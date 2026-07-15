package com.storehop.app.di

import android.content.Context
import androidx.room.Room
import com.storehop.app.data.dao.CategoryDao
import com.storehop.app.data.dao.HouseholdMemberDao
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
import com.storehop.app.data.db.MIGRATION_5_6
import com.storehop.app.data.db.MIGRATION_6_7
import com.storehop.app.data.db.MIGRATION_7_8
import com.storehop.app.data.db.MIGRATION_8_9
import com.storehop.app.data.db.MIGRATION_9_10
import com.storehop.app.data.db.MIGRATION_10_11
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
        .addMigrations(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
            MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
            MIGRATION_10_11,
        )
        // v0.7.0: safety net for the downgrade direction. If a user
        // installs a hypothetical v0.7.x / v0.8.x and then reverts to
        // a build with this Room version, the on-disk schema is newer
        // than what this code expects. Without this flag, Room throws
        // IllegalStateException and the app crashes on every launch.
        // With it, Room wipes the local DB and re-creates a fresh v8
        // schema on first launch — the user loses local-only edits
        // (`pendingSync = 1` rows) but the next sync pull restores
        // every Firestore-backed row. Better than a hard crash.
        //
        // Note: this does NOT help the v0.7.0 → v0.6.9 downgrade
        // path — that requires the v0.6.9 build to have this flag,
        // which it doesn't. For that case, uninstall + reinstall
        // is the documented workaround (see CHANGELOG).
        .fallbackToDestructiveMigrationOnDowngrade()
        .build()

    @Provides fun provideItemDao(db: StorehopDatabase): ItemDao = db.itemDao()
    @Provides fun provideCategoryDao(db: StorehopDatabase): CategoryDao = db.categoryDao()
    @Provides fun provideStoreDao(db: StorehopDatabase): StoreDao = db.storeDao()
    @Provides fun provideItemStoreXrefDao(db: StorehopDatabase): ItemStoreXrefDao = db.itemStoreXrefDao()
    @Provides fun provideStoreCategoryOrderDao(db: StorehopDatabase): StoreCategoryOrderDao = db.storeCategoryOrderDao()
    @Provides fun provideShoppingDao(db: StorehopDatabase): ShoppingDao = db.shoppingDao()
    @Provides fun providePurchaseRecordDao(db: StorehopDatabase): PurchaseRecordDao = db.purchaseRecordDao()
    @Provides fun provideLocalOnlyMigrationDao(db: StorehopDatabase): LocalOnlyMigrationDao = db.localOnlyMigrationDao()
    @Provides fun provideHouseholdMemberDao(db: StorehopDatabase): HouseholdMemberDao = db.householdMemberDao()
}
