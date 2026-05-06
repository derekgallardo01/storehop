package com.storehop.app.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.storehop.app.data.entity.StoreCategoryOrder
import kotlinx.coroutines.flow.Flow

@Dao
interface StoreCategoryOrderDao {

    @Query(
        """
        SELECT * FROM store_category_order
        WHERE storeId = :storeId AND deletedAt IS NULL
        ORDER BY displayOrder, categoryId
        """,
    )
    fun observeForStore(storeId: String): Flow<List<StoreCategoryOrder>>

    @Query(
        """
        SELECT * FROM store_category_order
        WHERE storeId = :storeId AND deletedAt IS NULL
        """,
    )
    suspend fun findForStore(storeId: String): List<StoreCategoryOrder>

    @Upsert
    suspend fun upsert(order: StoreCategoryOrder)

    @Query(
        """
        UPDATE store_category_order
        SET deletedAt = :now, updatedAt = :now
        WHERE storeId = :storeId AND categoryId = :categoryId
        """,
    )
    suspend fun softDelete(storeId: String, categoryId: String, now: Long)

    /** Cascade-tombstone every live SCO row for a store. Used when the store is soft-deleted. */
    @Query(
        """
        UPDATE store_category_order
        SET deletedAt = :now, updatedAt = :now
        WHERE storeId = :storeId AND userId = :userId AND deletedAt IS NULL
        """,
    )
    suspend fun softDeleteForStore(userId: String, storeId: String, now: Long)

    /** Cascade-tombstone every live SCO row for a category. Used when the category is soft-deleted. */
    @Query(
        """
        UPDATE store_category_order
        SET deletedAt = :now, updatedAt = :now
        WHERE categoryId = :categoryId AND userId = :userId AND deletedAt IS NULL
        """,
    )
    suspend fun softDeleteForCategory(userId: String, categoryId: String, now: Long)

    /**
     * Atomic replace: tombstone the existing order set for [storeId], upsert the new ordered list.
     * Each entry's `displayOrder` should already be set on the input rows.
     */
    @Transaction
    suspend fun replaceAllForStore(
        storeId: String,
        ordered: List<StoreCategoryOrder>,
        now: Long,
    ) {
        val existing = findForStore(storeId)
        val incomingKeys = ordered.map { it.categoryId }.toSet()
        existing
            .filter { it.categoryId !in incomingKeys }
            .forEach { softDelete(it.storeId, it.categoryId, now) }
        ordered.forEach { upsert(it.copy(updatedAt = now, deletedAt = null)) }
    }
}
