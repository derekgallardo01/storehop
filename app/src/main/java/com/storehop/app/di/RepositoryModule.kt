package com.storehop.app.di

import com.storehop.app.data.repository.CategoryRepository
import com.storehop.app.data.repository.CategoryRepositoryImpl
import com.storehop.app.data.repository.ItemRepository
import com.storehop.app.data.repository.ItemRepositoryImpl
import com.storehop.app.data.repository.PurchaseHistoryRepository
import com.storehop.app.data.repository.PurchaseHistoryRepositoryImpl
import com.storehop.app.data.repository.ShoppingRepository
import com.storehop.app.data.repository.ShoppingRepositoryImpl
import com.storehop.app.data.repository.StoreCategoryOrderRepository
import com.storehop.app.data.repository.StoreCategoryOrderRepositoryImpl
import com.storehop.app.data.repository.StoreRepository
import com.storehop.app.data.repository.StoreRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds @Singleton
    abstract fun bindItemRepository(impl: ItemRepositoryImpl): ItemRepository

    @Binds @Singleton
    abstract fun bindStoreRepository(impl: StoreRepositoryImpl): StoreRepository

    @Binds @Singleton
    abstract fun bindCategoryRepository(impl: CategoryRepositoryImpl): CategoryRepository

    @Binds @Singleton
    abstract fun bindShoppingRepository(impl: ShoppingRepositoryImpl): ShoppingRepository

    @Binds @Singleton
    abstract fun bindPurchaseHistoryRepository(impl: PurchaseHistoryRepositoryImpl): PurchaseHistoryRepository

    @Binds @Singleton
    abstract fun bindStoreCategoryOrderRepository(
        impl: StoreCategoryOrderRepositoryImpl,
    ): StoreCategoryOrderRepository
}
