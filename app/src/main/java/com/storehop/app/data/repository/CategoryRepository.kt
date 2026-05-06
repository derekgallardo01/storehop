package com.storehop.app.data.repository

import com.storehop.app.data.entity.Category
import kotlinx.coroutines.flow.Flow

interface CategoryRepository {
    fun observeAll(includeArchived: Boolean = false): Flow<List<Category>>

    suspend fun addCategory(name: String, icon: String? = null): String
    suspend fun rename(id: String, name: String)
    suspend fun setArchived(id: String, archived: Boolean)
    suspend fun softDelete(id: String)
}
