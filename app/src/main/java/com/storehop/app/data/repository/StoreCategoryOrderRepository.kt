package com.storehop.app.data.repository

import com.storehop.app.data.entity.StoreCategoryOrder
import kotlinx.coroutines.flow.Flow

/**
 * Per-store aisle ordering: which categories appear in what order when
 * shopping at a specific store. Wraps [com.storehop.app.data.dao.StoreCategoryOrderDao]
 * so ViewModels reach for a clean abstraction instead of binding to a DAO.
 *
 * The seed pack ships per-store orderings (Lidl puts Dairy in aisle 3,
 * Aldi in aisle 1, etc.) — users can override those via the Edit-aisle
 * screen, which calls [reorderCategoriesForStore] with the new list.
 */
interface StoreCategoryOrderRepository {
    /**
     * Live ordered list of SCO rows for [storeId], sorted by displayOrder.
     * Drives the Edit-aisle screen and the Shop-at-Store category sort.
     */
    fun observeForStore(storeId: String): Flow<List<StoreCategoryOrder>>

    /**
     * Atomic replace of the ordered set of categories for one store.
     * [orderedCategoryIds] is the full top-to-bottom list of categories
     * the user wants visible at this store, in the desired display order.
     * Categories not in this list get their SCO row tombstoned (the user
     * decided this category isn't relevant to this store anymore).
     *
     * Wrapped in a transaction by the underlying DAO so a partial write
     * (caller cancels, process dies mid-write) never leaves a half-applied
     * reorder. Each row gets re-flagged pendingSync = true so the next
     * SyncEngine tick pushes the change to Firestore.
     */
    suspend fun reorderCategoriesForStore(storeId: String, orderedCategoryIds: List<String>)
}
