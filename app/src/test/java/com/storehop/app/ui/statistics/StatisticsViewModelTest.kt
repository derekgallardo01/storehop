package com.storehop.app.ui.statistics

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.storehop.app.data.dao.CategoryPurchaseCount
import com.storehop.app.data.dao.DayCount
import com.storehop.app.data.dao.DayOfWeekCount
import com.storehop.app.data.dao.ItemPurchaseCount
import com.storehop.app.data.dao.StorePurchaseCount
import com.storehop.app.data.db.relations.ItemWithCategoryAndStores
import com.storehop.app.data.entity.Category
import com.storehop.app.data.entity.Item
import com.storehop.app.data.entity.Store
import com.storehop.app.data.repository.CategoryRepository
import com.storehop.app.data.repository.ItemRepository
import com.storehop.app.data.repository.PurchaseHistoryRepository
import com.storehop.app.data.repository.StoreRepository
import com.storehop.app.testing.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Pins the StatisticsViewModel's combine + state-derivation logic that the
 * Settings → Statistics screen depends on. Aggregations themselves are
 * tested at the DAO layer; this test mocks the four source repos and
 * focuses on:
 *
 *   - Empty / Ready / Loading state branching
 *   - Stale-item filtering (60-day cutoff + isNeeded gate)
 *   - Name-resolution from the lookup maps (deleted items / stores drop
 *     out of the rendered list even if their PurchaseRecord row survived)
 *   - Uncategorised handling (empty `categoryId` surfaces as `""` in the
 *     output NamedCount.name; the UI substitutes the localized fallback)
 *   - mostActiveDayOfWeek and mostShoppedStore wiring
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val purchases: PurchaseHistoryRepository = mockk()
    private val itemRepo: ItemRepository = mockk()
    private val storeRepo: StoreRepository = mockk()
    private val categoryRepo: CategoryRepository = mockk()
    private val nowMillis: Long =
        Instant.parse("2026-05-10T12:00:00Z").toEpochMilli()
    private val fixedClock: Clock = Clock.fixed(Instant.ofEpochMilli(nowMillis), ZoneOffset.UTC)

    @Test fun `Empty state when no purchases AND no items`() = runTest {
        wireRepos(
            totalCount = 0,
            items = emptyList(),
            stores = emptyList(),
            categories = emptyList(),
        )
        val vm = newVm()

        vm.state.test {
            awaitItem() // Loading seed
            advanceUntilIdle()
            assertThat(expectMostRecentItem()).isEqualTo(StatisticsUiState.Empty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `Ready state when totalCount is zero but library has items`() = runTest {
        // Even with no purchase history, a populated library should render
        // the library counters in Ready -- the screen shouldn't hide the
        // staple/priority counts just because the user hasn't shopped yet.
        wireRepos(
            totalCount = 0,
            items = listOf(item("milk", "Milk", isStaple = true)),
            stores = listOf(store("lidl", "Lidl")),
            categories = emptyList(),
        )

        newVm().state.test {
            awaitItem()
            advanceUntilIdle()
            val state = expectMostRecentItem() as StatisticsUiState.Ready
            assertThat(state.totalPurchases).isEqualTo(0)
            assertThat(state.totalItems).isEqualTo(1)
            assertThat(state.stapleItems).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `Ready state surfaces purchase counts and library totals`() = runTest {
        wireRepos(
            totalCount = 7,
            last30 = 5,
            last7 = 2,
            items = listOf(
                item("milk", "Milk", isStaple = true),
                item("bread", "Bread", isPriority = true),
                item("eggs", "Eggs"),
            ),
            stores = listOf(store("lidl", "Lidl")),
            categories = emptyList(),
        )

        newVm().state.test {
            awaitItem()
            advanceUntilIdle()
            val state = expectMostRecentItem() as StatisticsUiState.Ready
            assertThat(state.totalPurchases).isEqualTo(7)
            assertThat(state.purchasesLast30Days).isEqualTo(5)
            assertThat(state.purchasesLast7Days).isEqualTo(2)
            assertThat(state.totalItems).isEqualTo(3)
            assertThat(state.stapleItems).isEqualTo(1)
            assertThat(state.priorityItems).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `topItems map back to current item names and deleted items drop out`() = runTest {
        // The DAO might return raw counts referencing items that have since
        // been tombstoned -- their id won't be in the live items list, so
        // the lookup-map join drops them rather than rendering an empty row.
        val topRaw = listOf(
            ItemPurchaseCount(itemId = "milk", count = 3),
            ItemPurchaseCount(itemId = "ghost", count = 2),  // not in items list
            ItemPurchaseCount(itemId = "bread", count = 1),
        )
        wireRepos(
            totalCount = 6,
            topItemsRaw = topRaw,
            items = listOf(
                item("milk", "Milk"),
                item("bread", "Bread"),
            ),
        )

        newVm().state.test {
            awaitItem(); advanceUntilIdle()
            val state = expectMostRecentItem() as StatisticsUiState.Ready
            assertThat(state.topItems.map { it.name to it.count })
                .containsExactly("Milk" to 3, "Bread" to 1).inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `purchasesByStore drops deleted store ids and preserves count order`() = runTest {
        val byStoreRaw = listOf(
            StorePurchaseCount(storeId = "lidl", count = 5),
            StorePurchaseCount(storeId = "ghost", count = 4),  // deleted store
            StorePurchaseCount(storeId = "aldi", count = 3),
        )
        wireRepos(
            totalCount = 12,
            byStoreRaw = byStoreRaw,
            items = listOf(item("milk", "Milk")),
            stores = listOf(store("lidl", "Lidl"), store("aldi", "Aldi")),
        )

        newVm().state.test {
            awaitItem(); advanceUntilIdle()
            val state = expectMostRecentItem() as StatisticsUiState.Ready
            assertThat(state.purchasesByStore.map { it.name to it.count })
                .containsExactly("Lidl" to 5, "Aldi" to 3).inOrder()
            assertThat(state.mostShoppedStore?.name).isEqualTo("Lidl")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `purchasesByCategory empty categoryId surfaces as empty string for uncategorised`() = runTest {
        // The VM keeps the raw category sentinel (empty string) and lets the
        // UI render the localized "Uncategorised" label. Test that the empty
        // string survives the map step rather than being dropped or coerced.
        val byCategoryRaw = listOf(
            CategoryPurchaseCount(categoryId = "cat_dairy", count = 4),
            CategoryPurchaseCount(categoryId = "", count = 2),       // uncategorised
            CategoryPurchaseCount(categoryId = "ghost_cat", count = 1), // deleted
        )
        wireRepos(
            totalCount = 7,
            byCategoryRaw = byCategoryRaw,
            items = listOf(item("milk", "Milk")),
            categories = listOf(
                Category(
                    id = "cat_dairy", name = "Dairy", nameKey = null, icon = null,
                    isArchived = false, isSeeded = true, userId = "u",
                    createdAt = 1L, updatedAt = 1L, deletedAt = null,
                ),
            ),
        )

        newVm().state.test {
            awaitItem(); advanceUntilIdle()
            val state = expectMostRecentItem() as StatisticsUiState.Ready
            // Three rows — the deleted-category row collapses to "" because
            // its id isn't in the lookup map (matches uncategorised
            // rendering at the UI level — both surface as empty-string).
            assertThat(state.purchasesByCategory.map { it.name to it.count })
                .containsExactly("Dairy" to 4, "" to 2, "" to 1)
                .inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `mostActiveDayOfWeek picks the highest-count DOW`() = runTest {
        val byDow = listOf(
            DayOfWeekCount(dayOfWeek = 6, count = 9),  // Saturday wins
            DayOfWeekCount(dayOfWeek = 0, count = 3),
            DayOfWeekCount(dayOfWeek = 3, count = 1),
        )
        wireRepos(
            totalCount = 13,
            byDow = byDow,
            items = listOf(item("milk", "Milk")),
        )

        newVm().state.test {
            awaitItem(); advanceUntilIdle()
            val state = expectMostRecentItem() as StatisticsUiState.Ready
            assertThat(state.mostActiveDayOfWeek).isEqualTo(6)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `mostActiveDayOfWeek is null when no purchases recorded`() = runTest {
        wireRepos(
            totalCount = 0,
            byDow = emptyList(),
            items = listOf(item("milk", "Milk")), // library exists; not Empty
        )

        newVm().state.test {
            awaitItem(); advanceUntilIdle()
            val state = expectMostRecentItem() as StatisticsUiState.Ready
            assertThat(state.mostActiveDayOfWeek).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `staleItems surface only items with lastPurchasedAt older than 60 days AND isNeeded false`() = runTest {
        val sixtyOneDaysMs = Duration.ofDays(61).toMillis()
        val ninetyDaysMs = Duration.ofDays(90).toMillis()
        val recentMs = Duration.ofDays(5).toMillis()

        val items = listOf(
            // Eligible: tombstoned-by-time, not currently needed.
            item("old_eggs", "Eggs", lastPurchasedAt = nowMillis - ninetyDaysMs, isNeeded = false),
            // Eligible but more recent — should sort BEFORE old_eggs (DESC by days).
            item("old_butter", "Butter", lastPurchasedAt = nowMillis - sixtyOneDaysMs, isNeeded = false),
            // Not stale: too recent.
            item("recent", "Recent", lastPurchasedAt = nowMillis - recentMs, isNeeded = false),
            // Not stale: still on the active shopping list (isNeeded = true).
            item("needed", "Needed", lastPurchasedAt = nowMillis - ninetyDaysMs, isNeeded = true),
            // Not stale: never purchased.
            item("never", "Never", lastPurchasedAt = null, isNeeded = false),
        )
        wireRepos(totalCount = 4, items = items)

        newVm().state.test {
            awaitItem(); advanceUntilIdle()
            val state = expectMostRecentItem() as StatisticsUiState.Ready
            // DESC by daysSinceLastPurchase: 90 days first, then 61.
            assertThat(state.staleItems.map { it.name })
                .containsExactly("Eggs", "Butter").inOrder()
            assertThat(state.staleItems[0].daysSinceLastPurchase).isEqualTo(90)
            assertThat(state.staleItems[1].daysSinceLastPurchase).isEqualTo(61)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `purchasesPerDay is passed through unchanged for the trend chart`() = runTest {
        val perDay = listOf(
            DayCount(day = "2026-04-01", count = 1),
            DayCount(day = "2026-04-15", count = 4),
            DayCount(day = "2026-05-08", count = 2),
        )
        wireRepos(
            totalCount = 7,
            perDay = perDay,
            items = listOf(item("milk", "Milk")),
        )

        newVm().state.test {
            awaitItem(); advanceUntilIdle()
            val state = expectMostRecentItem() as StatisticsUiState.Ready
            assertThat(state.purchasesPerDay).containsExactlyElementsIn(perDay).inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `totalStores excludes archived and tombstoned rows`() = runTest {
        wireRepos(
            totalCount = 1,
            items = listOf(item("milk", "Milk")),
            stores = listOf(
                store("lidl", "Lidl"),
                store("aldi", "Aldi", isArchived = true),
                store("dead", "Dead", deletedAt = 100L),
            ),
        )

        newVm().state.test {
            awaitItem(); advanceUntilIdle()
            val state = expectMostRecentItem() as StatisticsUiState.Ready
            assertThat(state.totalStores).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // MARK: - Helpers

    private fun newVm() = StatisticsViewModel(
        purchases = purchases,
        itemRepository = itemRepo,
        storeRepository = storeRepo,
        categoryRepository = categoryRepo,
        clock = fixedClock,
    )

    private fun wireRepos(
        totalCount: Int = 0,
        last30: Int = 0,
        last7: Int = 0,
        perDay: List<DayCount> = emptyList(),
        byDow: List<DayOfWeekCount> = emptyList(),
        topItemsRaw: List<ItemPurchaseCount> = emptyList(),
        byStoreRaw: List<StorePurchaseCount> = emptyList(),
        byCategoryRaw: List<CategoryPurchaseCount> = emptyList(),
        items: List<Item> = emptyList(),
        stores: List<Store> = emptyList(),
        categories: List<Category> = emptyList(),
    ) {
        every { purchases.observeTotalCount() } returns flowOf(totalCount)
        // The VM calls observeCountSince twice -- for 30d and 7d windows. We
        // can't statically distinguish the two parameter values without
        // strict matching, so return both via parameter-discriminated
        // answers. The 30-day cutoff is 30 days before nowMillis; 7-day is
        // 7 days before. Use those exact values.
        val thirtyDaysAgo = nowMillis - Duration.ofDays(30).toMillis()
        val sevenDaysAgo = nowMillis - Duration.ofDays(7).toMillis()
        every { purchases.observeCountSince(thirtyDaysAgo) } returns flowOf(last30)
        every { purchases.observeCountSince(sevenDaysAgo) } returns flowOf(last7)
        every { purchases.observePurchasesPerDay(any()) } returns flowOf(perDay)
        every { purchases.observePurchasesByDayOfWeek() } returns flowOf(byDow)
        every { purchases.observeTopItems(any()) } returns flowOf(topItemsRaw)
        every { purchases.observePurchasesByStore() } returns flowOf(byStoreRaw)
        every { purchases.observePurchasesByCategory() } returns flowOf(byCategoryRaw)

        val itemsWithRelations = items.map {
            ItemWithCategoryAndStores(item = it, category = null, stores = emptyList())
        }
        every { itemRepo.observeAll() } returns flowOf(itemsWithRelations)
        every { storeRepo.observeAll(includeArchived = true) } returns flowOf(stores)
        every { categoryRepo.observeAll(includeArchived = true) } returns flowOf(categories)
    }

    private fun item(
        id: String,
        name: String,
        isStaple: Boolean = false,
        isPriority: Boolean = false,
        isNeeded: Boolean = true,
        lastPurchasedAt: Long? = null,
    ) = Item(
        id = id, name = name, categoryId = null, notes = null, quantity = null,
        isNeeded = isNeeded, lastPurchasedAt = lastPurchasedAt,
        userId = "u", createdAt = 1L, updatedAt = 1L, deletedAt = null,
        brand = null, imageUrl = null, isStaple = isStaple, isPriority = isPriority,
    )

    private fun store(
        id: String,
        name: String,
        isArchived: Boolean = false,
        deletedAt: Long? = null,
    ) = Store(
        id = id, name = name, colorArgb = null,
        isArchived = isArchived, isSeeded = false, userId = "u",
        createdAt = 1L, updatedAt = 1L, deletedAt = deletedAt,
    )
}
