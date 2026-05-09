package com.storehop.app.ui.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storehop.app.data.dao.CategoryPurchaseCount
import com.storehop.app.data.dao.DayCount
import com.storehop.app.data.dao.DayOfWeekCount
import com.storehop.app.data.dao.ItemPurchaseCount
import com.storehop.app.data.dao.StorePurchaseCount
import com.storehop.app.data.repository.CategoryRepository
import com.storehop.app.data.repository.ItemRepository
import com.storehop.app.data.repository.PurchaseHistoryRepository
import com.storehop.app.data.repository.StoreRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.Duration
import javax.inject.Inject

@HiltViewModel
class StatisticsViewModel @Inject constructor(
    purchases: PurchaseHistoryRepository,
    itemRepository: ItemRepository,
    storeRepository: StoreRepository,
    categoryRepository: CategoryRepository,
    private val clock: Clock,
) : ViewModel() {

    val state: StateFlow<StatisticsUiState> = run {
        val now = clock.millis()
        val thirtyDaysAgo = now - Duration.ofDays(30).toMillis()
        val sevenDaysAgo = now - Duration.ofDays(7).toMillis()
        // Trend chart: last 12 weeks of daily activity. 84-day window keeps
        // the chart legible on a phone screen — long enough to spot a pattern,
        // short enough that low-volume users don't see one tall bar lost in
        // empty days.
        val trendStart = now - Duration.ofDays(TREND_WINDOW_DAYS.toLong()).toMillis()
        val staleCutoff = now - Duration.ofDays(STALE_THRESHOLD_DAYS.toLong()).toMillis()

        // 11 source flows. combine()'s typed overloads cap at 5 arguments;
        // for >5 we spread an Array<Flow<Any?>> through the vararg overload
        // (Flow is covariant on T, so the upcasts are implicit).
        val sources: Array<kotlinx.coroutines.flow.Flow<Any?>> = arrayOf(
            purchases.observeTotalCount(),
            purchases.observeCountSince(thirtyDaysAgo),
            purchases.observeCountSince(sevenDaysAgo),
            purchases.observePurchasesPerDay(trendStart),
            purchases.observePurchasesByDayOfWeek(),
            purchases.observeTopItems(TOP_ITEMS_LIMIT),
            purchases.observePurchasesByStore(),
            purchases.observePurchasesByCategory(),
            itemRepository.observeAll(),
            storeRepository.observeAll(includeArchived = true),
            categoryRepository.observeAll(includeArchived = true),
        )
        combine(*sources) { values ->
            @Suppress("UNCHECKED_CAST")
            buildState(
                totalCount = values[0] as Int,
                last30 = values[1] as Int,
                last7 = values[2] as Int,
                perDay = values[3] as List<DayCount>,
                byDow = values[4] as List<DayOfWeekCount>,
                topItemsRaw = values[5] as List<ItemPurchaseCount>,
                byStoreRaw = values[6] as List<StorePurchaseCount>,
                byCategoryRaw = values[7] as List<CategoryPurchaseCount>,
                items = values[8] as List<com.storehop.app.data.db.relations.ItemWithCategoryAndStores>,
                stores = values[9] as List<com.storehop.app.data.entity.Store>,
                categories = values[10] as List<com.storehop.app.data.entity.Category>,
                staleCutoff = staleCutoff,
                now = now,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = StatisticsUiState.Loading,
        )
    }

    private fun buildState(
        totalCount: Int,
        last30: Int,
        last7: Int,
        perDay: List<DayCount>,
        byDow: List<DayOfWeekCount>,
        topItemsRaw: List<ItemPurchaseCount>,
        byStoreRaw: List<StorePurchaseCount>,
        byCategoryRaw: List<CategoryPurchaseCount>,
        items: List<com.storehop.app.data.db.relations.ItemWithCategoryAndStores>,
        stores: List<com.storehop.app.data.entity.Store>,
        categories: List<com.storehop.app.data.entity.Category>,
        staleCutoff: Long,
        now: Long,
    ): StatisticsUiState {
        // Totals across the library: useful even when the user has no
        // purchase history yet (so the screen still reads as informative).
        val totalItems = items.size
        val stapleItems = items.count { it.item.isStaple }
        val priorityItems = items.count { it.item.isPriority }

        if (totalCount == 0 && totalItems == 0) return StatisticsUiState.Empty

        val itemNamesById = items.associate { it.item.id to it.item.name }
        val storeNamesById = stores.associate { it.id to it.name }
        val categoryNamesById = categories.associate { it.id to it.name }

        val topItems = topItemsRaw.mapNotNull { row ->
            val name = itemNamesById[row.itemId] ?: return@mapNotNull null
            NamedCount(name = name, count = row.count)
        }

        val purchasesByStore = byStoreRaw.mapNotNull { row ->
            val name = storeNamesById[row.storeId] ?: return@mapNotNull null
            NamedCount(name = name, count = row.count)
        }

        val purchasesByCategory = byCategoryRaw.map { row ->
            val name = if (row.categoryId.isBlank()) {
                null // surfaced as "Uncategorised" via a localised fallback in UI
            } else {
                categoryNamesById[row.categoryId]
            }
            NamedCount(name = name ?: "", count = row.count)
        }

        val staleItems = items
            .asSequence()
            .filter { row ->
                val last = row.item.lastPurchasedAt ?: return@filter false
                last < staleCutoff && !row.item.isNeeded
            }
            .map { row ->
                val last = row.item.lastPurchasedAt ?: 0L
                val days = ((now - last) / DAY_MILLIS).toInt()
                StaleItemRow(name = row.item.name, daysSinceLastPurchase = days)
            }
            .sortedByDescending { it.daysSinceLastPurchase }
            .take(STALE_ITEMS_LIMIT)
            .toList()

        val mostShoppedStore = purchasesByStore.firstOrNull()

        val mostActiveDayOfWeek = byDow.maxByOrNull { it.count }?.dayOfWeek

        return StatisticsUiState.Ready(
            totalPurchases = totalCount,
            purchasesLast30Days = last30,
            purchasesLast7Days = last7,
            purchasesPerDay = perDay,
            mostActiveDayOfWeek = mostActiveDayOfWeek,
            totalItems = totalItems,
            stapleItems = stapleItems,
            priorityItems = priorityItems,
            topItems = topItems,
            staleItems = staleItems,
            totalStores = stores.count { !it.isArchived && it.deletedAt == null },
            mostShoppedStore = mostShoppedStore,
            purchasesByStore = purchasesByStore,
            totalCategories = categories.size,
            purchasesByCategory = purchasesByCategory,
        )
    }

    companion object {
        const val TREND_WINDOW_DAYS = 84
        const val STALE_THRESHOLD_DAYS = 60
        const val TOP_ITEMS_LIMIT = 10
        const val STALE_ITEMS_LIMIT = 20
        private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    }
}

sealed interface StatisticsUiState {
    data object Loading : StatisticsUiState
    data object Empty : StatisticsUiState
    data class Ready(
        val totalPurchases: Int,
        val purchasesLast30Days: Int,
        val purchasesLast7Days: Int,
        val purchasesPerDay: List<DayCount>,
        /** 0 = Sunday … 6 = Saturday, null when there are no purchases yet. */
        val mostActiveDayOfWeek: Int?,
        val totalItems: Int,
        val stapleItems: Int,
        val priorityItems: Int,
        val topItems: List<NamedCount>,
        val staleItems: List<StaleItemRow>,
        val totalStores: Int,
        val mostShoppedStore: NamedCount?,
        val purchasesByStore: List<NamedCount>,
        val totalCategories: Int,
        val purchasesByCategory: List<NamedCount>,
    ) : StatisticsUiState
}

data class NamedCount(val name: String, val count: Int)
data class StaleItemRow(val name: String, val daysSinceLastPurchase: Int)
