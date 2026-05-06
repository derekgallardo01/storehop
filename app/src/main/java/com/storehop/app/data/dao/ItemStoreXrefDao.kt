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

    @Query(
        """
        UPDATE item_store_xref
        SET deletedAt = :now, updatedAt = :now
        WHERE itemId = :itemId AND storeId = :storeId
        """,
    )
    suspend fun softDelete(itemId: String, storeId: String, now: Long)

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
        toRemove.forEach { softDelete(itemId, it, now) }
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
}
