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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 *   1. Subscribes to [UserSessionProvider.userId]. Every time the uid changes
 *      (sign-in, sign-out, anonymous-to-Google upgrade), the previous push jobs
 *      are cancelled and fresh ones for the new uid are launched.
 *
 *   2. Per synced entity, watches that entity's `pendingSync = 1` Flow. For
 *      each batch the Flow emits, serializes each row to its DTO and writes
 *      it to `/users/{uid}/<collection>/{docId}`. On Firestore ack,
 *      `markPushed(uid, id)` flips the row's `pendingSync` to 0.
 *
 * Failures (network, PERMISSION_DENIED, etc.) bubble up as exceptions; the row
 * stays `pendingSync = 1` and will be retried the next time its Flow re-emits
 * (which happens immediately because the Flow is hot and includes the dirty
 * row). Firestore's own offline queue covers transient network drops; durable
 * loss (app process killed mid-write) is recovered by the on-restart re-emission.
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
    private var pushJob: Job? = null

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
            itemDao.observePendingPush(uid).collectLatest { rows ->
                rows.forEach { row ->
                    pushOne(userDoc.collection(SyncCollections.ITEMS).document(row.id), row.toDto()) {
                        itemDao.markPushed(uid, row.id)
                    }
                }
            }
        }

        launch {
            categoryDao.observePendingPush(uid).collectLatest { rows ->
                rows.forEach { row ->
                    pushOne(userDoc.collection(SyncCollections.CATEGORIES).document(row.id), row.toDto()) {
                        categoryDao.markPushed(uid, row.id)
                    }
                }
            }
        }

        launch {
            storeDao.observePendingPush(uid).collectLatest { rows ->
                rows.forEach { row ->
                    pushOne(userDoc.collection(SyncCollections.STORES).document(row.id), row.toDto()) {
                        storeDao.markPushed(uid, row.id)
                    }
                }
            }
        }

        launch {
            xrefDao.observePendingPush(uid).collectLatest { rows ->
                rows.forEach { row ->
                    val docId = row.docId()
                    pushOne(userDoc.collection(SyncCollections.ITEM_STORE_XREFS).document(docId), row.toDto()) {
                        xrefDao.markPushed(uid, row.itemId, row.storeId)
                    }
                }
            }
        }

        launch {
            scoDao.observePendingPush(uid).collectLatest { rows ->
                rows.forEach { row ->
                    val docId = row.docId()
                    pushOne(userDoc.collection(SyncCollections.STORE_CATEGORY_ORDERS).document(docId), row.toDto()) {
                        scoDao.markPushed(uid, row.storeId, row.categoryId)
                    }
                }
            }
        }

        launch {
            purchaseDao.observePendingPush(uid).collectLatest { rows ->
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
        } catch (e: Exception) {
            // Leave pendingSync = 1 so the row gets re-pushed next time the
            // Flow re-emits. Logged at WARN because PERMISSION_DENIED in
            // pre-M7 is expected; once rules deploy, this should fall silent.
            Log.w(TAG, "Push failed for ${ref.path}: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "SyncEngine"
    }
}
