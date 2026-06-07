package com.storehop.app.auth

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.storehop.app.data.dao.HouseholdMemberDao
import com.storehop.app.data.dao.LocalOnlyMigrationDao
import com.storehop.app.data.entity.HouseholdMember
import com.storehop.app.data.prefs.UserPreferencesSync
import com.storehop.app.data.util.HouseholdSessionProvider
import com.storehop.app.data.util.HouseholdSwitcher
import com.storehop.app.data.util.UserSessionProvider
import com.storehop.app.sync.PullCoordinator
import com.storehop.app.sync.PullState
import com.storehop.app.sync.PullStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wires [FirebaseAuth]'s mutable session state into a hot [StateFlow] that the
 * rest of the app can observe. Construction has the side effect of:
 *
 *  1. Subscribing to `FirebaseAuth.addAuthStateListener` and pushing the current
 *     uid into an internal pipe.
 *  2. Kicking off `signInAnonymously()` if no user is present, so the app gets
 *     a uid quickly on first launch and never sits in a "signed-out" state for
 *     longer than a network round-trip.
 *  3. v0.7.0 Phase 2 — bootstrapping the user's active household. After the
 *     uid resolves, we look up [HouseholdMemberDao.activeMembershipFor] to
 *     find an existing membership. If none exists we create a personal
 *     household (`hid = uid`) and insert the corresponding local
 *     household_members row; sync pushes it to Firestore on the next tick.
 *  4. Running the appropriate sync flow for each uid change *before* the public
 *     [userId] + [householdId] StateFlows flip. The two ids are published
 *     together, atomically, so observers (repos / SyncEngine) never see a
 *     mismatched pair.
 *     - If cloud has data for this household → pull (skip orphan-claim; cloud wins).
 *     - If cloud is empty → orphan-claim (existing path; push afterwards
 *       populates the cloud).
 *
 * Anonymous accounts persist across app restarts (FirebaseAuth keeps the
 * credential on disk), so this only actually calls `signInAnonymously()` on
 * very-first-launch or after a "wipe app data" reset.
 */
@Singleton
class FirebaseAuthSessionProvider @Inject constructor(
    private val auth: FirebaseAuth,
    private val migrationDao: LocalOnlyMigrationDao,
    private val householdMemberDao: HouseholdMemberDao,
    private val pullCoordinator: PullCoordinator,
    private val pullStateRepo: PullStateRepository,
    private val userPreferencesSync: UserPreferencesSync,
    private val clock: Clock,
    private val applicationScope: CoroutineScope,
) : UserSessionProvider, HouseholdSessionProvider, HouseholdSwitcher {

    /**
     * Raw pipe from the auth listener. Internal-only -- observers see the gated
     * [userId] flow below, which only flips after sync completes.
     */
    private val rawUid = MutableStateFlow(auth.currentUser?.uid)

    /**
     * Initialized to the disk-cached uid (returning users) so observers don't
     * see `null` on cold launch. Subsequent changes are gated behind a sync
     * step (pull or claim): when the auth listener flips to a new uid, we run
     * the appropriate sync BEFORE updating this flow, so the next observer
     * re-query already finds the right rows under the new uid.
     */
    private val _userId = MutableStateFlow(auth.currentUser?.uid)
    override val userId: StateFlow<String?> = _userId.asStateFlow()

    /**
     * The user's active household id. Published together with [userId] so
     * repositories observing the household abstraction always see a
     * consistent (uid, householdId) pair — never a stale household for a
     * fresh uid. Null when signed out.
     */
    private val _householdId = MutableStateFlow<String?>(null)
    override val householdId: StateFlow<String?> = _householdId.asStateFlow()

    init {
        auth.addAuthStateListener { state ->
            val newUid = state.currentUser?.uid
            if (rawUid.value != newUid) {
                Log.i(TAG, "Auth state changed: ${rawUid.value} -> $newUid")
                rawUid.value = newUid
            }
        }
        if (auth.currentUser == null) {
            Log.i(TAG, "No current user; signing in anonymously.")
            auth.signInAnonymously()
                .addOnSuccessListener { result ->
                    Log.i(TAG, "Anonymous sign-in succeeded: ${result.user?.uid}")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Anonymous sign-in failed", e)
                }
        }

        // Gate every uid change behind the sync step. Runs in applicationScope
        // so the work survives any single ViewModel's lifetime, and serially
        // via collect so two rapid auth changes can't overlap.
        applicationScope.launch {
            // StateFlow already drops duplicate emissions; no distinctUntilChanged needed.
            rawUid.collect { newUid ->
                if (newUid == null) {
                    // Sign-out: nothing to migrate, just propagate the null.
                    if (_userId.value != null) {
                        Log.i(TAG, "Signed out; clearing userId + householdId")
                        _userId.value = null
                        _householdId.value = null
                    }
                    return@collect
                }
                // Always resolve the household locally — it's a cheap DAO
                // lookup, and we need a valid value to publish even on the
                // cold-launch short-circuit path.
                val resolvedHouseholdId = resolveHouseholdFor(newUid)
                // Cold-launch short-circuit: when _userId.value already
                // matches the cached uid AND pullState is SUCCEEDED, skip
                // the Firestore peek (saves a read on every launch). For
                // NEEDED / FAILED / stuck IN_PROGRESS (process died mid-
                // pull), we still need to run sync -- otherwise push stays
                // paused forever and the user accumulates dirty rows that
                // never ship to cloud.
                val canSkipSync = _userId.value == newUid && run {
                    val state = pullStateRepo.observe(newUid).first()
                    if (state != PullState.SUCCEEDED) {
                        Log.i(TAG, "Re-running sync for uid=$newUid (pullState=$state)")
                        false
                    } else {
                        true
                    }
                }
                if (!canSkipSync) {
                    runSyncFor(uid = newUid, householdId = resolvedHouseholdId)
                }
                // Publish both ids together so observers never see a mismatch.
                _householdId.value = resolvedHouseholdId
                _userId.value = newUid

                // v0.7.1: kick off the cloud-prefs reconcile in the background.
                // Push current prefs if cloud is absent/stale; pull cloud if
                // newer. Load-bearing for the sideload→Play transition —
                // first launch of v0.7.1 on Mike's phone captures his
                // local prefs to /userPrefs/{uid} before he ever opens
                // Settings. Fire-and-forget so the auth listener doesn't
                // block on a Firestore round-trip.
                applicationScope.launch {
                    userPreferencesSync.reconcile(newUid)
                }
            }
        }
    }

    /**
     * Resolve the user's active household. Phase 2: local-first.
     *  1. If a local household_members row exists for this uid (the device
     *     has joined a household before), reuse it.
     *  2. Otherwise create a personal household with `hid = uid` and insert
     *     the local membership row. Sync pushes it to Firestore on the
     *     first push tick after `_householdId` publishes.
     *
     * Phase 3 adds a Firestore peek (`/memberships/{uid}/households`) as a
     * fallback between (1) and (2) so a second device of the same Google
     * account inherits the existing household without needing the invite
     * code dance. For v0.7.0 ship we accept the limitation that the second
     * device of a sharing user has to be re-invited.
     */
    private suspend fun resolveHouseholdFor(uid: String): String {
        val existing = householdMemberDao.activeMembershipFor(uid)
        if (existing != null) {
            Log.i(TAG, "Resolved existing household for uid=$uid: hid=${existing.householdId}")
            return existing.householdId
        }
        val now = clock.millis()
        Log.i(TAG, "Creating personal household for uid=$uid (hid=$uid)")
        householdMemberDao.upsert(
            HouseholdMember(
                uid = uid,
                householdId = uid,
                displayName = auth.currentUser?.displayName,
                joinedAt = now,
                isOwner = true,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
                // pendingSync defaults to true so the next push pushes this
                // membership to /memberships/{uid}/households/{uid}.
            ),
        )
        return uid
    }

    /**
     * v0.7.0 Phase 3: re-point the active household after an invite-accept
     * (Amanda joining Mike's household) or a leave-household action.
     * Re-runs the same sync gate the auth listener uses so the cloud
     * pull executes against the new path and pullState transitions
     * correctly. Updates [householdId] only after sync completes so
     * observing repos don't briefly see the new id with stale local data.
     *
     * No-op if no user is signed in.
     */
    override suspend fun switchToHousehold(newHouseholdId: String) {
        val uid = _userId.value ?: run {
            Log.w(TAG, "switchToHousehold($newHouseholdId) ignored: no signed-in user")
            return
        }
        if (_householdId.value == newHouseholdId) {
            Log.i(TAG, "switchToHousehold($newHouseholdId) is a no-op (already active)")
            return
        }
        Log.i(TAG, "Switching household for uid=$uid: ${_householdId.value} -> $newHouseholdId")
        runSyncFor(uid = uid, householdId = newHouseholdId)
        _householdId.value = newHouseholdId
    }

    /**
     * v0.4: peek Firestore. If cloud has data → pull (cloud is authoritative;
     * skip orphan-claim entirely). If cloud is empty → existing claim path.
     *
     * Always sets `pullState` before returning; the [com.storehop.app.sync.SyncEngine]
     * gates push jobs on this state, so push is paused until the state lands
     * on `SUCCEEDED`. A failure here means the user signs in but pushes are
     * paused — Settings shows a Retry banner.
     */
    private suspend fun runSyncFor(uid: String, householdId: String) {
        pullStateRepo.set(uid, PullState.IN_PROGRESS)
        try {
            val cloudHasData = pullCoordinator.peek(householdId)
            if (cloudHasData) {
                Log.i(TAG, "Cloud has data for hid=$householdId; pulling")
                val result = pullCoordinator.pullForHousehold(householdId)
                val finalState = when (result) {
                    is PullCoordinator.PullResult.Success -> PullState.SUCCEEDED
                    is PullCoordinator.PullResult.Failure -> PullState.FAILED
                }
                pullStateRepo.set(uid, finalState)
            } else {
                Log.i(TAG, "Cloud is empty for hid=$householdId; running orphan-claim path")
                runClaimsFor(uid)
                pullStateRepo.set(uid, PullState.SUCCEEDED)
            }
        } catch (e: Exception) {
            // Peek failure or unexpected error during sync. Fail closed:
            // pullState=FAILED keeps push paused so seeded data can't leak
            // to the cloud. The user sees a Retry banner.
            Log.e(TAG, "Sync failed for uid=$uid hid=$householdId", e)
            pullStateRepo.set(uid, PullState.FAILED)
        }
    }

    /**
     * Re-stamp local-only and orphan-uid rows onto the active uid. Used when
     * the cloud is empty (first-ever Google sign-in for this account) -- those
     * rows were either pre-Firebase data or under a previous anonymous uid,
     * and now need to be tagged to the active user so the push side can
     * publish them.
     *
     * Catches exceptions so a migration failure (corrupt DB, etc.) doesn't
     * permanently trap the app on the previous uid -- we still proceed; the
     * data is just invisible until the next attempt succeeds.
     */
    private suspend fun runClaimsFor(uid: String) {
        try {
            val localOnly = migrationDao.countLocalOnlyStores()
            if (localOnly > 0) {
                Log.i(TAG, "Claiming $localOnly local-only stores (and cohort) to uid=$uid")
                migrationDao.claimAllLocalOnlyRowsAs(uid)
            }
            val orphans = migrationDao.countOrphanStores(uid)
            if (orphans > 0) {
                Log.i(TAG, "Claiming $orphans orphan-uid stores (and cohort) to uid=$uid")
                migrationDao.claimAllOrphanRowsAs(uid)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Claim migration failed for uid=$uid; publishing uid anyway", e)
        }
    }

    companion object {
        private const val TAG = "FirebaseAuthSession"
    }
}
