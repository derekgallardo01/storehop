package com.storehop.app.sync

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.storehop.app.data.dao.CategoryDao
import com.storehop.app.data.dao.HouseholdMemberDao
import com.storehop.app.data.dao.ItemDao
import com.storehop.app.data.dao.ItemStoreXrefDao
import com.storehop.app.data.dao.PurchaseRecordDao
import com.storehop.app.data.dao.StoreCategoryOrderDao
import com.storehop.app.data.dao.StoreDao
import com.storehop.app.data.prefs.UserPreferencesSync
import com.storehop.app.data.util.HouseholdSessionProvider
import com.storehop.app.data.util.UserSessionProvider
import com.storehop.app.sync.dto.SyncCollections
import com.storehop.app.sync.dto.docId
import com.storehop.app.sync.dto.toDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Push side of the sync engine (M4).
 *
 * Started once from [com.storehop.app.StorehopApplication.onCreate]. For the life
 * of the process:
 *
 *   1. Subscribes to ([UserSessionProvider.userId], [HouseholdSessionProvider.householdId])
 *      jointly via `collectLatest` so EITHER a uid change (sign-in/out) or a
 *      householdId change (joining/leaving a household) cancels the previous
 *      jobs and starts fresh ones. PullState is keyed by uid; push jobs key
 *      off `householdId` because that's the access scope for the rows being
 *      pushed.
 *
 *   2. Per synced entity, watches that entity's `pendingSync = 1` Flow via
 *      `collect` (NOT `collectLatest`) so a successful push -- which itself
 *      triggers a Flow re-emission via `markPushed` -- doesn't cancel the
 *      in-flight loop processing the rest of the batch. With `collect`, the
 *      next emission is buffered and processed after the current loop returns.
 *      Each row gets serialized to its DTO and written to
 *      `/users/{householdId}/<collection>/{docId}` — the `users` segment
 *      name is preserved from v0.4 for backward compatibility; `householdId`
 *      equals `userId` for single-member households so existing data lands
 *      at the same wire path. On Firestore ack, `markPushed` flips
 *      `pendingSync` to 0.
 *
 * Failures (network, PERMISSION_DENIED, etc.) bubble up as exceptions; the row
 * stays `pendingSync = 1` and will be retried the next time its Flow re-emits.
 * Firestore's own offline queue covers transient network drops; durable loss
 * (app process killed mid-write) is recovered by the on-restart re-emission.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val session: UserSessionProvider,
    private val householdSession: HouseholdSessionProvider,
    private val pullStateRepo: PullStateRepository,
    private val applicationScope: CoroutineScope,
    private val itemDao: ItemDao,
    private val categoryDao: CategoryDao,
    private val storeDao: StoreDao,
    private val xrefDao: ItemStoreXrefDao,
    private val scoDao: StoreCategoryOrderDao,
    private val purchaseDao: PurchaseRecordDao,
    private val householdMemberDao: HouseholdMemberDao,
    private val userPreferencesSync: UserPreferencesSync,
) {
    @Volatile private var pushJob: Job? = null

    /**
     * v0.4: push jobs are gated on [PullState.SUCCEEDED] for the active uid.
     *
     * While `IN_PROGRESS` (pull running) or `FAILED` (pull errored), no push
     * happens. Local edits accumulate `pendingSync = 1` and flush to cloud
     * automatically when the state flips to `SUCCEEDED`.
     *
     * This is what closes the silent-corruption bug: even if seeded local
     * data is sitting at `pendingSync = 1` after a fresh install, it never
     * pushes until pull has either populated the local DB from cloud (so
     * the seeded rows get overwritten by cloud's authoritative copy) or
     * confirmed cloud was empty (so push freely populates cloud).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        applicationScope.launch {
            // Join (uid, householdId) so the engine restarts when EITHER
            // changes. uid drives the PullState gate; householdId scopes the
            // rows that get pushed.
            combine(session.userId, householdSession.householdId) { uid, hid -> uid to hid }
                .flatMapLatest { (uid, hid) ->
                    if (uid == null || hid == null) {
                        // Sign-out or no household yet: cancel + idle.
                        kotlinx.coroutines.flow.flowOf(Triple(null, null, PullState.NEEDED))
                    } else {
                        pullStateRepo.observe(uid).map { state -> Triple(uid, hid, state) }
                    }
                }
                .distinctUntilChanged()
                .collectLatest { (uid, hid, state) ->
                    pushJob?.cancel()
                    pushJob = if (uid != null && hid != null && state == PullState.SUCCEEDED) {
                        Log.i(TAG, "PullState=SUCCEEDED for uid=$uid hid=$hid; starting push jobs")
                        launchPushJobsFor(hid)
                    } else {
                        Log.i(TAG, "PullState=$state for uid=$uid hid=$hid; push paused")
                        null
                    }
                }
        }
    }

    private fun CoroutineScope.launchPushJobsFor(householdId: String): Job = launch {
        Log.i(TAG, "Starting push jobs for householdId=$householdId")
        val householdDoc = firestore.collection("users").document(householdId)

        launch {
            itemDao.observePendingPush(householdId).collect { rows ->
                rows.forEach { row ->
                    pushOne(householdDoc.collection(SyncCollections.ITEMS).document(row.id), row.toDto()) {
                        itemDao.markPushed(householdId, row.id)
                    }
                }
            }
        }

        launch {
            categoryDao.observePendingPush(householdId).collect { rows ->
                rows.forEach { row ->
                    pushOne(householdDoc.collection(SyncCollections.CATEGORIES).document(row.id), row.toDto()) {
                        categoryDao.markPushed(householdId, row.id)
                    }
                }
            }
        }

        launch {
            storeDao.observePendingPush(householdId).collect { rows ->
                rows.forEach { row ->
                    pushOne(householdDoc.collection(SyncCollections.STORES).document(row.id), row.toDto()) {
                        storeDao.markPushed(householdId, row.id)
                    }
                }
            }
        }

        launch {
            xrefDao.observePendingPush(householdId).collect { rows ->
                rows.forEach { row ->
                    val docId = row.docId()
                    pushOne(householdDoc.collection(SyncCollections.ITEM_STORE_XREFS).document(docId), row.toDto()) {
                        xrefDao.markPushed(householdId, row.itemId, row.storeId)
                    }
                }
            }
        }

        launch {
            scoDao.observePendingPush(householdId).collect { rows ->
                rows.forEach { row ->
                    val docId = row.docId()
                    pushOne(householdDoc.collection(SyncCollections.STORE_CATEGORY_ORDERS).document(docId), row.toDto()) {
                        scoDao.markPushed(householdId, row.storeId, row.categoryId)
                    }
                }
            }
        }

        launch {
            purchaseDao.observePendingPush(householdId).collect { rows ->
                rows.forEach { row ->
                    pushOne(householdDoc.collection(SyncCollections.PURCHASE_RECORDS).document(row.id), row.toDto()) {
                        purchaseDao.markPushed(householdId, row.id)
                    }
                }
            }
        }

        // v0.7.1 (fix): wire the household_members push. v0.7.0 created the
        // personal-household membership row locally with pendingSync = 1 but
        // never had a sync job to ship it — the local row was load-bearing
        // for the cloud-side membership lookup that v0.7.x sharing relies
        // on, but the path-uid fallback in firestore.rules masked the gap
        // for single-user flows. v0.7.1's Force-sync count surfaces the
        // stuck row → loop never reaches "Safe to uninstall."
        //
        // Wire path is `/memberships/{uid}/households/{hid}` (per-user
        // ledger, not per-household-scoped). The row carries both ids so
        // we read them straight off the entity. observePendingPush() has
        // no householdId param because memberships span households.
        launch {
            householdMemberDao.observePendingPush().collect { rows ->
                rows.forEach { row ->
                    val ref = firestore.collection("memberships")
                        .document(row.uid)
                        .collection("households")
                        .document(row.householdId)
                    val payload = mapOf<String, Any?>(
                        "uid" to row.uid,
                        "householdId" to row.householdId,
                        "displayName" to row.displayName,
                        "joinedAt" to row.joinedAt,
                        "isOwner" to row.isOwner,
                        "createdAt" to row.createdAt,
                        "updatedAt" to row.updatedAt,
                        "deletedAt" to row.deletedAt,
                    )
                    pushOne(ref, payload) {
                        householdMemberDao.markPushed(row.uid, row.householdId)
                    }
                }
            }
        }
    }

    private suspend fun <T : Any> pushOne(
        ref: com.google.firebase.firestore.DocumentReference,
        dto: T,
        markClean: suspend () -> Unit,
    ) {
        try {
            ref.set(dto).await()
            markClean()
        } catch (e: CancellationException) {
            // Cooperative cancellation (e.g. uid changed mid-push). Don't log; rethrow
            // so the parent Job actually winds down.
            throw e
        } catch (e: Exception) {
            // Leave pendingSync = 1 so the row gets re-pushed next time the Flow
            // re-emits. WARN because PERMISSION_DENIED in pre-M7 is expected;
            // once rules deploy in M7, this should fall silent.
            Log.w(TAG, "Push failed for ${ref.path}: ${e.message}")
        }
    }

    /**
     * v0.7.1: aggregated row-count of every entity table's `pendingSync = 1`
     * rows for the active household + the household_members table (which is
     * uid-scoped not household-scoped).
     *
     * Powers the Settings → Data → "Force sync now" UX: the count tells the
     * user how many local writes haven't reached Firestore yet. When the
     * count drops to 0 the UX shows "Safe to uninstall".
     */
    fun observeAllPendingCount(householdId: String): Flow<Int> = combine(
        itemDao.countPendingPush(householdId),
        categoryDao.countPendingPush(householdId),
        storeDao.countPendingPush(householdId),
        xrefDao.countPendingPush(householdId),
        scoDao.countPendingPush(householdId),
        purchaseDao.countPendingPush(householdId),
        householdMemberDao.countPendingPush(),
    ) { counts -> counts.sum() }

    /**
     * v0.7.1: nudge the push side to flush every pending row immediately,
     * and wait until the queue is empty (or the timeout elapses).
     *
     * Implementation: the continuous push loop in [start] already drains
     * `pendingSync = 1` rows on every Flow emission, so we don't need to
     * spawn a parallel push. We just:
     *   1. Force a synchronous push of the user-preferences doc (its
     *      observe-and-push loop has a 500 ms debounce; this skips the
     *      debounce wait so "Safe to uninstall" reflects the truth even
     *      if the user toggled something a moment earlier).
     *   2. Subscribe to [observeAllPendingCount] and suspend until it
     *      hits 0 — or [timeoutMs] expires.
     *
     * Returns `true` if the queue drained, `false` if the timeout fired
     * with rows still pending (so the UI can surface the stuck count).
     */
    suspend fun flushAllPending(
        householdId: String,
        uid: String,
        timeoutMs: Long = 30_000L,
    ): Boolean {
        // v0.9 repair pass: converge any item stranded at isNeeded = 0 by the
        // pre-v0.9 asymmetric un-check back to global-needed BEFORE we flush,
        // so the healed rows (flagged pendingSync = 1) push up in this same
        // drain. Idempotent; a no-op once data is already consistent.
        val repaired = xrefDao.repairStrandedNeededLinks(householdId, System.currentTimeMillis())
        if (repaired > 0) Log.i(TAG, "Force sync repaired $repaired stranded item↔store link(s)")
        userPreferencesSync.flushPending(uid)
        val drained = withTimeoutOrNull(timeoutMs) {
            observeAllPendingCount(householdId).first { it == 0 }
        }
        return drained != null
    }

    companion object {
        private const val TAG = "SyncEngine"
    }
}
