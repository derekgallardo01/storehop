package com.storehop.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.storehop.app.data.dao.CategoryDao
import com.storehop.app.data.dao.HouseholdMemberDao
import com.storehop.app.data.dao.ItemDao
import com.storehop.app.data.dao.ItemStoreXrefDao
import com.storehop.app.data.dao.LocalOnlyMigrationDao
import com.storehop.app.data.dao.PurchaseRecordDao
import com.storehop.app.data.dao.ShoppingDao
import com.storehop.app.data.dao.StoreCategoryOrderDao
import com.storehop.app.data.dao.StoreDao
import com.storehop.app.data.db.views.AliveItemStoreXref
import com.storehop.app.data.entity.Category
import com.storehop.app.data.entity.HouseholdMember
import com.storehop.app.data.entity.Item
import com.storehop.app.data.entity.ItemStoreXref
import com.storehop.app.data.entity.PurchaseRecord
import com.storehop.app.data.entity.Store
import com.storehop.app.data.entity.StoreCategoryOrder

@Database(
    entities = [
        Item::class,
        Category::class,
        Store::class,
        ItemStoreXref::class,
        StoreCategoryOrder::class,
        PurchaseRecord::class,
        HouseholdMember::class,
    ],
    views = [AliveItemStoreXref::class],
    version = 11,
    exportSchema = true,
)
abstract class StorehopDatabase : RoomDatabase() {

    abstract fun itemDao(): ItemDao
    abstract fun categoryDao(): CategoryDao
    abstract fun storeDao(): StoreDao
    abstract fun itemStoreXrefDao(): ItemStoreXrefDao
    abstract fun storeCategoryOrderDao(): StoreCategoryOrderDao
    abstract fun shoppingDao(): ShoppingDao
    abstract fun purchaseRecordDao(): PurchaseRecordDao
    abstract fun localOnlyMigrationDao(): LocalOnlyMigrationDao
    abstract fun householdMemberDao(): HouseholdMemberDao

    companion object {
        const val NAME: String = "storehop.db"
    }
}
