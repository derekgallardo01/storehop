package com.storehop.app.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.storehop.app.data.entity.Store
import kotlinx.coroutines.flow.Flow

/**
 * v0.7.0 access scope: queries filter by `householdId` (not `userId`).
 *
 * `userId` remains on the entity as creator/audit metadata but is no
 * longer part of the access predicate. For single-member households
 * (everyone pre-Phase 3) `householdId == userId` on every row, so the
 * behavioural result is unchanged. When a user accepts an invite the
 * shared rows have `householdId = inviter.uid` and both members see
 * them via the household filter.
 */
@Dao
interface StoreDao {

    @Query(
        """
        SELECT * FROM stores
        WHERE householdId = :householdId AND deletedAt IS NULL
          AND (:includeArchived OR isArchived = 0)
        ORDER BY displayOrder ASC, name COLLATE NOCASE
        """,
    )
    fun observeAll(householdId: String, includeArchived: Boolean): Flow<List<Store>>

    @Query(
        """
        SELECT * FROM stores
        WHERE id = :id AND householdId = :householdId AND deletedAt IS NULL
        """,
    )
    fun observeById(householdId: String, id: String): Flow<Store?>

    @Query(
        """
        SELECT * FROM stores
        WHERE id = :id AND householdId = :householdId AND deletedAt IS NULL
        """,
    )
    suspend fun findById(householdId: String, id: String): Store?

    @Query(
        """
        SELECT * FROM stores
        WHERE householdId = :householdId AND deletedAt IS NULL
          AND name = :name COLLATE NOCASE
        LIMIT 1
        """,
    )
    suspend fun findByName(householdId: String, name: String): Store?

    /**
     * Like [findByName] but does NOT filter out tombstones. Used by the
     * resurrect-on-re-add path: when a user soft-deletes "Lidl" and then
     * tries to add a new "Lidl", the repository looks the tombstoned row
     * up here and revives it instead. Searches within the active
     * household so users sharing a household can resurrect each other's
     * tombstones.
     */
    @Query(
        """
        SELECT * FROM stores
        WHERE householdId = :householdId
          AND name = :name COLLATE NOCASE
        LIMIT 1
        """,
    )
    suspend fun findAnyByName(householdId: String, name: String): Store?

    /** Tombstone-aware lookup. Returns the row regardless of `deletedAt`. */
    @Query("SELECT * FROM stores WHERE id = :id AND householdId = :householdId LIMIT 1")
    suspend fun findAnyById(householdId: String, id: String): Store?

    /**
     * Inverse of [softDelete]: clears `deletedAt`, bumps updatedAt, and
     * re-flags pendingSync so the resurrection pushes to Firestore.
     */
    @Query(
        """
        UPDATE stores
        SET deletedAt = NULL, updatedAt = :now, pendingSync = 1
        WHERE id = :id AND householdId = :householdId
        """,
    )
    suspend fun restoreFromTombstone(householdId: String, id: String, now: Long)

    @Upsert
    suspend fun upsert(store: Store)

    /**
     * Batch upsert for the pull side. Mappers stamp `pendingSync = false`.
     * Called inside [PullWriteDao]'s single transaction.
     */
    @Upsert
    suspend fun upsertFromCloud(rows: List<Store>)

    @Query(
        """
        UPDATE stores
        SET isArchived = :archived, updatedAt = :now, pendingSync = 1
        WHERE id = :id AND householdId = :householdId
        """,
    )
    suspend fun setArchived(householdId: String, id: String, archived: Boolean, now: Long)

    @Query(
        """
        UPDATE stores
        SET deletedAt = :now, updatedAt = :now, pendingSync = 1
        WHERE id = :id AND householdId = :householdId
        """,
    )
    suspend fun softDelete(householdId: String, id: String, now: Long)

    @Query("SELECT * FROM stores WHERE householdId = :householdId AND pendingSync = 1")
    fun observePendingPush(householdId: String): Flow<List<Store>>

    /** v0.7.1: row-count of pending pushes for the Force-sync-now UX. */
    @Query("SELECT COUNT(*) FROM stores WHERE householdId = :householdId AND pendingSync = 1")
    fun countPendingPush(householdId: String): Flow<Int>

    /** v0.8.0.4: pendingSync-1 primary-key snapshot for the pull-guard. */
    @Query("SELECT id FROM stores WHERE householdId = :householdId AND pendingSync = 1")
    suspend fun pendingPushIds(householdId: String): List<String>

    @Query("UPDATE stores SET pendingSync = 0 WHERE id = :id AND householdId = :householdId")
    suspend fun markPushed(householdId: String, id: String)

    /**
     * Returns one past the largest live displayOrder for the household, or 0
     * if the household has no live stores yet. Used when adding a new store
     * so it appends to the bottom of the picker -- the user can drag it
     * where they want from there.
     */
    @Query(
        """
        SELECT COALESCE(MAX(displayOrder), -1) + 1
        FROM stores
        WHERE householdId = :householdId AND deletedAt IS NULL
        """,
    )
    suspend fun nextDisplayOrder(householdId: String): Int

    /**
     * Set [displayOrder] for one store and re-flag pendingSync so the change
     * pushes to Firestore on the next sync. Repository wraps a sequence of
     * these in a transaction to commit a full reorder atomically.
     */
    @Query(
        """
        UPDATE stores
        SET displayOrder = :displayOrder, updatedAt = :now, pendingSync = 1
        WHERE id = :id AND householdId = :householdId
        """,
    )
    suspend fun setDisplayOrder(householdId: String, id: String, displayOrder: Int, now: Long)
}
