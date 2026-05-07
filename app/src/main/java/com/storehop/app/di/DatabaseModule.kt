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
import com.storehop.app.data.db.DatabaseSeeder
import com.storehop.app.data.db.MIGRATION_1_2
import com.storehop.app.data.db.MIGRATION_2_3
import com.storehop.app.data.db.MIGRATION_3_4
import com.storehop.app.data.db.MIGRATION_4_5
import com.storehop.app.data.db.StorehopDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        seeder: DatabaseSeeder,
    ): StorehopDatabase = Room.databaseBuilder(
        context,
        StorehopDatabase::class.java,
        StorehopDatabase.NAME,
    )
        .addCallback(seeder)
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
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
