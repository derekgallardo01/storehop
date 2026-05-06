package com.storehop.app.data.repository

import com.storehop.app.data.entity.PurchaseRecord
import kotlinx.coroutines.flow.Flow

interface PurchaseHistoryRepository {
    fun observeForItem(itemId: String): Flow<List<PurchaseRecord>>
    suspend fun softDelete(id: String)
}
