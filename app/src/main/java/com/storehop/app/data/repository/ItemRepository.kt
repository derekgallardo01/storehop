package com.storehop.app.data.repository

import com.storehop.app.data.db.relations.ItemWithCategoryAndStores
import com.storehop.app.data.entity.Item
import kotlinx.coroutines.flow.Flow

interface ItemRepository {
    fun observeAll(): Flow<List<ItemWithCategoryAndStores>>
    fun observeNeeded(): Flow<List<Item>>
    fun observeById(id: String): Flow<ItemWithCategoryAndStores?>

    suspend fun addItem(
        name: String,
        categoryId: String?,
        storeIds: Set<String>,
        quantity: String? = null,
        notes: String? = null,
        isNeeded: Boolean = true,
    ): String

    suspend fun updateItem(
        id: String,
        name: String,
        categoryId: String?,
        storeIds: Set<String>,
        quantity: String?,
        notes: String?,
    )

    suspend fun softDelete(id: String)
    suspend fun markPurchased(id: String)
}
