package com.storehop.app.data.repository

import com.storehop.app.data.dao.CategoryPurchaseCount
import com.storehop.app.data.dao.DayCount
import com.storehop.app.data.dao.DayOfWeekCount
import com.storehop.app.data.dao.ItemPurchaseCount
import com.storehop.app.data.dao.StorePurchaseCount
import com.storehop.app.data.entity.PurchaseRecord
import kotlinx.coroutines.flow.Flow

interface PurchaseHistoryRepository {
    fun observeForItem(itemId: String): Flow<List<PurchaseRecord>>
    suspend fun softDelete(id: String)

    // Aggregates powering the Statistics screen. All scoped to the active
    // session — repositories return empty flows when the user is signed out.

    fun observeTotalCount(): Flow<Int>
    fun observeCountSince(sinceMillis: Long): Flow<Int>
    fun observePurchasesPerDay(sinceMillis: Long): Flow<List<DayCount>>
    fun observePurchasesByDayOfWeek(): Flow<List<DayOfWeekCount>>
    fun observeTopItems(limit: Int): Flow<List<ItemPurchaseCount>>
    fun observePurchasesByStore(): Flow<List<StorePurchaseCount>>
    fun observePurchasesByCategory(): Flow<List<CategoryPurchaseCount>>
}
