package com.storehop.app.data.repository

import com.storehop.app.data.entity.Category
import kotlinx.coroutines.flow.Flow

interface CategoryRepository {
    fun observeAll(includeArchived: Boolean = false): Flow<List<Category>>

    suspend fun addCategory(name: String, icon: String? = null): String
    suspend fun rename(id: String, name: String)
    suspend fun setArchived(id: String, archived: Boolean)
    suspend fun softDelete(id: String)

    /**
     * Undo a previous [softDelete]. Reads the row's current `deletedAt`,
     * restores the category, restores per-store aisle ordering rows, and
     * re-links items that had their `categoryId` cascade-cleared at the
     * exact same instant. No-op if the row isn't currently tombstoned.
     */
    suspend fun undoSoftDelete(id: String)

    /**
     * Rewrite the global Manage Categories order. [orderedIds] is the new
     * top-to-bottom sequence; each id gets `displayOrder = index`. Wrapped
     * in a transaction so a partial write can't leave the list in a
     * half-reordered state. Per-store aisle order (StoreCategoryOrder) is
     * unaffected.
     */
    suspend fun reorder(orderedIds: List<String>)

    /**
     * Batch soft-delete. Every id in [ids] is tombstoned at the same
     * `deletedAt` so [undoSoftDeleteMany] can restore the exact set in one
     * shot. Cascades item.categoryId clearing + per-store aisle order
     * tombstoning identically to single-row [softDelete]. Returns the
     * batch deletedAt the caller can hand to undoSoftDeleteMany.
     */
    suspend fun softDeleteMany(ids: List<String>): Long

    /**
     * Reverse a [softDeleteMany] batch. Restores every category tombstoned
     * at exactly [deletedAt], plus their cascade-cleared SCO + item links.
     */
    suspend fun undoSoftDeleteMany(deletedAt: Long)
}
