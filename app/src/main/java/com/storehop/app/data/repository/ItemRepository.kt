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
        brand: String? = null,
        imageUrl: String? = null,
        isStaple: Boolean = false,
        isPriority: Boolean = false,
    ): String

    suspend fun updateItem(
        id: String,
        name: String,
        categoryId: String?,
        storeIds: Set<String>,
        quantity: String?,
        notes: String?,
        brand: String? = null,
        imageUrl: String? = null,
        isStaple: Boolean = false,
        isPriority: Boolean = false,
    )

    suspend fun softDelete(id: String)
    suspend fun markPurchased(id: String)

    /**
     * Restore an item to the needed list. Used by Shop-at-Store to un-check a
     * purchased staple the user wants back on the list. No PurchaseRecord is
     * written -- this isn't a purchase, it's a state correction.
     */
    suspend fun markNeeded(id: String)
}
