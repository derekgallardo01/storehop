package com.storehop.app.sync

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.storehop.app.data.dao.PullWriteDao
import com.storehop.app.sync.dto.CategoryDto
import com.storehop.app.sync.dto.ItemDto
import com.storehop.app.sync.dto.ItemStoreXrefDto
import com.storehop.app.sync.dto.PurchaseRecordDto
import com.storehop.app.sync.dto.StoreCategoryOrderDto
import com.storehop.app.sync.dto.StoreDto
import com.storehop.app.sync.dto.SyncCollections
import com.storehop.app.sync.dto.toEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pull side of the sync engine (v0.4).
 *
 * Two responsibilities:
 *  1. [peek] — cheap "does this household have any cloud data?" check. One
 *     `.limit(1).get()` against `users/{householdId}/stores`. Drives the
 *     branch in [com.storehop.app.auth.FirebaseAuthSessionProvider]: cloud
 *     has data -> pull path; cloud empty -> existing orphan-claim path.
 *  2. [pullForHousehold] — fetch all six subcollections for a household in
 *     parallel, map to entities, write in a single all-or-nothing Room
 *     transaction.
 *
 * v0.7.0: the wire path stays `/users/{X}/...` — see [SyncCollections] —
 * with X now interpreted as `householdId`. Single-member households have
 * `householdId == userId` so existing cloud data persists at the same path.
 *
 * Mutex guards [pullForHousehold] per-household so a double sign-in tap
 * can't race itself. The mutex is process-wide; in practice only one
 * household is ever being pulled at a time, so the simpler single-Mutex
 * is fine.
 */
@Singleton
class PullCoordinator @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val pullWriteDao: PullWriteDao,
) {
    private val mutex = Mutex()

    sealed class PullResult {
        data class Success(
            val itemCount: Int,
            val categoryCount: Int,
            val storeCount: Int,
            val xrefCount: Int,
            val scoCount: Int,
            val purchaseRecordCount: Int,
        ) : PullResult()

        data class Failure(val cause: Throwable) : PullResult()
    }

    /**
     * Returns `true` if `users/{householdId}/stores` contains at least one
     * document. Throws on network/permission errors (caller decides how to
     * handle).
     *
     * Uses `stores` rather than `items` because every household has a stores
     * collection (seeded set ensures it), but only ever exists in cloud once
     * the device has pushed at least once. So presence of stores is the
     * cleanest "is this a returning household" signal.
     */
    suspend fun peek(householdId: String): Boolean {
        val snap = firestore.collection("users")
            .document(householdId)
            .collection(SyncCollections.STORES)
            .limit(1)
            .get()
            .await()
        return !snap.isEmpty
    }

    /**
     * Pull every cloud row for [householdId] and write to local Room in a
     * single transaction. Returns [PullResult.Success] with per-entity counts
     * on success, [PullResult.Failure] on any error (network, deserialization,
     * DB).
     *
     * Cloud always wins on pull: pulled rows write `pendingSync = false` so
     * they don't immediately re-push. Any prior local edits to the same row
     * id are overwritten -- documented v0.4 limitation; merge-anon-to-cloud
     * is a v0.5 question.
     */
    suspend fun pullForHousehold(householdId: String): PullResult = mutex.withLock {
        try {
            val householdDoc = firestore.collection("users").document(householdId)

            coroutineScope {
                // Fetch all six collections in parallel. Any failure
                // propagates and the catch below maps to PullResult.Failure;
                // the Room transaction never opens, so local DB is untouched.
                val itemsAsync = async {
                    householdDoc.collection(SyncCollections.ITEMS)
                        .get().await().toObjects(ItemDto::class.java).map { it.toEntity() }
                }
                val categoriesAsync = async {
                    householdDoc.collection(SyncCollections.CATEGORIES)
                        .get().await().toObjects(CategoryDto::class.java).map { it.toEntity() }
                }
                val storesAsync = async {
                    householdDoc.collection(SyncCollections.STORES)
                        .get().await().toObjects(StoreDto::class.java).map { it.toEntity() }
                }
                val xrefsAsync = async {
                    householdDoc.collection(SyncCollections.ITEM_STORE_XREFS)
                        .get().await().toObjects(ItemStoreXrefDto::class.java).map { it.toEntity() }
                }
                val scosAsync = async {
                    householdDoc.collection(SyncCollections.STORE_CATEGORY_ORDERS)
                        .get().await().toObjects(StoreCategoryOrderDto::class.java).map { it.toEntity() }
                }
                val purchaseRecordsAsync = async {
                    householdDoc.collection(SyncCollections.PURCHASE_RECORDS)
                        .get().await().toObjects(PurchaseRecordDto::class.java).map { it.toEntity() }
                }

                val items = itemsAsync.await()
                val categories = categoriesAsync.await()
                val stores = storesAsync.await()
                val xrefs = xrefsAsync.await()
                val scos = scosAsync.await()
                val purchaseRecords = purchaseRecordsAsync.await()

                Log.i(
                    TAG,
                    "Pull for hid=$householdId: ${items.size} items, ${categories.size} categories, " +
                        "${stores.size} stores, ${xrefs.size} xrefs, ${scos.size} scos, " +
                        "${purchaseRecords.size} purchaseRecords",
                )

                pullWriteDao.replaceAllForUid(
                    householdId = householdId,
                    items = items,
                    categories = categories,
                    stores = stores,
                    xrefs = xrefs,
                    scoOrders = scos,
                    purchaseRecords = purchaseRecords,
                )

                PullResult.Success(
                    itemCount = items.size,
                    categoryCount = categories.size,
                    storeCount = stores.size,
                    xrefCount = xrefs.size,
                    scoCount = scos.size,
                    purchaseRecordCount = purchaseRecords.size,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Pull failed for hid=$householdId", e)
            PullResult.Failure(e)
        }
    }

    private companion object {
        const val TAG = "PullCoordinator"
    }
}
