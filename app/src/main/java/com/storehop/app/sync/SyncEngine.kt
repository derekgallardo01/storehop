package com.storehop.app.sync

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.storehop.app.data.dao.CategoryDao
import com.storehop.app.data.dao.ItemDao
import com.storehop.app.data.dao.ItemStoreXrefDao
import com.storehop.app.data.dao.PurchaseRecordDao
import com.storehop.app.data.dao.StoreCategoryOrderDao
import com.storehop.app.data.dao.StoreDao
import com.storehop.app.data.util.UserSessionProvider
import com.storehop.app.sync.dto.SyncCollections
import com.storehop.app.sync.dto.docId
import com.storehop.app.sync.dto.toDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Push side of the sync engine (M4).
 *
 * Started once from [com.storehop.app.StorehopApplication.onCreate]. For the life
 * of the process:
 *
 *   1. Subscribes to [UserSessionProvider.userId] via `collectLatest` so that a
 *      uid change (sign-in / sign-out / anon-to-Google upgrade) cancels the
 *      previous user's push jobs and starts fresh ones.
 *
 *   2. Per synced entity, watches that entity's `pendingSync = 1` Flow via
 *      `collect` (NOT `collectLatest`) so a successful push -- which itself
 *      triggers a Flow re-emission via `markPushed` -- doesn't cancel the
 *      in-flight loop processing the rest of the batch. With `collect`, the
 *      next emission is buffered and processed after the current loop returns.
 *      Each row gets serialized to its DTO and written to
 *      `/users/{uid}/<collection>/{docId}`. On Firestore ack, `markPushed`
 *      flips `pendingSync` to 0.
 *
 * Failures (network, PERMISSION_DENIED, etc.) bubble up as exceptions; the row
 * stays `pendingSync = 1` and will be retried the next time its Flow re-emits.
 * Firestore's own offline queue covers transient network drops; durable loss
 * (app process killed mid-write) is recovered by the on-restart re-emission.
 *
 * Pull side lands in M5.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val session: UserSessionProvider,
    private val applicationScope: CoroutineScope,
    private val itemDao: ItemDao,
    private val categoryDao: CategoryDao,
    private val storeDao: StoreDao,
    private val xrefDao: ItemStoreXrefDao,
    private val scoDao: StoreCategoryOrderDao,
    private val purchaseDao: PurchaseRecordDao,
) {
    @Volatile private var pushJob: Job? = null

    fun start() {
        applicationScope.launch {
            session.userId.collectLatest { uid ->
                pushJob?.cancel()
                pushJob = if (uid == null) null else launchPushJobsFor(uid)
            }
        }
    }

    private fun CoroutineScope.launchPushJobsFor(uid: String): Job = launch {
        Log.i(TAG, "Starting push jobs for uid=$uid")
        val userDoc = firestore.collection("users").document(uid)

        launch {
            itemDao.observePendingPush(uid).collect { rows ->
                rows.forEach { row ->
                    pushOne(userDoc.collection(SyncCollections.ITEMS).document(row.id), row.toDto()) {
                        itemDao.markPushed(uid, row.id)
                    }
                }
            }
        }

        launch {
            categoryDao.observePendingPush(uid).collect { rows ->
                rows.forEach { row ->
                    pushOne(userDoc.collection(SyncCollections.CATEGORIES).document(row.id), row.toDto()) {
                        categoryDao.markPushed(uid, row.id)
                    }
                }
            }
        }

        launch {
            storeDao.observePendingPush(uid).collect { rows ->
                rows.forEach { row ->
                    pushOne(userDoc.collection(SyncCollections.STORES).document(row.id), row.toDto()) {
                        storeDao.markPushed(uid, row.id)
                    }
                }
            }
        }

        launch {
            xrefDao.observePendingPush(uid).collect { rows ->
                rows.forEach { row ->
                    val docId = row.docId()
                    pushOne(userDoc.collection(SyncCollections.ITEM_STORE_XREFS).document(docId), row.toDto()) {
                        xrefDao.markPushed(uid, row.itemId, row.storeId)
                    }
                }
            }
        }

        launch {
            scoDao.observePendingPush(uid).collect { rows ->
                rows.forEach { row ->
                    val docId = row.docId()
                    pushOne(userDoc.collection(SyncCollections.STORE_CATEGORY_ORDERS).document(docId), row.toDto()) {
                        scoDao.markPushed(uid, row.storeId, row.categoryId)
                    }
                }
            }
        }

        launch {
            purchaseDao.observePendingPush(uid).collect { rows ->
                rows.forEach { row ->
                    pushOne(userDoc.collection(SyncCollections.PURCHASE_RECORDS).document(row.id), row.toDto()) {
                        purchaseDao.markPushed(uid, row.id)
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

    companion object {
        private const val TAG = "SyncEngine"
    }
}
