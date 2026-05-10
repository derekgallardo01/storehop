package com.storehop.app.ui.items

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.storehop.app.data.db.relations.ItemWithCategoryAndStores
import com.storehop.app.data.entity.Category
import com.storehop.app.data.entity.Item
import com.storehop.app.data.prefs.SortMode
import com.storehop.app.data.prefs.UserPreferencesRepository
import com.storehop.app.data.repository.ItemRepository
import com.storehop.app.testing.MainDispatcherRule
import com.storehop.app.ui.util.UndoEvent
import com.storehop.app.ui.util.UndoEventBus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Items master screen VM. Three behaviors to pin:
 *  - search filter (name OR brand, case-insensitive) is the only logic the VM
 *    owns -- if it regresses, the master list becomes unsearchable.
 *  - undoEvents passes through from UndoEventBus exactly once per event;
 *    that's how the cross-screen undo from item-deleted-in-form lands here.
 *  - sort mode (ALPHABETIC vs CATEGORY): in ALPHABETIC, `rows` is populated
 *    and `sections` is empty; in CATEGORY, the reverse, with uncategorised
 *    items collected into a trailing sentinel section.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ItemsListViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val itemRepo: ItemRepository = mockk(relaxed = true)
    private val undoBus = UndoEventBus()
    private val itemsFlow = MutableStateFlow<List<ItemWithCategoryAndStores>>(emptyList())
    private val neededIdsFlow = MutableStateFlow<Set<String>>(emptySet())
    private val sortModeFlow = MutableStateFlow(SortMode.ALPHABETIC)
    private val prefsRepo: UserPreferencesRepository = mockk {
        every { itemsListSortMode } returns sortModeFlow
        coEvery { setItemsListSortMode(any()) } answers {
            sortModeFlow.value = firstArg()
        }
    }

    init {
        every { itemRepo.observeNeededItemIds() } returns neededIdsFlow
    }

    @Test fun `rows returns the full repo list when query is empty (alphabetic mode)`() = runTest {
        every { itemRepo.observeAll() } returns itemsFlow
        itemsFlow.value = listOf(
            row("milk", "Milk", brand = "Mimosa"),
            row("eggs", "Eggs", brand = null),
        )

        val vm = ItemsListViewModel(itemRepo, prefsRepo, undoBus)
        vm.uiState.test {
            awaitItem() // initial empty
            advanceUntilIdle()
            val state = expectMostRecentItem()
            // ALPHABETIC sort -> case-insensitive name order.
            assertThat(state.rows.map { it.item.name }).containsExactly("Eggs", "Milk").inOrder()
            assertThat(state.sections).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `setQuery matches category name too (v0_6_2)`() = runTest {
        every { itemRepo.observeAll() } returns itemsFlow
        val frozen = Category(
            id = "cat_frozen", name = "Frozen", nameKey = "cat_frozen",
            icon = null, isArchived = false, isSeeded = true,
            userId = "u", createdAt = 1L, updatedAt = 1L, deletedAt = null,
        )
        val dairy = Category(
            id = "cat_dairy", name = "Dairy", nameKey = "cat_dairy",
            icon = null, isArchived = false, isSeeded = true,
            userId = "u", createdAt = 1L, updatedAt = 1L, deletedAt = null,
        )
        itemsFlow.value = listOf(
            row("calamari", "Calamari", category = frozen),
            row("milk", "Milk", category = dairy),
            row("eggs", "Eggs", category = null),
        )

        val vm = ItemsListViewModel(itemRepo, prefsRepo, undoBus)
        vm.setQuery("FRO")  // matches "Frozen" category, ignore-case
        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            // Calamari surfaces by virtue of its Frozen category --
            // its own name doesn't contain "FRO".
            assertThat(expectMostRecentItem().rows.map { it.item.name })
                .containsExactly("Calamari")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `setQuery filters by name AND brand, case insensitive`() = runTest {
        every { itemRepo.observeAll() } returns itemsFlow
        itemsFlow.value = listOf(
            row("milk", "Milk", brand = "Mimosa"),
            row("eggs", "Eggs", brand = null),
            row("oat", "Oat Drink", brand = null),
        )

        val vm = ItemsListViewModel(itemRepo, prefsRepo, undoBus)
        vm.setQuery("MIM")
        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            assertThat(expectMostRecentItem().rows.map { it.item.name }).containsExactly("Milk")
            cancelAndIgnoreRemainingEvents()
        }

        vm.setQuery("OAT")
        vm.uiState.test {
            advanceUntilIdle()
            assertThat(expectMostRecentItem().rows.map { it.item.name }).containsExactly("Oat Drink")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `setQuery with whitespace-only is treated as empty`() = runTest {
        every { itemRepo.observeAll() } returns itemsFlow
        itemsFlow.value = listOf(
            row("milk", "Milk"),
            row("eggs", "Eggs"),
        )

        val vm = ItemsListViewModel(itemRepo, prefsRepo, undoBus)
        vm.setQuery("    ")
        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            assertThat(expectMostRecentItem().rows).hasSize(2)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `setSortMode CATEGORY groups by category and clears flat rows`() = runTest {
        every { itemRepo.observeAll() } returns itemsFlow
        val produce = Category(
            id = "cat_produce", name = "Produce", nameKey = "cat_produce",
            icon = null, isArchived = false, isSeeded = true,
            userId = "u", createdAt = 1L, updatedAt = 1L, deletedAt = null,
        )
        val dairy = Category(
            id = "cat_dairy", name = "Dairy", nameKey = "cat_dairy",
            icon = null, isArchived = false, isSeeded = true,
            userId = "u", createdAt = 1L, updatedAt = 1L, deletedAt = null,
        )
        itemsFlow.value = listOf(
            row("milk", "Milk", category = dairy),
            row("apples", "Apples", category = produce),
            row("eggs", "Eggs", category = dairy),
        )

        val vm = ItemsListViewModel(itemRepo, prefsRepo, undoBus)
        vm.setSortMode(SortMode.CATEGORY)
        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertThat(state.sortMode).isEqualTo(SortMode.CATEGORY)
            assertThat(state.rows).isEmpty()
            // Sections sorted alphabetically by category name (Dairy, Produce).
            assertThat(state.sections.map { it.categoryName })
                .containsExactly("Dairy", "Produce").inOrder()
            // Items within a section sorted alphabetically.
            assertThat(state.sections[0].rows.map { it.item.name })
                .containsExactly("Eggs", "Milk").inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `setSortMode CATEGORY collects uncategorised items into a trailing sentinel section`() = runTest {
        every { itemRepo.observeAll() } returns itemsFlow
        val produce = Category(
            id = "cat_produce", name = "Produce", nameKey = "cat_produce",
            icon = null, isArchived = false, isSeeded = true,
            userId = "u", createdAt = 1L, updatedAt = 1L, deletedAt = null,
        )
        itemsFlow.value = listOf(
            row("apples", "Apples", category = produce),
            row("misc", "Mystery item", category = null),
        )

        val vm = ItemsListViewModel(itemRepo, prefsRepo, undoBus)
        vm.setSortMode(SortMode.CATEGORY)
        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val state = expectMostRecentItem()
            // The last section is the uncategorised sentinel (the screen swaps
            // it for the localized "(uncategorised)" label).
            assertThat(state.sections).hasSize(2)
            val last = state.sections.last()
            assertThat(last.categoryName).isEqualTo(UNCATEGORISED_SENTINEL)
            assertThat(last.categoryNameKey).isNull()
            assertThat(last.rows.map { it.item.name }).containsExactly("Mystery item")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `undoEvents forwards events emitted on the bus`() = runTest {
        every { itemRepo.observeAll() } returns itemsFlow
        val vm = ItemsListViewModel(itemRepo, prefsRepo, undoBus)

        vm.undoEvents.test {
            undoBus.emit(UndoEvent.ItemDeleted(itemId = "milk", itemName = "Milk"))
            val event = awaitItem() as UndoEvent.ItemDeleted
            assertThat(event.itemId).isEqualTo("milk")
            assertThat(event.itemName).isEqualTo("Milk")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `undoItemDelete forwards to the repository`() = runTest {
        every { itemRepo.observeAll() } returns itemsFlow

        val vm = ItemsListViewModel(itemRepo, prefsRepo, undoBus)
        vm.undoItemDelete("milk")
        advanceUntilIdle()

        coVerify(exactly = 1) { itemRepo.undoSoftDelete("milk") }
    }

    // ---- v0.6.1: +/- toggle on the Items list ----------------------------

    @Test fun `neededItemIds flows through into the ui state`() = runTest {
        every { itemRepo.observeAll() } returns itemsFlow
        itemsFlow.value = listOf(row("milk", "Milk"), row("eggs", "Eggs"))
        neededIdsFlow.value = setOf("milk")

        val vm = ItemsListViewModel(itemRepo, prefsRepo, undoBus)
        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertThat(state.neededItemIds).containsExactly("milk")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `toggleNeededAtAllStores routes to markNeeded when not currently needed`() = runTest {
        every { itemRepo.observeAll() } returns itemsFlow
        val vm = ItemsListViewModel(itemRepo, prefsRepo, undoBus)
        vm.toggleNeededAtAllStores(itemId = "milk", currentlyNeeded = false)
        advanceUntilIdle()

        coVerify(exactly = 1) { itemRepo.markNeededAcrossAllStores("milk") }
        coVerify(exactly = 0) { itemRepo.markPurchasedAcrossAllStores(any()) }
    }

    @Test fun `toggleNeededAtAllStores routes to markPurchased when currently needed`() = runTest {
        every { itemRepo.observeAll() } returns itemsFlow
        val vm = ItemsListViewModel(itemRepo, prefsRepo, undoBus)
        vm.toggleNeededAtAllStores(itemId = "milk", currentlyNeeded = true)
        advanceUntilIdle()

        coVerify(exactly = 1) { itemRepo.markPurchasedAcrossAllStores("milk") }
        coVerify(exactly = 0) { itemRepo.markNeededAcrossAllStores(any()) }
    }

    private fun row(
        id: String,
        name: String,
        brand: String? = null,
        category: Category? = null,
    ) = ItemWithCategoryAndStores(
        item = Item(
            id = id, name = name, categoryId = category?.id, notes = null,
            quantity = null, isNeeded = true, lastPurchasedAt = null,
            userId = "u", createdAt = 1L, updatedAt = 1L, deletedAt = null,
            brand = brand,
        ),
        category = category,
        stores = emptyList(),
    )
}
