package com.storehop.app.ui.shop

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.storehop.app.data.entity.Category
import com.storehop.app.data.entity.Store
import com.storehop.app.data.entity.StoreCategoryOrder
import com.storehop.app.data.repository.CategoryRepository
import com.storehop.app.data.repository.StoreCategoryOrderRepository
import com.storehop.app.data.repository.StoreRepository
import com.storehop.app.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Pin EditAisleOrder VM behavior:
 *  - orderedCategories joins SCO rows with Category rows, sorted by displayOrder
 *  - categories without an SCO row for this store don't appear
 *  - commitOrder forwards the new category-id list to the repo
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditAisleOrderViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val scoRepo: StoreCategoryOrderRepository = mockk(relaxed = true)
    private val storeRepo: StoreRepository = mockk()
    private val categoryRepo: CategoryRepository = mockk()

    private val scoFlow = MutableStateFlow<List<StoreCategoryOrder>>(emptyList())
    private val categoriesFlow = MutableStateFlow<List<Category>>(emptyList())

    private fun newVm(): EditAisleOrderViewModel {
        coEvery { scoRepo.observeForStore("store_lidl") } returns scoFlow
        coEvery { categoryRepo.observeAll(false) } returns categoriesFlow
        coEvery { storeRepo.observeById("store_lidl") } returns flowOf(testStore())
        return EditAisleOrderViewModel(
            storeCategoryOrderRepository = scoRepo,
            storeRepository = storeRepo,
            categoryRepository = categoryRepo,
            savedStateHandle = SavedStateHandle(mapOf("storeId" to "store_lidl")),
        )
    }

    @Test fun `orderedCategories sorts by displayOrder and joins on category names`() = runTest {
        scoFlow.value = listOf(
            sco("cat_produce", displayOrder = 2),
            sco("cat_dairy_eggs", displayOrder = 0),
            sco("cat_bakery", displayOrder = 1),
        )
        categoriesFlow.value = listOf(
            cat("cat_produce", "Produce"),
            cat("cat_dairy_eggs", "Dairy & Eggs"),
            cat("cat_bakery", "Bakery"),
        )

        val vm = newVm()
        vm.orderedCategories.test {
            awaitItem() // initial empty
            advanceUntilIdle()
            val rows = expectMostRecentItem()
            assertThat(rows.map { it.id }).containsExactly(
                "cat_dairy_eggs", "cat_bakery", "cat_produce",
            ).inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `orderedCategories filters categories without an SCO row for this store`() = runTest {
        scoFlow.value = listOf(sco("cat_produce", displayOrder = 0))
        // cat_alcohol exists but has no SCO row at this store -- it's an
        // acceptable v0.5 limitation; users add to a store's aisle via the
        // item form.
        categoriesFlow.value = listOf(
            cat("cat_produce", "Produce"),
            cat("cat_alcohol", "Alcohol"),
        )

        val vm = newVm()
        vm.orderedCategories.test {
            awaitItem()
            advanceUntilIdle()
            val rows = expectMostRecentItem()
            assertThat(rows.map { it.id }).containsExactly("cat_produce")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `orderedCategories drops SCO rows with no matching live Category`() = runTest {
        // Defensive: an SCO row pointing at a tombstoned category shouldn't
        // surface (the cascade should already prevent this; test pins the
        // VM-side guard).
        scoFlow.value = listOf(
            sco("cat_produce", displayOrder = 0),
            sco("cat_ghost", displayOrder = 1),
        )
        categoriesFlow.value = listOf(cat("cat_produce", "Produce"))

        val vm = newVm()
        vm.orderedCategories.test {
            awaitItem()
            advanceUntilIdle()
            assertThat(expectMostRecentItem().map { it.id }).containsExactly("cat_produce")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `commitOrder forwards the ordered list to the repo`() = runTest {
        val vm = newVm()
        vm.commitOrder(listOf("cat_dairy_eggs", "cat_produce", "cat_bakery"))
        advanceUntilIdle()

        coVerify(exactly = 1) {
            scoRepo.reorderCategoriesForStore(
                "store_lidl",
                listOf("cat_dairy_eggs", "cat_produce", "cat_bakery"),
            )
        }
    }

    private fun sco(categoryId: String, displayOrder: Int) = StoreCategoryOrder(
        storeId = "store_lidl", categoryId = categoryId, displayOrder = displayOrder,
        isSeeded = true, userId = "u",
        createdAt = 1L, updatedAt = 1L, deletedAt = null,
    )

    private fun cat(id: String, name: String) = Category(
        id = id, name = name, nameKey = id, icon = null,
        isArchived = false, isSeeded = true, userId = "u",
        createdAt = 1L, updatedAt = 1L, deletedAt = null,
    )

    private fun testStore() = Store(
        id = "store_lidl", name = "Lidl", colorArgb = null,
        isArchived = false, isSeeded = true, userId = "u",
        createdAt = 1L, updatedAt = 1L, deletedAt = null,
    )

    @Test fun `constructor throws IllegalStateException when storeId arg is missing`() = runTest {
        try {
            EditAisleOrderViewModel(
                storeCategoryOrderRepository = mockk(relaxed = true),
                storeRepository = mockk(relaxed = true) {
                    coEvery { observeById(any()) } returns flowOf(null)
                },
                categoryRepository = mockk(relaxed = true) {
                    coEvery { observeAll(any()) } returns flowOf(emptyList())
                },
                savedStateHandle = SavedStateHandle(),
            )
            org.junit.Assert.fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertThat(e.message).contains("storeId")
        }
    }
}
