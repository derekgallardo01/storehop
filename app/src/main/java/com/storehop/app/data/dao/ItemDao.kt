package com.storehop.app.data.dao

import androidx.room.Dao
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.storehop.app.data.db.relations.ItemWithCategoryAndStores
import com.storehop.app.data.entity.Item
import kotlinx.coroutines.flow.Flow

/**
 * v0.7.0 access scope: queries filter by `householdId` (not `userId`).
 * See [StoreDao] for the rationale.
 */
@Dao
interface ItemDao {

    @Transaction
    @Query(
        """
        SELECT * FROM items
        WHERE householdId = :householdId AND deletedAt IS NULL
        ORDER BY name COLLATE NOCASE
        """,
    )
    fun observeAll(householdId: String): Flow<List<ItemWithCategoryAndStores>>

    @Query(
        """
        SELECT * FROM items
        WHERE householdId = :householdId AND deletedAt IS NULL AND isNeeded = 1
        ORDER BY name COLLATE NOCASE
        """,
    )
    fun observeNeeded(householdId: String): Flow<List<Item>>

    @Transaction
    @Query(
        """
        SELECT * FROM items
        WHERE id = :id AND householdId = :householdId AND deletedAt IS NULL
        """,
    )
    fun observeById(householdId: String, id: String): Flow<ItemWithCategoryAndStores?>

    @Upsert
    suspend fun upsert(item: Item)

    /**
     * Batch upsert for the pull side. Mappers stamp `pendingSync = false` so
     * pulled rows don't immediately re-push. Called inside [PullWriteDao]'s
     * single transaction.
     */
    @Upsert
    suspend fun upsertFromCloud(rows: List<Item>)

    @Query(
        """
        UPDATE items
        SET deletedAt = :now, updatedAt = :now, pendingSync = 1
        WHERE id = :id AND householdId = :householdId
        """,
    )
    suspend fun softDelete(householdId: String, id: String, now: Long)

    /** Tombstone-aware lookup for the undo path. */
    @Query("SELECT * FROM items WHERE id = :id AND householdId = :householdId LIMIT 1")
    suspend fun findAnyById(householdId: String, id: String): Item?

    /**
     * Live, case-insensitive name lookup. Used by the CSV import duplicate
     * guard: if an alive item with the same name already exists, the import
     * skips that row to preserve the user's existing data per the
     * "don't erase anything" hard constraint. Items aren't UNIQUE on name
     * in the schema (you can have a "Milk" from Lidl and another "Milk"
     * from Mimosa), so this is a soft dedup just for import.
     */
    @Query(
        """
        SELECT * FROM items
        WHERE householdId = :householdId
          AND name = :name COLLATE NOCASE
          AND deletedAt IS NULL
        LIMIT 1
        """,
    )
    suspend fun findByName(householdId: String, name: String): Item?

    /** Inverse of [softDelete]: clears `deletedAt`, re-flags pendingSync. */
    @Query(
        """
        UPDATE items
        SET deletedAt = NULL, updatedAt = :now, pendingSync = 1
        WHERE id = :id AND householdId = :householdId
        """,
    )
    suspend fun restoreFromTombstone(householdId: String, id: String, now: Long)

    @Query(
        """
        UPDATE items
        SET isNeeded = 0, lastPurchasedAt = :now, updatedAt = :now, pendingSync = 1
        WHERE id = :id AND householdId = :householdId
        """,
    )
    suspend fun markPurchased(householdId: String, id: String, now: Long)

    /**
     * Restore an item to the "needed" list. Used by the Shop-at-Store screen
     * when the user taps a struck-through purchased staple to put it back on
     * the list (un-checks it). Does not touch `lastPurchasedAt` -- the prior
     * purchase still happened.
     */
    @Query(
        """
        UPDATE items
        SET isNeeded = 1, updatedAt = :now, pendingSync = 1
        WHERE id = :id AND householdId = :householdId
        """,
    )
    suspend fun markNeeded(householdId: String, id: String, now: Long)

    /**
     * Clear `categoryId` on every live item that points at a category that's
     * being soft-deleted. Used by the category cascade — without this, items
     * would still resolve their @Relation join to the (tombstoned) category
     * row. Setting categoryId = NULL makes the LEFT JOIN return null cleanly.
     */
    @Query(
        """
        UPDATE items
        SET categoryId = NULL, updatedAt = :now, pendingSync = 1
        WHERE categoryId = :categoryId AND householdId = :householdId AND deletedAt IS NULL
        """,
    )
    suspend fun clearCategoryReferences(householdId: String, categoryId: String, now: Long)

    /**
     * Inverse of [clearCategoryReferences]: re-link items to their previous
     * category when the user undoes a category soft-delete. Identifies
     * affected items by `updatedAt = :clearedAt AND categoryId IS NULL`,
     * which is the unique stamp the cascade-clear left behind. Not a hard
     * guarantee (an unrelated item update at the exact same ms could match)
     * but the undo window is seconds, so the collision risk is tiny.
     */
    @Query(
        """
        UPDATE items
        SET categoryId = :categoryId, updatedAt = :now, pendingSync = 1
        WHERE householdId = :householdId AND categoryId IS NULL
          AND updatedAt = :clearedAt AND deletedAt IS NULL
        """,
    )
    suspend fun restoreCategoryReferences(householdId: String, categoryId: String, clearedAt: Long, now: Long)

    @Query("SELECT * FROM items WHERE householdId = :householdId AND pendingSync = 1")
    fun observePendingPush(householdId: String): Flow<List<Item>>

    /** v0.7.1: row-count of pending pushes for the Force-sync-now UX. */
    @Query("SELECT COUNT(*) FROM items WHERE householdId = :householdId AND pendingSync = 1")
    fun countPendingPush(householdId: String): Flow<Int>

    /**
     * v0.8.0.4: snapshot of primary keys for rows with pendingSync = 1
     * in this household. Read by [PullWriteDao.replaceAllForUid] to
     * filter cloud rows that would otherwise resurrect local edits
     * waiting to be pushed. See Mike's write-revert bug for the
     * canonical case.
     */
    @Query("SELECT id FROM items WHERE householdId = :householdId AND pendingSync = 1")
    suspend fun pendingPushIds(householdId: String): List<String>

    @Query("UPDATE items SET pendingSync = 0 WHERE id = :id AND householdId = :householdId")
    suspend fun markPushed(householdId: String, id: String)
}
