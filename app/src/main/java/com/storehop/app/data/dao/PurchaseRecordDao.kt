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

    /**
     * Soft-delete the live PurchaseRecord(s) for [itemId] whose `purchasedAt`
     * matches exactly [purchasedAt]. Used by the snackbar-undo path after a
     * cascade purchase, to roll back history alongside the xref restore.
     * Filtered by user and live-only so concurrent records or already-tombstoned
     * ones aren't disturbed.
     */
    @Query(
        """
        UPDATE purchase_records
        SET deletedAt = :now, updatedAt = :now, pendingSync = 1
        WHERE itemId = :itemId AND userId = :userId
            AND purchasedAt = :purchasedAt
            AND deletedAt IS NULL
        """,
    )
    suspend fun softDeleteForItemAtTime(
        userId: String,
        itemId: String,
        purchasedAt: Long,
        now: Long,
    )

    @Query("SELECT * FROM purchase_records WHERE userId = :userId AND pendingSync = 1")
    fun observePendingPush(userId: String): Flow<List<PurchaseRecord>>

    @Query("UPDATE purchase_records SET pendingSync = 0 WHERE id = :id AND userId = :userId")
    suspend fun markPushed(userId: String, id: String)

    // ---- Statistics aggregates ------------------------------------------------
    // Read-only flows that power the Settings → Statistics screen. All filter
    // by userId + deletedAt IS NULL so tombstoned records and other users'
    // history never leak into the visible totals.

    @Query(
        """
        SELECT COUNT(*) FROM purchase_records
        WHERE userId = :userId AND deletedAt IS NULL
        """,
    )
    fun observeTotalCount(userId: String): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM purchase_records
        WHERE userId = :userId AND deletedAt IS NULL AND purchasedAt >= :sinceMillis
        """,
    )
    fun observeCountSince(userId: String, sinceMillis: Long): Flow<Int>

    @Query(
        """
        SELECT date(purchasedAt / 1000, 'unixepoch', 'localtime') AS day,
               COUNT(*) AS count
        FROM purchase_records
        WHERE userId = :userId AND deletedAt IS NULL AND purchasedAt >= :sinceMillis
        GROUP BY day
        ORDER BY day ASC
        """,
    )
    fun observePurchasesPerDay(userId: String, sinceMillis: Long): Flow<List<DayCount>>

    @Query(
        """
        SELECT CAST(strftime('%w', purchasedAt / 1000, 'unixepoch', 'localtime') AS INTEGER) AS dayOfWeek,
               COUNT(*) AS count
        FROM purchase_records
        WHERE userId = :userId AND deletedAt IS NULL
        GROUP BY dayOfWeek
        ORDER BY count DESC
        """,
    )
    fun observePurchasesByDayOfWeek(userId: String): Flow<List<DayOfWeekCount>>

    @Query(
        """
        SELECT itemId, COUNT(*) AS count
        FROM purchase_records
        WHERE userId = :userId AND deletedAt IS NULL
        GROUP BY itemId
        ORDER BY count DESC
        LIMIT :limit
        """,
    )
    fun observeTopItems(userId: String, limit: Int): Flow<List<ItemPurchaseCount>>

    @Query(
        """
        SELECT storeId, COUNT(*) AS count
        FROM purchase_records
        WHERE userId = :userId AND deletedAt IS NULL AND storeId IS NOT NULL
        GROUP BY storeId
        ORDER BY count DESC
        """,
    )
    fun observePurchasesByStore(userId: String): Flow<List<StorePurchaseCount>>

    /**
     * Group purchase records by the category of the item that was bought.
     * Items whose categoryId is NULL (uncategorised) are reported as the
     * empty string so the UI can show an "Uncategorised" bucket without
     * losing them from the total.
     */
    @Query(
        """
        SELECT COALESCE(items.categoryId, '') AS categoryId, COUNT(*) AS count
        FROM purchase_records
        INNER JOIN items ON items.id = purchase_records.itemId
        WHERE purchase_records.userId = :userId
            AND purchase_records.deletedAt IS NULL
            AND items.deletedAt IS NULL
        GROUP BY categoryId
        ORDER BY count DESC
        """,
    )
    fun observePurchasesByCategory(userId: String): Flow<List<CategoryPurchaseCount>>
}

/** Daily purchase count, projection of [observePurchasesPerDay]. */
data class DayCount(
    /** ISO date (YYYY-MM-DD) in the device's local timezone. */
    val day: String,
    val count: Int,
)

/** Day-of-week purchase count where dayOfWeek is 0 (Sunday) – 6 (Saturday). */
data class DayOfWeekCount(
    val dayOfWeek: Int,
    val count: Int,
)

/** Per-item aggregate, projection of [observeTopItems]. */
data class ItemPurchaseCount(
    val itemId: String,
    val count: Int,
)

/** Per-store aggregate, projection of [observePurchasesByStore]. */
data class StorePurchaseCount(
    val storeId: String,
    val count: Int,
)

/** Per-category aggregate; empty `categoryId` means uncategorised. */
data class CategoryPurchaseCount(
    val categoryId: String,
    val count: Int,
)
