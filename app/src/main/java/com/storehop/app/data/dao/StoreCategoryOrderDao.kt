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

    @Query(
        """
        SELECT * FROM store_category_order
        WHERE storeId = :storeId AND categoryId = :categoryId
        """,
    )
    suspend fun findAnyByPk(storeId: String, categoryId: String): StoreCategoryOrder?

    @Query(
        """
        SELECT MAX(displayOrder) FROM store_category_order
        WHERE storeId = :storeId AND deletedAt IS NULL
        """,
    )
    suspend fun maxDisplayOrderForStore(storeId: String): Int?

    @Upsert
    suspend fun upsert(order: StoreCategoryOrder)

    /**
     * Idempotently make sure (storeId, categoryId) has a live SCO row, so the
     * category becomes visible/orderable in Edit-aisle for that store. Append
     * semantics: a brand-new or revived row gets `displayOrder = max + 1`, so
     * it lands at the bottom of the existing aisle order.
     *
     * Called from [com.storehop.app.data.repository.ItemRepositoryImpl] after
     * an item is saved with a non-null category at one or more stores; until
     * v0.5.1 this was a UX gap where custom user categories never got an SCO
     * row and so couldn't be aisle-ordered, even though items in them shopped
     * fine.
     */
    @Transaction
    suspend fun appendIfMissing(storeId: String, categoryId: String, userId: String, now: Long) {
        val existing = findAnyByPk(storeId, categoryId)
        if (existing != null && existing.deletedAt == null) return
        val nextOrder = (maxDisplayOrderForStore(storeId) ?: -1) + 1
        if (existing == null) {
            upsert(
                StoreCategoryOrder(
                    storeId = storeId,
                    categoryId = categoryId,
                    displayOrder = nextOrder,
                    isSeeded = false,
                    userId = userId,
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                ),
            )
        } else {
            // Reviving a tombstoned row: reposition to the end so the user can
            // drag it where they want, rather than having it pop back into a
            // potentially surprising old slot.
            upsert(
                existing.copy(
                    displayOrder = nextOrder,
                    updatedAt = now,
                    deletedAt = null,
                    pendingSync = true,
                ),
            )
        }
    }

    /**
     * Batch upsert for the pull side. Mappers stamp `pendingSync = false`.
     * Called inside [PullWriteDao]'s single transaction.
     */
    @Upsert
    suspend fun upsertFromCloud(rows: List<StoreCategoryOrder>)

    @Query(
        """
        UPDATE store_category_order
        SET deletedAt = :now, updatedAt = :now, pendingSync = 1
        WHERE storeId = :storeId AND categoryId = :categoryId
        """,
    )
    suspend fun softDelete(storeId: String, categoryId: String, now: Long)

    /** Cascade-tombstone every live SCO row for a store. Used when the store is soft-deleted. */
    @Query(
        """
        UPDATE store_category_order
        SET deletedAt = :now, updatedAt = :now, pendingSync = 1
        WHERE storeId = :storeId AND userId = :userId AND deletedAt IS NULL
        """,
    )
    suspend fun softDeleteForStore(userId: String, storeId: String, now: Long)

    /** Inverse of [softDeleteForStore], filtered by exact `deletedAt`. */
    @Query(
        """
        UPDATE store_category_order
        SET deletedAt = NULL, updatedAt = :now, pendingSync = 1
        WHERE storeId = :storeId AND userId = :userId AND deletedAt = :deletedAt
        """,
    )
    suspend fun restoreCascadeForStore(userId: String, storeId: String, deletedAt: Long, now: Long)

    /** Cascade-tombstone every live SCO row for a category. Used when the category is soft-deleted. */
    @Query(
        """
        UPDATE store_category_order
        SET deletedAt = :now, updatedAt = :now, pendingSync = 1
        WHERE categoryId = :categoryId AND userId = :userId AND deletedAt IS NULL
        """,
    )
    suspend fun softDeleteForCategory(userId: String, categoryId: String, now: Long)

    /** Inverse of [softDeleteForCategory], filtered by exact `deletedAt`. */
    @Query(
        """
        UPDATE store_category_order
        SET deletedAt = NULL, updatedAt = :now, pendingSync = 1
        WHERE categoryId = :categoryId AND userId = :userId AND deletedAt = :deletedAt
        """,
    )
    suspend fun restoreCascadeForCategory(userId: String, categoryId: String, deletedAt: Long, now: Long)

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
        ordered.forEach { upsert(it.copy(updatedAt = now, deletedAt = null, pendingSync = true)) }
    }

    @Query("SELECT * FROM store_category_order WHERE userId = :userId AND pendingSync = 1")
    fun observePendingPush(userId: String): Flow<List<StoreCategoryOrder>>

    @Query(
        """
        UPDATE store_category_order SET pendingSync = 0
        WHERE storeId = :storeId AND categoryId = :categoryId AND userId = :userId
        """,
    )
    suspend fun markPushed(userId: String, storeId: String, categoryId: String)
}
