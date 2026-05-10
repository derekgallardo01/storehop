package com.storehop.app.ui.shop

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.storehop.app.data.db.relations.ItemWithCategoryAndStores
import com.storehop.app.data.db.relations.ShoppingRow
import com.storehop.app.data.entity.Item
import com.storehop.app.data.entity.Store
import com.storehop.app.data.prefs.UserPreferencesRepository
import com.storehop.app.data.repository.ItemRepository
import com.storehop.app.data.repository.ShoppingRepository
import com.storehop.app.data.repository.StoreRepository
import com.storehop.app.testing.MainDispatcherRule
import io.mockk.coVerify
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Pins the Shop-at-Store screen's behaviors that don't fall out of the
 * repository tests:
 *  - search query filters by name OR brand, case-insensitively
 *  - search filtering does NOT hide critical items from the banner
 *  - togglePurchased routes to the correct repo method based on isNeeded
 *  - undoPurchase threads the cascade snapshot back to the repo, and is a
 *    no-op when there's no snapshot in flight (safety guard against stale
 *    snackbar taps)
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
    private val showPurchasedFlow = MutableStateFlow(true)
    private val sortModeFlow = MutableStateFlow(com.storehop.app.data.prefs.SortMode.CATEGORY)
    private val prefsRepo: UserPreferencesRepository = mockk(relaxed = true) {
        every { showPurchased } returns showPurchasedFlow
        every { shopAtStoreSortMode } returns sortModeFlow
        coEvery { setShopAtStoreSortMode(any()) } answers {
            sortModeFlow.value = firstArg()
        }
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

    @Test fun `togglePurchased on a needed row routes to cascade markPurchasedAtStore`() = runTest {
        coEvery { shoppingRepo.shoppingListForStore(any(), any()) } returns rowsFlow
        coEvery { storeRepo.observeById(any()) } returns flowOf(testStore())
        coEvery { itemRepo.markPurchasedAtStore(any(), any()) } returns 1234L

        val vm = newVm()
        vm.togglePurchased(row("milk", "Milk", isNeeded = true))
        advanceUntilIdle()

        coVerify(exactly = 1) { itemRepo.markPurchasedAtStore("milk", "store_lidl") }
        coVerify(exactly = 0) { itemRepo.markNeededAtStore(any(), any()) }
    }

    @Test fun `togglePurchased on a purchased row flips it back to needed at THIS store only`() = runTest {
        coEvery { shoppingRepo.shoppingListForStore(any(), any()) } returns rowsFlow
        coEvery { storeRepo.observeById(any()) } returns flowOf(testStore())

        val vm = newVm()
        vm.togglePurchased(row("milk", "Milk", isNeeded = false))
        advanceUntilIdle()

        coVerify(exactly = 1) { itemRepo.markNeededAtStore("milk", "store_lidl") }
        coVerify(exactly = 0) { itemRepo.markPurchasedAtStore(any(), any()) }
    }

    @Test fun `undoPurchase routes to repository undoPurchase with the snapshot from the most recent cascade`() = runTest {
        coEvery { shoppingRepo.shoppingListForStore(any(), any()) } returns rowsFlow
        coEvery { storeRepo.observeById(any()) } returns flowOf(testStore())
        coEvery { itemRepo.markPurchasedAtStore("milk", "store_lidl") } returns 9_999L

        val vm = newVm()
        vm.togglePurchased(row("milk", "Milk", isNeeded = true))
        advanceUntilIdle()
        vm.undoPurchase("milk")
        advanceUntilIdle()

        coVerify(exactly = 1) { itemRepo.undoPurchase("milk", 9_999L) }
    }

    @Test fun `undoPurchase is a no-op when no snapshot is in flight`() = runTest {
        coEvery { shoppingRepo.shoppingListForStore(any(), any()) } returns rowsFlow
        coEvery { storeRepo.observeById(any()) } returns flowOf(testStore())

        val vm = newVm()
        // No prior togglePurchased — snapshot is null.
        vm.undoPurchase("milk")
        advanceUntilIdle()

        coVerify(exactly = 0) { itemRepo.undoPurchase(any(), any()) }
    }

    @Test fun `showPurchased=false hides every checked-off row regardless of staple status`() = runTest {
        rowsFlow.value = listOf(
            row("milk", "Milk", isNeeded = true),                     // needed -> visible
            row("rice", "Rice", isNeeded = true, isStaple = true),    // needed staple -> visible
            row("bread", "Bread", isNeeded = false),                  // purchased non-staple -> hidden
            row("eggs", "Eggs", isNeeded = false, isStaple = true),   // purchased staple -> hidden
        )
        coEvery { shoppingRepo.shoppingListForStore(any(), any()) } returns rowsFlow
        coEvery { storeRepo.observeById(any()) } returns flowOf(testStore())
        showPurchasedFlow.value = false

        val vm = newVm()
        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val visibleNames = expectMostRecentItem().rowsByCategory
                .flatMap { it.rows }.map { it.itemName }
            assertThat(visibleNames).containsExactly("Milk", "Rice")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `criticalNames is unaffected by the visibility toggle`() = runTest {
        // The critical-needs banner is sourced from the unfiltered rows, so
        // hiding checked-off items must never drop a critical item from the
        // banner.
        rowsFlow.value = listOf(
            row("milk", "Milk", isPriority = true, isNeeded = true),
            row("bread", "Bread", isNeeded = false),
        )
        coEvery { shoppingRepo.shoppingListForStore(any(), any()) } returns rowsFlow
        coEvery { storeRepo.observeById(any()) } returns flowOf(testStore())
        showPurchasedFlow.value = false

        val vm = newVm()
        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertThat(state.criticalNames).containsExactly("Milk")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `setSortMode ALPHABETIC produces a flat case-insensitive list and clears category groups`() = runTest {
        rowsFlow.value = listOf(
            row("milk", "milk", isNeeded = true),
            row("apples", "Apples", isNeeded = true),
            row("bread", "BREAD", isNeeded = true),
        )
        coEvery { shoppingRepo.shoppingListForStore(any(), any()) } returns rowsFlow
        coEvery { storeRepo.observeById(any()) } returns flowOf(testStore())

        val vm = newVm()
        vm.setSortMode(com.storehop.app.data.prefs.SortMode.ALPHABETIC)
        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertThat(state.sortMode).isEqualTo(com.storehop.app.data.prefs.SortMode.ALPHABETIC)
            assertThat(state.rowsByCategory).isEmpty()
            // lowercase("BREAD") < lowercase("milk") so order is Apples, BREAD, milk.
            assertThat(state.rowsAlphabetic.map { it.itemName })
                .containsExactly("Apples", "BREAD", "milk").inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `setSortMode CATEGORY restores the grouped list and clears alphabetic`() = runTest {
        rowsFlow.value = listOf(
            row("milk", "Milk", isNeeded = true),
            row("apples", "Apples", isNeeded = true),
        )
        coEvery { shoppingRepo.shoppingListForStore(any(), any()) } returns rowsFlow
        coEvery { storeRepo.observeById(any()) } returns flowOf(testStore())

        val vm = newVm()
        // Flip to ALPHABETIC first, then back, to prove the toggle round-trips.
        vm.setSortMode(com.storehop.app.data.prefs.SortMode.ALPHABETIC)
        advanceUntilIdle()
        vm.setSortMode(com.storehop.app.data.prefs.SortMode.CATEGORY)
        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertThat(state.sortMode).isEqualTo(com.storehop.app.data.prefs.SortMode.CATEGORY)
            assertThat(state.rowsAlphabetic).isEmpty()
            assertThat(state.rowsByCategory).isNotEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `submitQuickAddText is a no-op on whitespace input`() = runTest {
        coEvery { shoppingRepo.shoppingListForStore(any(), any()) } returns rowsFlow
        coEvery { storeRepo.observeById(any()) } returns flowOf(testStore())

        val vm = newVm()
        vm.setQuickAddInput("   ")
        vm.submitQuickAddText()
        advanceUntilIdle()

        coVerify(exactly = 0) { itemRepo.addItemFromQuickAdd(any(), any()) }
    }

    @Test fun `submitQuickAddText routes to addItemFromQuickAdd with trimmed name`() = runTest {
        coEvery { shoppingRepo.shoppingListForStore(any(), any()) } returns rowsFlow
        coEvery { storeRepo.observeById(any()) } returns flowOf(testStore())
        coEvery { itemRepo.addItemFromQuickAdd(any(), any()) } returns "new_id"

        val vm = newVm()
        vm.setQuickAddInput("  Yogurt  ")
        vm.submitQuickAddText()
        advanceUntilIdle()

        coVerify(exactly = 1) { itemRepo.addItemFromQuickAdd("Yogurt", "store_lidl") }
        // Input should be cleared after a successful submit so the field is
        // ready for the next entry.
        assertThat(vm.quickAddInput.value).isEmpty()
    }

    @Test fun `pickExistingItem routes to tagItemToStore`() = runTest {
        coEvery { shoppingRepo.shoppingListForStore(any(), any()) } returns rowsFlow
        coEvery { storeRepo.observeById(any()) } returns flowOf(testStore())

        val vm = newVm()
        vm.setQuickAddInput("mil")
        vm.pickExistingItem("milk")
        advanceUntilIdle()

        coVerify(exactly = 1) { itemRepo.tagItemToStore("milk", "store_lidl") }
        assertThat(vm.quickAddInput.value).isEmpty()
    }

    @Test fun `quickAddSuggestions empty when input is empty even if staples exist`() = runTest {
        // The bar stays unobtrusive until the user starts typing. Empty
        // input always yields empty suggestions, regardless of how many
        // staples or items exist in the master library.
        val masterLibrary = listOf(
            itemRow("eggs", "Eggs", isStaple = true),
            itemRow("bread", "Bread", isStaple = true),
            itemRow("popcorn", "Popcorn", isStaple = false),
        )
        rowsFlow.value = emptyList()
        coEvery { shoppingRepo.shoppingListForStore(any(), any()) } returns rowsFlow
        coEvery { storeRepo.observeById(any()) } returns flowOf(testStore())
        coEvery { itemRepo.observeAll() } returns flowOf(masterLibrary)

        // StateFlow seed is already empty and stays empty, so there's no
        // post-collection emission for Turbine to await -- read .value after
        // letting coroutines run instead.
        val vm = newVm()
        // Cold-collect briefly so the upstream combine actually evaluates.
        vm.quickAddSuggestions.test {
            awaitItem()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(vm.quickAddSuggestions.value).isEmpty()
    }

    @Test fun `quickAddSuggestions filters by name substring case-insensitively when input is non-empty`() = runTest {
        val masterLibrary = listOf(
            itemRow("milk", "Milk"),
            itemRow("almond_milk", "Almond Milk"),
            itemRow("eggs", "Eggs"),
        )
        rowsFlow.value = emptyList()
        coEvery { shoppingRepo.shoppingListForStore(any(), any()) } returns rowsFlow
        coEvery { storeRepo.observeById(any()) } returns flowOf(testStore())
        coEvery { itemRepo.observeAll() } returns flowOf(masterLibrary)

        val vm = newVm()
        vm.setQuickAddInput("MIL")
        vm.quickAddSuggestions.test {
            awaitItem()
            advanceUntilIdle()
            val names = expectMostRecentItem().map { it.name }
            // Both milks match (case-insensitive substring); prefix-on-name
            // ranks "Milk" before "Almond Milk".
            assertThat(names).containsExactly("Milk", "Almond Milk").inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `quickAddSuggestions matches brand too`() = runTest {
        val masterLibrary = listOf(
            itemRow("milk_mimosa", "Milk", brand = "Mimosa"),
            itemRow("oat_drink", "Oat Drink", brand = null),
        )
        rowsFlow.value = emptyList()
        coEvery { shoppingRepo.shoppingListForStore(any(), any()) } returns rowsFlow
        coEvery { storeRepo.observeById(any()) } returns flowOf(testStore())
        coEvery { itemRepo.observeAll() } returns flowOf(masterLibrary)

        val vm = newVm()
        vm.setQuickAddInput("mim")
        vm.quickAddSuggestions.test {
            awaitItem()
            advanceUntilIdle()
            val names = expectMostRecentItem().map { it.name }
            assertThat(names).containsExactly("Milk")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `quickAddSuggestions excludes items already needed at this store`() = runTest {
        val masterLibrary = listOf(
            itemRow("milk", "Milk"),
            itemRow("almond_milk", "Almond Milk"),
        )
        // "milk" is already needed at this store -- shouldn't appear even if
        // it matches the typed substring.
        rowsFlow.value = listOf(row("milk", "Milk", isNeeded = true))
        coEvery { shoppingRepo.shoppingListForStore(any(), any()) } returns rowsFlow
        coEvery { storeRepo.observeById(any()) } returns flowOf(testStore())
        coEvery { itemRepo.observeAll() } returns flowOf(masterLibrary)

        val vm = newVm()
        vm.setQuickAddInput("mil")
        vm.quickAddSuggestions.test {
            awaitItem()
            advanceUntilIdle()
            val names = expectMostRecentItem().map { it.name }
            assertThat(names).containsExactly("Almond Milk")
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun newVm() = ShopAtStoreViewModel(
        shoppingRepository = shoppingRepo,
        itemRepository = itemRepo,
        preferencesRepository = prefsRepo,
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
        isStaple: Boolean = false,
    ) = ShoppingRow(
        itemId = id,
        itemName = name,
        quantity = null,
        notes = null,
        isNeeded = isNeeded,
        brand = brand,
        imageUrl = null,
        isPriority = isPriority,
        isStaple = isStaple,
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

    private fun itemRow(
        id: String,
        name: String,
        brand: String? = null,
        isStaple: Boolean = false,
    ) = ItemWithCategoryAndStores(
        item = Item(
            id = id,
            name = name,
            categoryId = null,
            notes = null,
            quantity = null,
            isNeeded = true,
            lastPurchasedAt = null,
            userId = "u",
            createdAt = 1L,
            updatedAt = 1L,
            deletedAt = null,
            brand = brand,
            imageUrl = null,
            isStaple = isStaple,
            isPriority = false,
        ),
        category = null,
        stores = emptyList(),
    )
}
