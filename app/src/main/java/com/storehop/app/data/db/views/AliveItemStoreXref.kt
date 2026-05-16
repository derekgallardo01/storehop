package com.storehop.app.data.db.views

import androidx.room.DatabaseView

/**
 * Tombstone-filtered view over `item_store_xref`. Exposes only the
 * columns needed for [androidx.room.Junction] joins: `itemId` and
 * `storeId`.
 *
 * v0.8.1: replaces the v0.8.0.5 tactical hack
 * (`ItemRepository.aliveStoreIdsForItem` + a special read in
 * `ItemFormViewModel.init`). Room's `@Relation` + `@Junction` doesn't
 * apply a `WHERE` to the bridging table, so pointing the junction at
 * the raw `item_store_xref` surfaced soft-deleted rows through the
 * join. Pointing it at this view instead means every consumer of
 * `ItemWithCategoryAndStores.stores` is automatically tombstone-aware
 * (form's chips, CSV export's per-store column, Items list's
 * `hasStores` toggle) -- one fix at the data layer instead of three
 * fixes at every read site.
 *
 * The view is registered in [com.storehop.app.data.db.StorehopDatabase];
 * schema migration v8 -> v9 creates it via raw `CREATE VIEW` SQL so
 * existing installs pick it up on first open.
 */
@DatabaseView(
    viewName = "alive_item_store_xref",
    value = "SELECT itemId, storeId FROM item_store_xref WHERE deletedAt IS NULL",
)
data class AliveItemStoreXref(
    val itemId: String,
    val storeId: String,
)
