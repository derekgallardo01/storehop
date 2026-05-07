package com.storehop.app.data.repository

import com.storehop.app.data.db.relations.ItemWithCategoryAndStores
import kotlinx.coroutines.flow.Flow

interface ItemRepository {
    fun observeAll(): Flow<List<ItemWithCategoryAndStores>>
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

    /**
     * Mark this item purchased at one specific store. Per-store need state:
     * checking off milk at Lidl flips only `isx(Lidl, milk).isNeeded` -- the
     * Aldi xref is untouched, so milk still shows as needed at Aldi. Writes
     * exactly one [com.storehop.app.data.entity.PurchaseRecord] for the
     * (item, store) pair.
     */
    suspend fun markPurchasedAtStore(itemId: String, storeId: String)

    /**
     * Restore the (item, store) row to needed at that store. Used by
     * Shop-at-Store to un-check a mis-tap. No PurchaseRecord is written --
     * this is a state correction, not a purchase.
     */
    suspend fun markNeededAtStore(itemId: String, storeId: String)
}
