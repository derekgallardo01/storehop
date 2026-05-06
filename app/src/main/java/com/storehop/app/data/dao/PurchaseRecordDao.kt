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

    @Query(
        """
        SELECT * FROM purchase_records
        WHERE itemId = :itemId AND deletedAt IS NULL
        ORDER BY purchasedAt DESC
        """,
    )
    fun observeForItem(itemId: String): Flow<List<PurchaseRecord>>

    @Query(
        """
        UPDATE purchase_records
        SET deletedAt = :now, updatedAt = :now
        WHERE id = :id
        """,
    )
    suspend fun softDelete(id: String, now: Long)
}
