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
}
