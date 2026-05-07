package com.storehop.app.data.dao

import androidx.room.Dao
import androidx.room.Query
import com.storehop.app.data.db.relations.ShoppingRow
import com.storehop.app.data.db.relations.StorePickerItemRow
import kotlinx.coroutines.flow.Flow

@Dao
interface ShoppingDao {

    /**
     * The cross-cutting query that powers a "Shop at Store" screen.
     *
     * Includes:
     *  - every item that is currently needed AND tagged to [storeId]
     *  - every staple tagged to [storeId] regardless of need state -- staples
     *    are "always on the list," so a purchased staple stays struck-through
     *  - every item the user has marked purchased *within the current session*
     *    (lastPurchasedAt >= [sessionStartMs]). This keeps a tapped non-staple
     *    visible struck-through while the user is still shopping at this
     *    store, but drops it from the list on the next visit -- re-opening
     *    the screen creates a new ViewModel with a fresh `sessionStartMs`,
     *    and previously purchased non-staples fall outside the window.
     *
     * Sort order: needed items first (in this store's aisle order), then
     * purchased rows at the bottom (also in aisle order). Items in
     * categories with no `StoreCategoryOrder` for this store fall to the
     * bottom of their bucket (COALESCE to 9999). Ties on displayOrder are
     * broken deterministically by category name then item name.
     *
     * @param sessionStartMs millis since epoch marking when this Shop-at-Store
     *   ViewModel was constructed. Pass [Long.MAX_VALUE] to disable the
     *   session window (only needed/staple items appear) -- useful in tests.
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
          AND (
                i.isNeeded = 1
             OR i.isStaple = 1
             OR (i.lastPurchasedAt IS NOT NULL AND i.lastPurchasedAt >= :sessionStartMs)
          )
          AND i.userId = :userId
        ORDER BY i.isNeeded DESC,
                 COALESCE(sco.displayOrder, 9999),
                 c.name COLLATE NOCASE,
                 i.name COLLATE NOCASE
        """,
    )
    fun shoppingListForStore(
        userId: String,
        storeId: String,
        sessionStartMs: Long,
    ): Flow<List<ShoppingRow>>

    /**
     * Cross-store flat list of every item that's currently relevant to a
     * Store Picker badge: either still needed somewhere, OR purchased within
     * the active shopping session (`lastPurchasedAt >= :sessionStartMs`).
     * Lets the picker show "✓ All set" on a store where every needed item
     * has been picked up this trip rather than the bland "Nothing needed,"
     * AND keeps cross-store sync visible: an item bought at Lidl shows as
     * picked-up on Aldi too if it was tagged at both.
     *
     * Same join shape as [shoppingListForStore] minus the per-store filter
     * (we want every store the item touches). Includes `isNeeded` so the
     * caller can split each store's rows into "still needed" vs
     * "picked up" buckets without re-querying.
     */
    @Query(
        """
        SELECT isx.storeId  AS storeId,
               i.id         AS itemId,
               i.name       AS itemName,
               i.isPriority AS isPriority,
               i.isNeeded   AS isNeeded
        FROM items i
        INNER JOIN item_store_xref isx
               ON isx.itemId = i.id
              AND isx.userId = :userId
              AND isx.deletedAt IS NULL
        WHERE i.deletedAt IS NULL
          AND i.userId = :userId
          AND (
                i.isNeeded = 1
             OR (i.lastPurchasedAt IS NOT NULL AND i.lastPurchasedAt >= :sessionStartMs)
          )
        """,
    )
    fun observeStorePickerItems(
        userId: String,
        sessionStartMs: Long,
    ): Flow<List<StorePickerItemRow>>
}
