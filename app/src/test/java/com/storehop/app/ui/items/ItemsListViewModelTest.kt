package com.storehop.app.ui.items

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.storehop.app.data.db.relations.ItemWithCategoryAndStores
import com.storehop.app.data.entity.Item
import com.storehop.app.data.repository.ItemRepository
import com.storehop.app.testing.MainDispatcherRule
import com.storehop.app.ui.util.UndoEvent
import com.storehop.app.ui.util.UndoEventBus
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
 * Items master screen VM. Two behaviors to pin:
 *  - search filter (name OR brand, case-insensitive) is the only logic the VM
 *    owns -- if it regresses, the master list becomes unsearchable.
 *  - undoEvents passes through from UndoEventBus exactly once per event;
 *    that's how the cross-screen undo from item-deleted-in-form lands here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ItemsListViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val itemRepo: ItemRepository = mockk(relaxed = true)
    private val undoBus = UndoEventBus()
    private val itemsFlow = MutableStateFlow<List<ItemWithCategoryAndStores>>(emptyList())

    @Test fun `items returns the full repo list when query is empty`() = runTest {
        every { itemRepo.observeAll() } returns itemsFlow
        itemsFlow.value = listOf(
            row("milk", "Milk", brand = "Mimosa"),
            row("eggs", "Eggs", brand = null),
        )

        val vm = ItemsListViewModel(itemRepo, undoBus)
        vm.items.test {
            awaitItem() // initial empty
            advanceUntilIdle()
            val rows = expectMostRecentItem()
            assertThat(rows.map { it.item.name }).containsExactly("Milk", "Eggs")
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

        val vm = ItemsListViewModel(itemRepo, undoBus)
        // Match by brand: "mim" -> Milk only.
        vm.setQuery("MIM")
        vm.items.test {
            awaitItem()
            advanceUntilIdle()
            assertThat(expectMostRecentItem().map { it.item.name }).containsExactly("Milk")
            cancelAndIgnoreRemainingEvents()
        }

        // Match by name: "OAT" -> Oat Drink.
        vm.setQuery("OAT")
        vm.items.test {
            advanceUntilIdle()
            assertThat(expectMostRecentItem().map { it.item.name }).containsExactly("Oat Drink")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `setQuery with whitespace-only is treated as empty`() = runTest {
        every { itemRepo.observeAll() } returns itemsFlow
        itemsFlow.value = listOf(
            row("milk", "Milk"),
            row("eggs", "Eggs"),
        )

        val vm = ItemsListViewModel(itemRepo, undoBus)
        vm.setQuery("    ") // pure whitespace
        vm.items.test {
            awaitItem()
            advanceUntilIdle()
            // Should NOT filter -- trim() makes it empty, full list returned.
            assertThat(expectMostRecentItem()).hasSize(2)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `undoEvents forwards events emitted on the bus`() = runTest {
        every { itemRepo.observeAll() } returns itemsFlow
        val vm = ItemsListViewModel(itemRepo, undoBus)

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

        val vm = ItemsListViewModel(itemRepo, undoBus)
        vm.undoItemDelete("milk")
        advanceUntilIdle()

        coVerify(exactly = 1) { itemRepo.undoSoftDelete("milk") }
    }

    private fun row(id: String, name: String, brand: String? = null) = ItemWithCategoryAndStores(
        item = Item(
            id = id, name = name, categoryId = null, notes = null,
            quantity = null, isNeeded = true, lastPurchasedAt = null,
            userId = "u", createdAt = 1L, updatedAt = 1L, deletedAt = null,
            brand = brand,
        ),
        category = null,
        stores = emptyList(),
    )
}
