package com.storehop.app.data.dao

import androidx.room.Dao
import androidx.room.Query
import com.storehop.app.data.db.relations.NeededItemAtStore
import com.storehop.app.data.db.relations.ShoppingRow
import kotlinx.coroutines.flow.Flow

@Dao
interface ShoppingDao {

    /**
     * The cross-cutting query that powers a "Shop at Store" screen.
     *
     * Includes:
     *  - every item that is currently needed AND tagged to [storeId]
     *  - every staple tagged to [storeId] regardless of need state, so the
     *    user sees it struck-through after purchase and can un-check it
     *
     * Sort order: needed items first (in this store's aisle order), then
     * purchased staples at the bottom (also in aisle order). Items in
     * categories with no `StoreCategoryOrder` for this store fall to the
     * bottom of their bucket (COALESCE to 9999). Ties on displayOrder are
     * broken deterministically by category name then item name.
     */
    @Query(
        """
        SELECT i.id            AS id,
               i.name          AS name,
               i.quantity      AS quantity,
               i.notes         AS notes,
               i.isNeeded      AS isNeeded,
               i.brand         AS brand,
               i.imageUrl      AS imageUrl,
               i.isPriority    AS isPriority,
               i.isStaple      AS isStaple,
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
          AND (i.isNeeded = 1 OR i.isStaple = 1)
          AND i.userId = :userId
        ORDER BY i.isNeeded DESC,
                 COALESCE(sco.displayOrder, 9999),
                 c.name COLLATE NOCASE,
                 i.name COLLATE NOCASE
        """,
    )
    fun shoppingListForStore(userId: String, storeId: String): Flow<List<ShoppingRow>>

    /**
     * Cross-store flat list of every item that is currently needed and tagged
     * to ANY store. The Store Picker uses this to compute per-store needed
     * counts and per-store priority badges in a single Flow rather than N+1.
     *
     * Same join shape as [shoppingListForStore] minus the per-store filters.
     */
    @Query(
        """
        SELECT isx.storeId  AS storeId,
               i.id         AS itemId,
               i.name       AS itemName,
               i.isPriority AS isPriority
        FROM items i
        INNER JOIN item_store_xref isx
               ON isx.itemId = i.id
              AND isx.userId = :userId
              AND isx.deletedAt IS NULL
        WHERE i.deletedAt IS NULL
          AND i.isNeeded = 1
          AND i.userId = :userId
        """,
    )
    fun observeNeededByStore(userId: String): Flow<List<NeededItemAtStore>>
}
