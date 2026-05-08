package com.storehop.app.ui.shop

import android.content.Context
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.storehop.app.R
import com.storehop.app.data.entity.Store
import com.storehop.app.data.repository.ShoppingRepository
import com.storehop.app.data.repository.StorePickerRow
import com.storehop.app.data.repository.StoreRepository
import com.storehop.app.testing.MainDispatcherRule
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
 * Coverage for the picker VM's three responsibilities the repo tests don't
 * exercise:
 *  - composing the banner's CriticalBannerState (counts, top store, dedup)
 *  - addStore / renameStore localized validation paths
 *  - reorder + delete + undo plumbing into the repo
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StorePickerViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val context: Context = mockk {
        // Localized error strings for empty/duplicate/generic cases.
        every { getString(R.string.error_store_name_empty) } returns "ENAME_EMPTY"
        every { getString(R.string.error_store_name_duplicate, any()) } returns "ENAME_DUPE"
        every { getString(R.string.error_could_not_add_store) } returns "ECOULDNT_ADD"
        every { getString(R.string.error_could_not_rename_store) } returns "ECOULDNT_RENAME"
    }
    private val shoppingRepo: ShoppingRepository = mockk()
    private val storeRepo: StoreRepository = mockk(relaxed = true)
    private val sessionTracker: ShoppingSessionTracker = mockk {
        every { sessionStartMs() } returns 1_000L
    }

    private val rowsFlow = MutableStateFlow<List<StorePickerRow>>(emptyList())

    private fun newVm(): StorePickerViewModel {
        coEvery { shoppingRepo.observeStorePickerRows(1_000L) } returns rowsFlow
        return StorePickerViewModel(
            appContext = context,
            shoppingRepository = shoppingRepo,
            storeRepository = storeRepo,
            sessionTracker = sessionTracker,
        )
    }

    @Test fun `criticalSummary is null when no rows have criticals`() = runTest {
        rowsFlow.value = listOf(
            pickerRow("store_lidl", critical = emptyList()),
            pickerRow("store_aldi", critical = emptyList()),
        )

        val vm = newVm()

        vm.criticalSummary.test {
            // Initial value is null (no criticals); the mapped emission is also
            // null, so we just confirm the current value rather than awaiting a
            // duplicate.
            advanceUntilIdle()
            assertThat(expectMostRecentItem()).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `criticalSummary marks singleStore when only one row has criticals`() = runTest {
        rowsFlow.value = listOf(
            pickerRow("store_lidl", critical = listOf("Milk", "Eggs", "Bread")),
            pickerRow("store_aldi", critical = emptyList()),
        )

        val vm = newVm()

        vm.criticalSummary.test {
            advanceUntilIdle()
            val state = expectMostRecentItem()!!
            assertThat(state.singleStore).isTrue()
            assertThat(state.topStoreName).isEqualTo("store_lidl")
            assertThat(state.topStoreCount).isEqualTo(3)
            assertThat(state.totalCount).isEqualTo(3)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `criticalSummary picks the row with the most criticals as top store`() = runTest {
        rowsFlow.value = listOf(
            pickerRow("store_aldi", critical = listOf("Milk")),
            pickerRow("store_lidl", critical = listOf("Eggs", "Bread", "Cheese")),
            pickerRow("store_continente", critical = listOf("Pickles", "Yogurt")),
        )

        val vm = newVm()

        vm.criticalSummary.test {
            advanceUntilIdle()
            val state = expectMostRecentItem()!!
            assertThat(state.singleStore).isFalse()
            assertThat(state.topStoreName).isEqualTo("store_lidl")
            assertThat(state.topStoreCount).isEqualTo(3)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `criticalSummary breaks ties by row order (which is displayOrder)`() = runTest {
        // Both stores have 2 criticals; the row that appears first in the
        // upstream Flow wins -- since rows are sorted by displayOrder ASC,
        // this is the user's manual drag order.
        rowsFlow.value = listOf(
            pickerRow("store_pingo", critical = listOf("Milk", "Eggs")),
            pickerRow("store_aldi", critical = listOf("Bread", "Cheese")),
        )

        val vm = newVm()

        vm.criticalSummary.test {
            advanceUntilIdle()
            assertThat(expectMostRecentItem()!!.topStoreName).isEqualTo("store_pingo")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `criticalSummary totalCount dedupes items flagged at multiple stores`() = runTest {
        rowsFlow.value = listOf(
            pickerRow("store_lidl", critical = listOf("Milk", "Eggs")),
            pickerRow("store_aldi", critical = listOf("Milk", "Bread")), // Milk repeats
        )

        val vm = newVm()

        vm.criticalSummary.test {
            advanceUntilIdle()
            // Distinct items across all stores: Milk, Eggs, Bread = 3.
            assertThat(expectMostRecentItem()!!.totalCount).isEqualTo(3)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `criticalSummary byStore preserves displayOrder and skips empty rows`() = runTest {
        rowsFlow.value = listOf(
            pickerRow("store_pingo", critical = listOf("Milk")),
            pickerRow("store_aldi", critical = emptyList()),
            pickerRow("store_continente", critical = listOf("Bread", "Cheese")),
        )

        val vm = newVm()

        vm.criticalSummary.test {
            advanceUntilIdle()
            val byStore = expectMostRecentItem()!!.byStore
            assertThat(byStore).containsExactly(
                "store_pingo" to listOf("Milk"),
                "store_continente" to listOf("Bread", "Cheese"),
            ).inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `addStore returns localized empty-name error when blank`() = runTest {
        val vm = newVm()
        assertThat(vm.addStore("   ")).isEqualTo("ENAME_EMPTY")
        coVerify(exactly = 0) { storeRepo.addStore(any(), any()) }
    }

    @Test fun `addStore maps IllegalArgumentException to localized duplicate error`() = runTest {
        coEvery { storeRepo.addStore(name = "Lidl", colorArgb = null) } throws IllegalArgumentException("dup")
        val vm = newVm()
        assertThat(vm.addStore("Lidl")).isEqualTo("ENAME_DUPE")
    }

    @Test fun `addStore maps unexpected exceptions to a generic error`() = runTest {
        coEvery { storeRepo.addStore(name = "Lidl", colorArgb = null) } throws RuntimeException("io")
        val vm = newVm()
        assertThat(vm.addStore("Lidl")).isEqualTo("ECOULDNT_ADD")
    }

    @Test fun `addStore returns null on success`() = runTest {
        coEvery { storeRepo.addStore(name = "New Store", colorArgb = null) } returns "id"
        val vm = newVm()
        assertThat(vm.addStore("  New Store  ")).isNull() // trims, then calls
        coVerify(exactly = 1) { storeRepo.addStore(name = "New Store", colorArgb = null) }
    }

    @Test fun `renameStore rejects blank input before hitting the repo`() = runTest {
        val vm = newVm()
        assertThat(vm.renameStore("id", "")).isEqualTo("ENAME_EMPTY")
        coVerify(exactly = 0) { storeRepo.rename(any(), any()) }
    }

    @Test fun `renameStore returns null on success and trims the name`() = runTest {
        val vm = newVm()
        assertThat(vm.renameStore("id", "  Mercadona  ")).isNull()
        coVerify(exactly = 1) { storeRepo.rename("id", "Mercadona") }
    }

    @Test fun `renameStore maps IllegalArgumentException to localized duplicate error`() = runTest {
        coEvery { storeRepo.rename("id", "Aldi") } throws IllegalArgumentException("dup")
        val vm = newVm()
        assertThat(vm.renameStore("id", "Aldi")).isEqualTo("ENAME_DUPE")
    }

    @Test fun `renameStore maps unexpected exceptions to a generic error`() = runTest {
        coEvery { storeRepo.rename("id", "Aldi") } throws RuntimeException("io")
        val vm = newVm()
        assertThat(vm.renameStore("id", "Aldi")).isEqualTo("ECOULDNT_RENAME")
    }

    @Test fun `commitOrder forwards full ordered ids to the repo`() = runTest {
        val vm = newVm()
        vm.commitOrder(listOf("store_aldi", "store_lidl", "store_continente"))
        advanceUntilIdle()
        coVerify(exactly = 1) {
            storeRepo.reorderStores(listOf("store_aldi", "store_lidl", "store_continente"))
        }
    }

    @Test fun `deleteStore and undoDeleteStore are fire-and-forget repo plumbing`() = runTest {
        val vm = newVm()
        vm.deleteStore("store_x")
        vm.undoDeleteStore("store_x")
        advanceUntilIdle()
        coVerify(exactly = 1) { storeRepo.softDelete("store_x") }
        coVerify(exactly = 1) { storeRepo.undoSoftDelete("store_x") }
    }

    private fun pickerRow(
        storeId: String,
        critical: List<String>,
        neededCount: Int = 1,
    ) = StorePickerRow(
        store = Store(
            id = storeId, name = storeId, colorArgb = null,
            isArchived = false, isSeeded = true, userId = "u",
            createdAt = 1L, updatedAt = 1L, deletedAt = null,
        ),
        neededCount = neededCount,
        pickedUpInSessionCount = 0,
        criticalItemNames = critical,
    )
}
