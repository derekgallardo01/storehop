package com.storehop.app.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.storehop.app.data.entity.ItemStoreXref
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemStoreXrefDao {

    @Query(
        """
        SELECT storeId FROM item_store_xref
        WHERE itemId = :itemId AND deletedAt IS NULL
        """,
    )
    fun observeStoreIdsForItem(itemId: String): Flow<List<String>>

    @Query(
        """
        SELECT * FROM item_store_xref
        WHERE itemId = :itemId AND deletedAt IS NULL
        """,
    )
    suspend fun findForItem(itemId: String): List<ItemStoreXref>

    @Upsert
    suspend fun upsert(xref: ItemStoreXref)

    /**
     * Batch upsert for the pull side. Mappers stamp `pendingSync = false`.
     * Called inside [PullWriteDao]'s single transaction.
     */
    @Upsert
    suspend fun upsertFromCloud(rows: List<ItemStoreXref>)

    @Query(
        """
        UPDATE item_store_xref
        SET deletedAt = :now, updatedAt = :now, pendingSync = 1
        WHERE itemId = :itemId AND storeId = :storeId AND userId = :userId
        """,
    )
    suspend fun softDelete(userId: String, itemId: String, storeId: String, now: Long)

    /**
     * Cascade-tombstone every live xref for an item. Used by the item soft-delete
     * flow so a deleted item doesn't leave xref orphans (the cross-store-sync
     * `ShoppingDao` query INNER JOINs through xrefs, so live xrefs against a
     * deleted item would surface ghost rows). Scoped by `userId`.
     */
    @Query(
        """
        UPDATE item_store_xref
        SET deletedAt = :now, updatedAt = :now, pendingSync = 1
        WHERE itemId = :itemId AND userId = :userId AND deletedAt IS NULL
        """,
    )
    suspend fun softDeleteForItem(userId: String, itemId: String, now: Long)

    /**
     * Cascade-tombstone every live xref pointing at a store. Used by the store
     * soft-delete flow so items previously tagged to the deleted store don't
     * still appear "sold at <tombstoned store>" in any relation that joins
     * items to stores through this junction.
     */
    @Query(
        """
        UPDATE item_store_xref
        SET deletedAt = :now, updatedAt = :now, pendingSync = 1
        WHERE storeId = :storeId AND userId = :userId AND deletedAt IS NULL
        """,
    )
    suspend fun softDeleteForStore(userId: String, storeId: String, now: Long)

    /**
     * Restore the cascade-tombstoned xrefs for a store. Filters by the exact
     * `deletedAt` used during the cascade so a later, separate tombstoning of
     * a different row at the same time doesn't accidentally come back too.
     */
    @Query(
        """
        UPDATE item_store_xref
        SET deletedAt = NULL, updatedAt = :now, pendingSync = 1
        WHERE storeId = :storeId AND userId = :userId AND deletedAt = :deletedAt
        """,
    )
    suspend fun restoreCascadeForStore(userId: String, storeId: String, deletedAt: Long, now: Long)

    /** Inverse of [softDeleteForItem]; same `deletedAt`-filter pattern. */
    @Query(
        """
        UPDATE item_store_xref
        SET deletedAt = NULL, updatedAt = :now, pendingSync = 1
        WHERE itemId = :itemId AND userId = :userId AND deletedAt = :deletedAt
        """,
    )
    suspend fun restoreCascadeForItem(userId: String, itemId: String, deletedAt: Long, now: Long)

    /**
     * Replace the set of stores an item is tagged to.
     * Tombstones any xref no longer in [storeIds] and upserts the new set.
     * `userId` is the parent item's userId — copied here to enforce the
     * cross-table ownership invariant.
     */
    @Transaction
    suspend fun setStoresForItem(
        itemId: String,
        storeIds: Set<String>,
        userId: String,
        now: Long,
    ) {
        val existing = findForItem(itemId)
        val existingIds = existing.map { it.storeId }.toSet()
        val toRemove = existingIds - storeIds
        val toAdd = storeIds - existingIds
        toRemove.forEach { softDelete(userId, itemId, it, now) }
        toAdd.forEach { storeId ->
            upsert(
                ItemStoreXref(
                    itemId = itemId,
                    storeId = storeId,
                    userId = userId,
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                ),
            )
        }
    }

    @androidx.room.Query("SELECT * FROM item_store_xref WHERE userId = :userId AND pendingSync = 1")
    fun observePendingPush(userId: String): kotlinx.coroutines.flow.Flow<List<ItemStoreXref>>

    @androidx.room.Query(
        """
        UPDATE item_store_xref SET pendingSync = 0
        WHERE itemId = :itemId AND storeId = :storeId AND userId = :userId
        """,
    )
    suspend fun markPushed(userId: String, itemId: String, storeId: String)

    /**
     * Mark this single (itemId, storeId) xref purchased at this store only.
     * Used by [markNeededAtStore]'s mirror — the per-store state-correction
     * path. The Shop-at-Store check-off cascades via
     * [markPurchasedAcrossAllStores] instead, which writes the same flips to
     * every store the item is tagged to.
     */
    @Query(
        """
        UPDATE item_store_xref
        SET isNeeded = 0,
            lastPurchasedAt = :now,
            updatedAt = :now,
            pendingSync = 1
        WHERE itemId = :itemId AND storeId = :storeId AND userId = :userId
        """,
    )
    suspend fun markPurchasedAtStore(userId: String, itemId: String, storeId: String, now: Long)

    /**
     * Cascade-mark every live xref for [itemId] as purchased: a single
     * shopping trip (one trip to one store) satisfies the need across every
     * store the item is tagged to. Used by the Shop-at-Store check-off so
     * that buying mozzarella at Lidl makes it disappear from the Aldi and
     * Pingo Doce lists too, matching how shopping-list apps usually behave.
     *
     * All affected rows share the same `lastPurchasedAt = :now`, which is
     * the precision marker [restorePurchaseAcrossAllStores] uses to undo a
     * cascade without touching unrelated history.
     */
    @Query(
        """
        UPDATE item_store_xref
        SET isNeeded = 0,
            lastPurchasedAt = :now,
            updatedAt = :now,
            pendingSync = 1
        WHERE itemId = :itemId AND userId = :userId AND deletedAt IS NULL
        """,
    )
    suspend fun markPurchasedAcrossAllStores(userId: String, itemId: String, now: Long)

    /**
     * Restore the (itemId, storeId) row to needed at this store. Used to
     * undo a mis-tap in the Shop-at-Store screen. lastPurchasedAt is left
     * intact -- the prior purchase still happened in history.
     */
    @Query(
        """
        UPDATE item_store_xref
        SET isNeeded = 1,
            updatedAt = :now,
            pendingSync = 1
        WHERE itemId = :itemId AND storeId = :storeId AND userId = :userId
        """,
    )
    suspend fun markNeededAtStore(userId: String, itemId: String, storeId: String, now: Long)

    /**
     * Inverse of [markPurchasedAcrossAllStores], filtered by exact
     * `lastPurchasedAt` so concurrent or older purchases of the same item
     * aren't accidentally restored. Restores the xrefs to `isNeeded = 1`
     * and clears `lastPurchasedAt`, since the snackbar-undo flow also
     * deletes the matching `PurchaseRecord` — it's "as if it never
     * happened," not a state correction.
     */
    @Query(
        """
        UPDATE item_store_xref
        SET isNeeded = 1,
            lastPurchasedAt = NULL,
            updatedAt = :now,
            pendingSync = 1
        WHERE itemId = :itemId AND userId = :userId
            AND lastPurchasedAt = :lastPurchasedAt
            AND isNeeded = 0
        """,
    )
    suspend fun restorePurchaseAcrossAllStores(
        userId: String,
        itemId: String,
        lastPurchasedAt: Long,
        now: Long,
    )
}
