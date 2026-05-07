package com.storehop.app.ui.shop

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.storehop.app.data.db.relations.ShoppingRow
import com.storehop.app.data.entity.Store
import com.storehop.app.data.repository.ItemRepository
import com.storehop.app.data.repository.ShoppingRepository
import com.storehop.app.data.repository.StoreRepository
import com.storehop.app.testing.MainDispatcherRule
import io.mockk.coVerify
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Pins the Shop-at-Store screen's three behaviors that don't fall out of the
 * repository tests:
 *  - search query filters by name OR brand, case-insensitively
 *  - search filtering does NOT hide critical items from the banner
 *  - togglePurchased routes to the correct repo method based on isNeeded
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShopAtStoreViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val shoppingRepo: ShoppingRepository = mockk()
    private val itemRepo: ItemRepository = mockk(relaxed = true)
    private val storeRepo: StoreRepository = mockk()
    private val sessionTracker: ShoppingSessionTracker = mockk {
        coEvery { sessionStartMs() } returns 1_000L
    }

    private val rowsFlow = MutableStateFlow<List<ShoppingRow>>(emptyList())

    @Test fun `query filters rows by name and brand, case insensitive`() = runTest {
        val rows = listOf(
            row("milk", "Milk", brand = "Mimosa"),
            row("eggs", "Eggs", brand = null),
            row("oat", "Oat Drink", brand = null),
        )
        rowsFlow.value = rows
        coEvery { shoppingRepo.shoppingListForStore("store_lidl", 1_000L) } returns rowsFlow
        coEvery { storeRepo.observeById("store_lidl") } returns flowOf(testStore())

        val vm = newVm()

        vm.uiState.test {
            // Initial empty filter -> all three rows.
            awaitItem() // initial state with empty rowsByCategory
            advanceUntilIdle()
            assertThat(awaitItem().rowsByCategory.flatMap { it.rows }.map { it.itemName })
                .containsExactly("Milk", "Eggs", "Oat Drink")

            // Search "mim" -> matches Mimosa brand only.
            vm.setQuery("mim")
            advanceUntilIdle()
            val mimResults = awaitItem().rowsByCategory.flatMap { it.rows }
            assertThat(mimResults.map { it.itemName }).containsExactly("Milk")

            // Search "OAT" upper-case -> still finds the item.
            vm.setQuery("OAT")
            advanceUntilIdle()
            val oatResults = awaitItem().rowsByCategory.flatMap { it.rows }
            assertThat(oatResults.map { it.itemName }).containsExactly("Oat Drink")

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `criticalNames stays populated even when search hides the row`() = runTest {
        // Critical banner is global to the store; users shouldn't lose
        // visibility of unmissable items just because they searched for
        // something else.
        rowsFlow.value = listOf(
            row("milk", "Milk", isPriority = true),
            row("eggs", "Eggs"),
        )
        coEvery { shoppingRepo.shoppingListForStore(any(), any()) } returns rowsFlow
        coEvery { storeRepo.observeById(any()) } returns flowOf(testStore())

        val vm = newVm()
        vm.setQuery("eggs") // hides Milk from the list

        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val state = expectMostRecentItem()
            // Filtered list shows only Eggs.
            assertThat(state.rowsByCategory.flatMap { it.rows }.map { it.itemName })
                .containsExactly("Eggs")
            // Banner still includes Milk -- comes from the unfiltered rows.
            assertThat(state.criticalNames).containsExactly("Milk")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `togglePurchased on a needed row marks it purchased at this store only`() = runTest {
        coEvery { shoppingRepo.shoppingListForStore(any(), any()) } returns rowsFlow
        coEvery { storeRepo.observeById(any()) } returns flowOf(testStore())

        val vm = newVm()
        vm.togglePurchased(row("milk", "Milk", isNeeded = true))
        advanceUntilIdle()

        coVerify(exactly = 1) { itemRepo.markPurchasedAtStore("milk", "store_lidl") }
        coVerify(exactly = 0) { itemRepo.markNeededAtStore(any(), any()) }
    }

    @Test fun `togglePurchased on a purchased row flips it back to needed`() = runTest {
        coEvery { shoppingRepo.shoppingListForStore(any(), any()) } returns rowsFlow
        coEvery { storeRepo.observeById(any()) } returns flowOf(testStore())

        val vm = newVm()
        vm.togglePurchased(row("milk", "Milk", isNeeded = false))
        advanceUntilIdle()

        coVerify(exactly = 1) { itemRepo.markNeededAtStore("milk", "store_lidl") }
        coVerify(exactly = 0) { itemRepo.markPurchasedAtStore(any(), any()) }
    }

    @Test fun `quickAdd ignores blank input`() = runTest {
        coEvery { shoppingRepo.shoppingListForStore(any(), any()) } returns rowsFlow
        coEvery { storeRepo.observeById(any()) } returns flowOf(testStore())

        val vm = newVm()
        vm.quickAdd("   ")
        advanceUntilIdle()

        coVerify(exactly = 0) { itemRepo.addItem(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `quickAdd auto-tags the new item to the current store`() = runTest {
        coEvery { shoppingRepo.shoppingListForStore(any(), any()) } returns rowsFlow
        coEvery { storeRepo.observeById(any()) } returns flowOf(testStore())

        val vm = newVm()
        vm.quickAdd("  Yogurt  ")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            itemRepo.addItem(
                name = "Yogurt",
                categoryId = null,
                storeIds = setOf("store_lidl"),
                // Defaulted args we don't care about here:
                quantity = any(),
                notes = any(),
                isNeeded = any(),
                brand = any(),
                imageUrl = any(),
                isStaple = any(),
                isPriority = any(),
            )
        }
    }

    private fun newVm() = ShopAtStoreViewModel(
        shoppingRepository = shoppingRepo,
        itemRepository = itemRepo,
        sessionTracker = sessionTracker,
        storeRepository = storeRepo,
        savedStateHandle = SavedStateHandle(mapOf("storeId" to "store_lidl")),
    )

    private fun row(
        id: String,
        name: String,
        brand: String? = null,
        isNeeded: Boolean = true,
        isPriority: Boolean = false,
    ) = ShoppingRow(
        itemId = id,
        itemName = name,
        quantity = null,
        notes = null,
        isNeeded = isNeeded,
        brand = brand,
        imageUrl = null,
        isPriority = isPriority,
        isStaple = false,
        categoryId = "cat_dairy_eggs",
        categoryName = "Dairy & Eggs",
        categoryNameKey = "cat_dairy_eggs",
        categoryIcon = null,
        displayOrder = 0,
    )

    private fun testStore() = Store(
        id = "store_lidl", name = "Lidl", colorArgb = null,
        isArchived = false, isSeeded = true, userId = "u",
        createdAt = 1L, updatedAt = 1L, deletedAt = null,
    )
}
