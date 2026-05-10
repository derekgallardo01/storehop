package com.storehop.app.di

import android.content.Context
import androidx.room.Room
import com.storehop.app.data.dao.CategoryDao
import com.storehop.app.data.dao.ItemDao
import com.storehop.app.data.dao.ItemStoreXrefDao
import com.storehop.app.data.dao.LocalOnlyMigrationDao
import com.storehop.app.data.dao.PurchaseRecordDao
import com.storehop.app.data.dao.ShoppingDao
import com.storehop.app.data.dao.StoreCategoryOrderDao
import com.storehop.app.data.dao.StoreDao
import com.storehop.app.data.db.StorehopDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/**
 * Replaces [DatabaseModule] in instrumented tests with an in-memory Room
 * DB. Each test gets a fresh DB (in-memory + `allowMainThreadQueries`
 * because some setup paths bypass the executor). The seeded onCreate
 * callback is intentionally NOT installed -- tests seed their own data.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [DatabaseModule::class],
)
object TestDatabaseModule {

    @Provides
    @Singleton
    fun provideTestDatabase(@ApplicationContext context: Context): StorehopDatabase =
        Room.inMemoryDatabaseBuilder(context, StorehopDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    @Provides fun provideItemDao(db: StorehopDatabase): ItemDao = db.itemDao()
    @Provides fun provideCategoryDao(db: StorehopDatabase): CategoryDao = db.categoryDao()
    @Provides fun provideStoreDao(db: StorehopDatabase): StoreDao = db.storeDao()
    @Provides fun provideItemStoreXrefDao(db: StorehopDatabase): ItemStoreXrefDao = db.itemStoreXrefDao()
    @Provides fun provideStoreCategoryOrderDao(db: StorehopDatabase): StoreCategoryOrderDao = db.storeCategoryOrderDao()
    @Provides fun provideShoppingDao(db: StorehopDatabase): ShoppingDao = db.shoppingDao()
    @Provides fun providePurchaseRecordDao(db: StorehopDatabase): PurchaseRecordDao = db.purchaseRecordDao()
    @Provides fun provideLocalOnlyMigrationDao(db: StorehopDatabase): LocalOnlyMigrationDao = db.localOnlyMigrationDao()
}
