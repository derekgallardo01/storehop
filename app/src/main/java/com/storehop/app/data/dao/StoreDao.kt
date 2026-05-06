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
        ORDER BY name COLLATE NOCASE
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

    @Upsert
    suspend fun upsert(store: Store)

    @Query(
        """
        UPDATE stores
        SET isArchived = :archived, updatedAt = :now
        WHERE id = :id AND userId = :userId
        """,
    )
    suspend fun setArchived(userId: String, id: String, archived: Boolean, now: Long)

    @Query(
        """
        UPDATE stores
        SET deletedAt = :now, updatedAt = :now
        WHERE id = :id AND userId = :userId
        """,
    )
    suspend fun softDelete(userId: String, id: String, now: Long)
}
