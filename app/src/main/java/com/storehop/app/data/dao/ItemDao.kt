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
        SET deletedAt = :now, updatedAt = :now
        WHERE id = :id
        """,
    )
    suspend fun softDelete(id: String, now: Long)

    @Query(
        """
        UPDATE items
        SET isNeeded = 0, lastPurchasedAt = :now, updatedAt = :now
        WHERE id = :id
        """,
    )
    suspend fun markPurchased(id: String, now: Long)
}
