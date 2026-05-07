package com.storehop.app.data.dao

import androidx.room.Dao
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.storehop.app.data.db.relations.ItemWithCategoryAndStores
import com.storehop.app.data.entity.Item
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemDao {

    @Transaction
    @Query(
        """
        SELECT * FROM items
        WHERE userId = :userId AND deletedAt IS NULL
        ORDER BY name COLLATE NOCASE
        """,
    )
    fun observeAll(userId: String): Flow<List<ItemWithCategoryAndStores>>

    @Query(
        """
        SELECT * FROM items
        WHERE userId = :userId AND deletedAt IS NULL AND isNeeded = 1
        ORDER BY name COLLATE NOCASE
        """,
    )
    fun observeNeeded(userId: String): Flow<List<Item>>

    @Transaction
    @Query(
        """
        SELECT * FROM items
        WHERE id = :id AND userId = :userId AND deletedAt IS NULL
        """,
    )
    fun observeById(userId: String, id: String): Flow<ItemWithCategoryAndStores?>

    @Upsert
    suspend fun upsert(item: Item)

    @Query(
        """
        UPDATE items
        SET deletedAt = :now, updatedAt = :now, pendingSync = 1
        WHERE id = :id AND userId = :userId
        """,
    )
    suspend fun softDelete(userId: String, id: String, now: Long)

    /** Tombstone-aware lookup for the undo path. */
    @Query("SELECT * FROM items WHERE id = :id AND userId = :userId LIMIT 1")
    suspend fun findAnyById(userId: String, id: String): Item?

    /** Inverse of [softDelete]: clears `deletedAt`, re-flags pendingSync. */
    @Query(
        """
        UPDATE items
        SET deletedAt = NULL, updatedAt = :now, pendingSync = 1
        WHERE id = :id AND userId = :userId
        """,
    )
    suspend fun restoreFromTombstone(userId: String, id: String, now: Long)

    @Query(
        """
        UPDATE items
        SET isNeeded = 0, lastPurchasedAt = :now, updatedAt = :now, pendingSync = 1
        WHERE id = :id AND userId = :userId
        """,
    )
    suspend fun markPurchased(userId: String, id: String, now: Long)

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
        WHERE id = :id AND userId = :userId
        """,
    )
    suspend fun markNeeded(userId: String, id: String, now: Long)

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
        WHERE categoryId = :categoryId AND userId = :userId AND deletedAt IS NULL
        """,
    )
    suspend fun clearCategoryReferences(userId: String, categoryId: String, now: Long)

    @Query("SELECT * FROM items WHERE userId = :userId AND pendingSync = 1")
    fun observePendingPush(userId: String): Flow<List<Item>>

    @Query("UPDATE items SET pendingSync = 0 WHERE id = :id AND userId = :userId")
    suspend fun markPushed(userId: String, id: String)
}
