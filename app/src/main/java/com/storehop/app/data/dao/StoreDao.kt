package com.storehop.app.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.storehop.app.data.entity.Store
import kotlinx.coroutines.flow.Flow

@Dao
interface StoreDao {

    @Query(
        """
        SELECT * FROM stores
        WHERE userId = :userId AND deletedAt IS NULL
          AND (:includeArchived OR isArchived = 0)
        ORDER BY displayOrder ASC, name COLLATE NOCASE
        """,
    )
    fun observeAll(userId: String, includeArchived: Boolean): Flow<List<Store>>

    @Query(
        """
        SELECT * FROM stores
        WHERE id = :id AND userId = :userId AND deletedAt IS NULL
        """,
    )
    fun observeById(userId: String, id: String): Flow<Store?>

    @Query(
        """
        SELECT * FROM stores
        WHERE id = :id AND userId = :userId AND deletedAt IS NULL
        """,
    )
    suspend fun findById(userId: String, id: String): Store?

    @Query(
        """
        SELECT * FROM stores
        WHERE userId = :userId AND deletedAt IS NULL
          AND name = :name COLLATE NOCASE
        LIMIT 1
        """,
    )
    suspend fun findByName(userId: String, name: String): Store?

    /**
     * Like [findByName] but does NOT filter out tombstones. Used by the
     * resurrect-on-re-add path: when a user soft-deletes "Lidl" and then
     * tries to add a new "Lidl", the schema-level UNIQUE(userId, name) index
     * blocks the insert (tombstones still occupy the index slot), so the
     * repository looks the tombstoned row up here and revives it instead.
     */
    @Query(
        """
        SELECT * FROM stores
        WHERE userId = :userId
          AND name = :name COLLATE NOCASE
        LIMIT 1
        """,
    )
    suspend fun findAnyByName(userId: String, name: String): Store?

    /** Tombstone-aware lookup. Returns the row regardless of `deletedAt`. */
    @Query("SELECT * FROM stores WHERE id = :id AND userId = :userId LIMIT 1")
    suspend fun findAnyById(userId: String, id: String): Store?

    /**
     * Inverse of [softDelete]: clears `deletedAt`, bumps updatedAt, and
     * re-flags pendingSync so the resurrection pushes to Firestore.
     */
    @Query(
        """
        UPDATE stores
        SET deletedAt = NULL, updatedAt = :now, pendingSync = 1
        WHERE id = :id AND userId = :userId
        """,
    )
    suspend fun restoreFromTombstone(userId: String, id: String, now: Long)

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
        WHERE id = :id AND userId = :userId
        """,
    )
    suspend fun setArchived(userId: String, id: String, archived: Boolean, now: Long)

    @Query(
        """
        UPDATE stores
        SET deletedAt = :now, updatedAt = :now, pendingSync = 1
        WHERE id = :id AND userId = :userId
        """,
    )
    suspend fun softDelete(userId: String, id: String, now: Long)

    @Query("SELECT * FROM stores WHERE userId = :userId AND pendingSync = 1")
    fun observePendingPush(userId: String): Flow<List<Store>>

    @Query("UPDATE stores SET pendingSync = 0 WHERE id = :id AND userId = :userId")
    suspend fun markPushed(userId: String, id: String)

    /**
     * Returns one past the largest live displayOrder for this user, or 0 if
     * the user has no live stores yet. Used when adding a new store so it
     * appends to the bottom of the picker -- the user can drag it where they
     * want from there.
     */
    @Query(
        """
        SELECT COALESCE(MAX(displayOrder), -1) + 1
        FROM stores
        WHERE userId = :userId AND deletedAt IS NULL
        """,
    )
    suspend fun nextDisplayOrder(userId: String): Int

    /**
     * Set [displayOrder] for one store and re-flag pendingSync so the change
     * pushes to Firestore on the next sync. Repository wraps a sequence of
     * these in a transaction to commit a full reorder atomically.
     */
    @Query(
        """
        UPDATE stores
        SET displayOrder = :displayOrder, updatedAt = :now, pendingSync = 1
        WHERE id = :id AND userId = :userId
        """,
    )
    suspend fun setDisplayOrder(userId: String, id: String, displayOrder: Int, now: Long)
}
