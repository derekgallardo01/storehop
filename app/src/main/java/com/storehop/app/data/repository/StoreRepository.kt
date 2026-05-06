package com.storehop.app.data.repository

import com.storehop.app.data.entity.Store
import kotlinx.coroutines.flow.Flow

interface StoreRepository {
    fun observeAll(includeArchived: Boolean = false): Flow<List<Store>>
    fun observeById(id: String): Flow<Store?>

    suspend fun addStore(name: String, colorArgb: Int? = null): String
    suspend fun rename(id: String, name: String)
    suspend fun setColor(id: String, colorArgb: Int?)
    suspend fun setArchived(id: String, archived: Boolean)
    suspend fun softDelete(id: String)
}
