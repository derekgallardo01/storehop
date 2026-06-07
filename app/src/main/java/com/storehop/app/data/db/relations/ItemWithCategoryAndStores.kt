package com.storehop.app.data.db.relations

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import com.storehop.app.data.db.views.AliveItemStoreXref
import com.storehop.app.data.entity.Category
import com.storehop.app.data.entity.Item
import com.storehop.app.data.entity.Store

/**
 * Item plus its (live) Category and (live) tagged Stores.
 *
 * Room's `@Relation` (and `@Junction`) generate JOINs that do NOT apply
 * `WHERE` filters on the related entities or the bridging table. The data
 * layer keeps Category/Store live by cascading soft-deletes:
 *  - `StoreRepositoryImpl.softDelete`    → tombstones xrefs pointing at the store
 *  - `CategoryRepositoryImpl.softDelete` → sets items.categoryId = NULL for items
 *                                          pointing at the deleted category
 *  - `ItemRepositoryImpl.softDelete`     → tombstones the item's xrefs
 *
 * v0.8.1: the `@Junction` reads through the [AliveItemStoreXref] view
 * (`SELECT itemId, storeId FROM item_store_xref WHERE deletedAt IS NULL`)
 * instead of the raw `item_store_xref` table, so tombstoned bridge rows
 * no longer leak ghost stores into `stores`. This replaces the v0.8.0.5
 * tactical hack on the form (which had to special-read via
 * `ItemRepository.aliveStoreIdsForItem`) and fixes the leak at every
 * consumer in one place (form chips, CSV export, Items list +/− toggle).
 */
data class ItemWithCategoryAndStores(
    @Embedded val item: Item,
    @Relation(parentColumn = "categoryId", entityColumn = "id")
    val category: Category?,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = AliveItemStoreXref::class,
            parentColumn = "itemId",
            entityColumn = "storeId",
        ),
    )
    val stores: List<Store>,
)
