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
     * Need state is now per-(item, store) -- it lives on the xref row, not
     * the item. So checking off milk at Lidl flips `isx(Lidl, milk).isNeeded`
     * but leaves `isx(Aldi, milk).isNeeded` untouched: the Aldi screen still
     * sees milk as needed, the Lidl screen sees it as struck-through.
     *
     * Includes (for this store specifically):
     *  - xrefs with `isNeeded = 1` (still on the list at this store)
     *  - any xref whose item is a staple (always-on-the-list, struck through
     *    after purchase here so the user can un-check)
     *  - any xref the user has marked purchased *within the current session*
     *    at this store (lastPurchasedAt >= [sessionStartMs]). This keeps a
     *    tapped non-staple visible struck-through while shopping, but drops
     *    it on the next visit -- a new ViewModel anchors a fresh window and
     *    previously purchased non-staples fall outside it.
     *
     * Sort order: needed rows first (in this store's aisle order), then
     * purchased rows at the bottom. Items in categories with no
     * `StoreCategoryOrder` for this store fall to the bottom of their
     * bucket (COALESCE to 9999). Ties broken by category then item name.
     *
     * @param sessionStartMs millis since epoch marking when this Shop-at-Store
     *   ViewModel was constructed. Pass [Long.MAX_VALUE] to disable the
     *   session window (only needed/staple rows appear) -- useful in tests.
     */
    @Query(
        """
        SELECT i.id            AS id,
               i.name          AS name,
               i.quantity      AS quantity,
               i.notes         AS notes,
               isx.isNeeded    AS isNeeded,
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
                isx.isNeeded = 1
             OR i.isStaple = 1
             OR (isx.lastPurchasedAt IS NOT NULL AND isx.lastPurchasedAt >= :sessionStartMs)
          )
          AND i.userId = :userId
        ORDER BY isx.isNeeded DESC,
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
     * Cross-store flat list of every (item, store) pair that's currently
     * relevant to a Store Picker badge: either still needed at that store,
     * OR marked purchased there within the active shopping session
     * (`isx.lastPurchasedAt >= :sessionStartMs`). Lets the picker show
     * "✓ All set" on a store where every needed item has been picked up
     * this trip rather than the bland "Nothing needed."
     *
     * Per-store: each xref's `isNeeded` is independent, so milk bought at
     * Lidl drops Lidl's count to 0 but Aldi's row keeps `isNeeded = 1`
     * (Aldi still says "1 item needed").
     *
     * Same join shape as [shoppingListForStore] minus the per-store filter
     * (we want every store the item touches). Includes the per-store
     * `isNeeded` so the caller can split each store's rows into "still
     * needed" vs "picked up" buckets without re-querying.
     */
    @Query(
        """
        SELECT isx.storeId  AS storeId,
               i.id         AS itemId,
               i.name       AS itemName,
               i.isPriority AS isPriority,
               isx.isNeeded AS isNeeded
        FROM items i
        INNER JOIN item_store_xref isx
               ON isx.itemId = i.id
              AND isx.userId = :userId
              AND isx.deletedAt IS NULL
        WHERE i.deletedAt IS NULL
          AND i.userId = :userId
          AND (
                isx.isNeeded = 1
             OR (isx.lastPurchasedAt IS NOT NULL AND isx.lastPurchasedAt >= :sessionStartMs)
          )
        """,
    )
    fun observeStorePickerItems(
        userId: String,
        sessionStartMs: Long,
    ): Flow<List<StorePickerItemRow>>
}
