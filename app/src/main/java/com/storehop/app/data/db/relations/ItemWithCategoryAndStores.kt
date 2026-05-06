package com.storehop.app.data.db.relations

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import com.storehop.app.data.entity.Category
import com.storehop.app.data.entity.Item
import com.storehop.app.data.entity.ItemStoreXref
import com.storehop.app.data.entity.Store

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
