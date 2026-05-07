package com.storehop.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.storehop.app.data.entity.PurchaseRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface PurchaseRecordDao {

    // ABORT (not REPLACE): UUIDs don't collide in normal use, so a duplicate
    // PK indicates a real bug — surface it instead of silently overwriting.
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: PurchaseRecord)

    /**
     * Batch upsert for the pull side. Pull uses Upsert (not Insert) because
     * the same record can legitimately arrive twice -- once from a previous
     * push, once from a re-pull on a new device. Mappers stamp
     * `pendingSync = false`. Called inside [PullWriteDao]'s single transaction.
     */
    @androidx.room.Upsert
    suspend fun upsertFromCloud(rows: List<PurchaseRecord>)

    @Query(
        """
        SELECT * FROM purchase_records
        WHERE itemId = :itemId AND userId = :userId AND deletedAt IS NULL
        ORDER BY purchasedAt DESC
        """,
    )
    fun observeForItem(userId: String, itemId: String): Flow<List<PurchaseRecord>>

    @Query(
        """
        UPDATE purchase_records
        SET deletedAt = :now, updatedAt = :now, pendingSync = 1
        WHERE id = :id AND userId = :userId
        """,
    )
    suspend fun softDelete(userId: String, id: String, now: Long)

    /**
     * Cascade-tombstone all purchase records for an item. Used by the item soft-delete
     * flow so a deleted item doesn't leave purchase-history orphans visible to
     * `observeForItem`. Bound by `userId` so a buggy caller cannot cascade across users.
     */
    @Query(
        """
        UPDATE purchase_records
        SET deletedAt = :now, updatedAt = :now, pendingSync = 1
        WHERE itemId = :itemId AND userId = :userId AND deletedAt IS NULL
        """,
    )
    suspend fun softDeleteForItem(userId: String, itemId: String, now: Long)

    /** Inverse of [softDeleteForItem], filtered by exact `deletedAt`. */
    @Query(
        """
        UPDATE purchase_records
        SET deletedAt = NULL, updatedAt = :now, pendingSync = 1
        WHERE itemId = :itemId AND userId = :userId AND deletedAt = :deletedAt
        """,
    )
    suspend fun restoreCascadeForItem(userId: String, itemId: String, deletedAt: Long, now: Long)

    @Query("SELECT * FROM purchase_records WHERE userId = :userId AND pendingSync = 1")
    fun observePendingPush(userId: String): Flow<List<PurchaseRecord>>

    @Query("UPDATE purchase_records SET pendingSync = 0 WHERE id = :id AND userId = :userId")
    suspend fun markPushed(userId: String, id: String)
}
