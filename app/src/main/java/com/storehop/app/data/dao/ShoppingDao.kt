package com.storehop.app.data.dao

import androidx.room.Dao
import androidx.room.Query
import com.storehop.app.data.db.relations.ShoppingRow
import kotlinx.coroutines.flow.Flow

@Dao
interface ShoppingDao {

    /**
     * The cross-cutting query that powers a "Shop at Store" screen.
     * Filters items to those needed AND tagged to [storeId], with the
     * store's per-aisle category ordering applied.
     *
     * Items in categories with no `StoreCategoryOrder` for this store
     * fall to the bottom (COALESCE to 9999). Ties on displayOrder are
     * broken deterministically by category name then item name.
     */
    @Query(
        """
        SELECT i.id            AS id,
               i.name          AS name,
               i.quantity      AS quantity,
               i.notes         AS notes,
               i.isNeeded      AS isNeeded,
               c.id            AS cat_id,
               c.name          AS cat_name,
               c.icon          AS cat_icon,
               sco.displayOrder AS displayOrder
        FROM items i
        INNER JOIN item_store_xref isx
               ON isx.itemId = i.id
              AND isx.userId = :userId
              AND isx.deletedAt IS NULL
        LEFT  JOIN categories c
               ON c.id = i.categoryId AND c.deletedAt IS NULL
        LEFT  JOIN store_category_order sco
               ON sco.storeId = :storeId
              AND sco.categoryId = i.categoryId
              AND sco.deletedAt IS NULL
        WHERE isx.storeId = :storeId
          AND i.deletedAt IS NULL
          AND i.isNeeded = 1
          AND i.userId = :userId
        ORDER BY COALESCE(sco.displayOrder, 9999),
                 c.name COLLATE NOCASE,
                 i.name COLLATE NOCASE
        """,
    )
    fun shoppingListForStore(userId: String, storeId: String): Flow<List<ShoppingRow>>
}
