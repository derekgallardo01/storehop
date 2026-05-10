package com.storehop.app.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.storehop.app.data.entity.Category
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    @Query(
        """
        SELECT * FROM categories
        WHERE userId = :userId AND deletedAt IS NULL
          AND (:includeArchived OR isArchived = 0)
        ORDER BY displayOrder ASC, name COLLATE NOCASE
        """,
    )
    fun observeAll(userId: String, includeArchived: Boolean): Flow<List<Category>>

    @Query(
        """
        SELECT * FROM categories
        WHERE id = :id AND userId = :userId AND deletedAt IS NULL
        """,
    )
    suspend fun findById(userId: String, id: String): Category?

    @Query(
        """
        SELECT * FROM categories
        WHERE userId = :userId AND deletedAt IS NULL
          AND name = :name COLLATE NOCASE
        LIMIT 1
        """,
    )
    suspend fun findByName(userId: String, name: String): Category?

    /** Mirror of StoreDao.findAnyByName -- includes tombstones for the resurrect-on-re-add path. */
    @Query(
        """
        SELECT * FROM categories
        WHERE userId = :userId
          AND name = :name COLLATE NOCASE
        LIMIT 1
        """,
    )
    suspend fun findAnyByName(userId: String, name: String): Category?

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
        WHERE id = :id AND userId = :userId
        """,
    )
    suspend fun setArchived(userId: String, id: String, archived: Boolean, now: Long)

    @Query(
        """
        UPDATE categories
        SET deletedAt = :now, updatedAt = :now, pendingSync = 1
        WHERE id = :id AND userId = :userId
        """,
    )
    suspend fun softDelete(userId: String, id: String, now: Long)

    /** Tombstone-aware lookup. Returns the row regardless of `deletedAt`. */
    @Query("SELECT * FROM categories WHERE id = :id AND userId = :userId LIMIT 1")
    suspend fun findAnyById(userId: String, id: String): Category?

    /** Inverse of [softDelete]: clears `deletedAt`, re-flags pendingSync. */
    @Query(
        """
        UPDATE categories
        SET deletedAt = NULL, updatedAt = :now, pendingSync = 1
        WHERE id = :id AND userId = :userId
        """,
    )
    suspend fun restoreFromTombstone(userId: String, id: String, now: Long)

    @Query("SELECT * FROM categories WHERE userId = :userId AND pendingSync = 1")
    fun observePendingPush(userId: String): Flow<List<Category>>

    @Query("UPDATE categories SET pendingSync = 0 WHERE id = :id AND userId = :userId")
    suspend fun markPushed(userId: String, id: String)

    /**
     * Highest displayOrder among alive categories for this user, or null
     * when the user has none yet. Used by the add-category paths to
     * append new rows at the end of the global Manage Categories list.
     */
    @Query(
        """
        SELECT MAX(displayOrder) FROM categories
        WHERE userId = :userId AND deletedAt IS NULL
        """,
    )
    suspend fun maxDisplayOrder(userId: String): Int?

    /**
     * All categories tombstoned at exactly [deletedAt]. Used by
     * `undoSoftDeleteMany` to restore the precise batch a bulk-delete
     * tombstoned.
     */
    @Query(
        """
        SELECT * FROM categories
        WHERE userId = :userId AND deletedAt = :deletedAt
        """,
    )
    suspend fun findTombstonedAt(userId: String, deletedAt: Long): List<Category>

    /**
     * Rewrite the displayOrder of a single category. Called inside the
     * repository's `reorder` transaction once per affected row.
     */
    @Query(
        """
        UPDATE categories
        SET displayOrder = :order, updatedAt = :now, pendingSync = 1
        WHERE id = :id AND userId = :userId
        """,
    )
    suspend fun updateDisplayOrder(userId: String, id: String, order: Int, now: Long)
}
