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
     * Undo a previous [softDelete]. Reads the row's `deletedAt` and restores
     * the item plus every xref + PurchaseRecord row that was tombstoned at
     * that same instant. No-op when the row isn't currently tombstoned.
     */
    suspend fun undoSoftDelete(id: String)

    /**
     * Mark this item purchased. The user is at one specific [storeId], but
     * one purchase satisfies the need across every store the item is tagged
     * to: buying mozzarella at Lidl makes it disappear from the Aldi and
     * Pingo Doce lists too. Writes exactly one
     * [com.storehop.app.data.entity.PurchaseRecord] (the actual store of the
     * purchase) and returns the snapshot timestamp the caller should pass to
     * [undoPurchase] for a precise reversal. Returns null if the item isn't
     * owned by the live session.
     */
    suspend fun markPurchasedAtStore(itemId: String, storeId: String): Long?

    /**
     * Reverse the most recent [markPurchasedAtStore] for [itemId] at exactly
     * [snapshotTime] (the value [markPurchasedAtStore] returned). Restores
     * every xref the cascade flipped — those whose `lastPurchasedAt` matches
     * [snapshotTime] — back to needed, and soft-deletes the matching
     * PurchaseRecord. After undo, history shows no purchase: this is a true
     * "as if it never happened" rollback, not a state correction.
     */
    suspend fun undoPurchase(itemId: String, snapshotTime: Long)

    /**
     * Restore the (item, store) row to needed at that store. Used by
     * Shop-at-Store to manually un-check a single store after the snackbar
     * timeout — the cascade-undo above is the primary mis-tap path. No
     * PurchaseRecord is touched; this is a per-store state correction.
     */
    suspend fun markNeededAtStore(itemId: String, storeId: String)
}
