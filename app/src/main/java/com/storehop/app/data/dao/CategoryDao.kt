package com.storehop.app.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.storehop.app.data.entity.Category
import kotlinx.coroutines.flow.Flow

/**
 * v0.7.0 access scope: queries filter by `householdId` (not `userId`).
 * See [StoreDao] for the rationale.
 */
@Dao
interface CategoryDao {

    @Query(
        """
        SELECT * FROM categories
        WHERE householdId = :householdId AND deletedAt IS NULL
          AND (:includeArchived OR isArchived = 0)
        ORDER BY displayOrder ASC, name COLLATE NOCASE
        """,
    )
    fun observeAll(householdId: String, includeArchived: Boolean): Flow<List<Category>>

    @Query(
        """
        SELECT * FROM categories
        WHERE id = :id AND householdId = :householdId AND deletedAt IS NULL
        """,
    )
    suspend fun findById(householdId: String, id: String): Category?

    @Query(
        """
        SELECT * FROM categories
        WHERE householdId = :householdId AND deletedAt IS NULL
          AND name = :name COLLATE NOCASE
        LIMIT 1
        """,
    )
    suspend fun findByName(householdId: String, name: String): Category?

    /** Mirror of StoreDao.findAnyByName -- includes tombstones for the resurrect-on-re-add path. */
    @Query(
        """
        SELECT * FROM categories
        WHERE householdId = :householdId
          AND name = :name COLLATE NOCASE
        LIMIT 1
        """,
    )
    suspend fun findAnyByName(householdId: String, name: String): Category?

    @Upsert
    suspend fun upsert(category: Category)

    /**
     * Batch upsert for the pull side. Mappers stamp `pendingSync = false`.
     * Called inside [PullWriteDao]'s single transaction.
     */
    @Upsert
    suspend fun upsertFromCloud(rows: List<Category>)

    @Query(
        """
        UPDATE categories
        SET isArchived = :archived, updatedAt = :now, pendingSync = 1
        WHERE id = :id AND householdId = :householdId
        """,
    )
    suspend fun setArchived(householdId: String, id: String, archived: Boolean, now: Long)

    @Query(
        """
        UPDATE categories
        SET deletedAt = :now, updatedAt = :now, pendingSync = 1
        WHERE id = :id AND householdId = :householdId
        """,
    )
    suspend fun softDelete(householdId: String, id: String, now: Long)

    /** Tombstone-aware lookup. Returns the row regardless of `deletedAt`. */
    @Query("SELECT * FROM categories WHERE id = :id AND householdId = :householdId LIMIT 1")
    suspend fun findAnyById(householdId: String, id: String): Category?

    /** Inverse of [softDelete]: clears `deletedAt`, re-flags pendingSync. */
    @Query(
        """
        UPDATE categories
        SET deletedAt = NULL, updatedAt = :now, pendingSync = 1
        WHERE id = :id AND householdId = :householdId
        """,
    )
    suspend fun restoreFromTombstone(householdId: String, id: String, now: Long)

    @Query("SELECT * FROM categories WHERE householdId = :householdId AND pendingSync = 1")
    fun observePendingPush(householdId: String): Flow<List<Category>>

    @Query("UPDATE categories SET pendingSync = 0 WHERE id = :id AND householdId = :householdId")
    suspend fun markPushed(householdId: String, id: String)

    /**
     * Highest displayOrder among alive categories in this household, or
     * null when the household has none yet. Used by the add-category
     * paths to append new rows at the end of the global Manage
     * Categories list.
     */
    @Query(
        """
        SELECT MAX(displayOrder) FROM categories
        WHERE householdId = :householdId AND deletedAt IS NULL
        """,
    )
    suspend fun maxDisplayOrder(householdId: String): Int?

    /**
     * All categories tombstoned at exactly [deletedAt]. Used by
     * `undoSoftDeleteMany` to restore the precise batch a bulk-delete
     * tombstoned.
     */
    @Query(
        """
        SELECT * FROM categories
        WHERE householdId = :householdId AND deletedAt = :deletedAt
        """,
    )
    suspend fun findTombstonedAt(householdId: String, deletedAt: Long): List<Category>

    /**
     * Rewrite the displayOrder of a single category. Called inside the
     * repository's `reorder` transaction once per affected row.
     */
    @Query(
        """
        UPDATE categories
        SET displayOrder = :order, updatedAt = :now, pendingSync = 1
        WHERE id = :id AND householdId = :householdId
        """,
    )
    suspend fun updateDisplayOrder(householdId: String, id: String, order: Int, now: Long)
}
