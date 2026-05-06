package com.storehop.app.data.db.relations

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import com.storehop.app.data.entity.Category
import com.storehop.app.data.entity.Item
import com.storehop.app.data.entity.ItemStoreXref
import com.storehop.app.data.entity.Store

/**
 * Item plus its (live) Category and (live) tagged Stores.
 *
 * IMPORTANT: Room's `@Relation` (and `@Junction`) generate JOINs that do NOT
 * apply WHERE filters on the related entities, so they will happily surface
 * tombstoned Categories/Stores or follow tombstoned junction rows. The data
 * layer keeps this consistent by cascading soft-deletes:
 *  - `StoreRepositoryImpl.softDelete`    → tombstones xrefs pointing at the store
 *  - `CategoryRepositoryImpl.softDelete` → sets items.categoryId = NULL for items
 *                                          pointing at the deleted category
 *  - `ItemRepositoryImpl.softDelete`     → tombstones the item's xrefs
 *
 * As long as every tombstone is performed through the repository layer (which
 * is the only sanctioned writer of soft-deletes), this class returns only live
 * relations. A future caller writing tombstones directly via DAO would bypass
 * the cascades and surface ghost data here.
 */
data class ItemWithCategoryAndStores(
    @Embedded val item: Item,
    @Relation(parentColumn = "categoryId", entityColumn = "id")
    val category: Category?,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = ItemStoreXref::class,
            parentColumn = "itemId",
            entityColumn = "storeId",
        ),
    )
    val stores: List<Store>,
)
