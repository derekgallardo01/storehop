package com.storehop.app.ui.categories

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.storehop.app.R
import com.storehop.app.data.entity.Category
import com.storehop.app.data.repository.CategoryRepository
import com.storehop.app.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Manage Categories VM. Same shape as StorePickerViewModelTest:
 *  - localized validation paths (empty / duplicate / generic)
 *  - delete / undo plumbing fires the right repo methods
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManageCategoriesViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val context: Context = mockk {
        every { getString(R.string.error_category_name_empty) } returns "ENAME_EMPTY"
        every { getString(R.string.error_category_name_duplicate, any()) } returns "ENAME_DUPE"
        every { getString(R.string.error_could_not_add_category) } returns "ECOULDNT_ADD"
        every { getString(R.string.error_could_not_rename_category) } returns "ECOULDNT_RENAME"
    }
    private val categoryRepo: CategoryRepository = mockk(relaxed = true)
    private val categoriesFlow = MutableStateFlow<List<Category>>(emptyList())

    private fun newVm(): ManageCategoriesViewModel {
        coEvery { categoryRepo.observeAll(false) } returns categoriesFlow
        return ManageCategoriesViewModel(context, categoryRepo)
    }

    @Test fun `addCategory returns localized empty-name error when blank`() = runTest {
        val vm = newVm()
        assertThat(vm.addCategory("   ")).isEqualTo("ENAME_EMPTY")
        coVerify(exactly = 0) { categoryRepo.addCategory(any(), any()) }
    }

    @Test fun `addCategory maps IllegalArgumentException to localized duplicate error`() = runTest {
        coEvery { categoryRepo.addCategory(name = "Bakery", icon = null) } throws IllegalArgumentException("dup")
        val vm = newVm()
        assertThat(vm.addCategory("Bakery")).isEqualTo("ENAME_DUPE")
    }

    @Test fun `addCategory maps unexpected exceptions to a generic error`() = runTest {
        coEvery { categoryRepo.addCategory(name = "Bakery", icon = null) } throws RuntimeException("io")
        val vm = newVm()
        assertThat(vm.addCategory("Bakery")).isEqualTo("ECOULDNT_ADD")
    }

    @Test fun `addCategory returns null on success and trims the name`() = runTest {
        coEvery { categoryRepo.addCategory(name = "Frozen Treats", icon = null) } returns "id"
        val vm = newVm()
        assertThat(vm.addCategory("  Frozen Treats  ")).isNull()
        coVerify(exactly = 1) { categoryRepo.addCategory(name = "Frozen Treats", icon = null) }
    }

    @Test fun `renameCategory rejects blank input before hitting the repo`() = runTest {
        val vm = newVm()
        assertThat(vm.renameCategory("id", "")).isEqualTo("ENAME_EMPTY")
        coVerify(exactly = 0) { categoryRepo.rename(any(), any()) }
    }

    @Test fun `renameCategory returns null on success and trims the name`() = runTest {
        val vm = newVm()
        assertThat(vm.renameCategory("id", "  Bakery  ")).isNull()
        coVerify(exactly = 1) { categoryRepo.rename("id", "Bakery") }
    }

    @Test fun `renameCategory maps IllegalArgumentException to localized duplicate error`() = runTest {
        coEvery { categoryRepo.rename("id", "Bakery") } throws IllegalArgumentException("dup")
        val vm = newVm()
        assertThat(vm.renameCategory("id", "Bakery")).isEqualTo("ENAME_DUPE")
    }

    @Test fun `renameCategory maps unexpected exceptions to a generic error`() = runTest {
        coEvery { categoryRepo.rename("id", "Bakery") } throws RuntimeException("io")
        val vm = newVm()
        assertThat(vm.renameCategory("id", "Bakery")).isEqualTo("ECOULDNT_RENAME")
    }

    @Test fun `deleteCategory and undoDeleteCategory plumb to the repo`() = runTest {
        val vm = newVm()
        vm.deleteCategory("cat_x")
        vm.undoDeleteCategory("cat_x")
        advanceUntilIdle()
        coVerify(exactly = 1) { categoryRepo.softDelete("cat_x") }
        coVerify(exactly = 1) { categoryRepo.undoSoftDelete("cat_x") }
    }

    // ---- v0.6.4: selection mode + bulk delete + reorder + multi-add ----

    @Test fun `enterSelection flips to selection mode with that id selected`() = runTest {
        val vm = newVm()
        vm.enterSelection("cat_a")
        assertThat(vm.selectionMode.value).isTrue()
        assertThat(vm.selectedIds.value).containsExactly("cat_a")
    }

    @Test fun `toggleSelection adds and removes ids and auto-exits when set empties`() = runTest {
        val vm = newVm()
        vm.enterSelection("cat_a")
        vm.toggleSelection("cat_b")
        assertThat(vm.selectedIds.value).containsExactly("cat_a", "cat_b")
        // Removing cat_a still leaves cat_b -- stays in selection mode.
        vm.toggleSelection("cat_a")
        assertThat(vm.selectedIds.value).containsExactly("cat_b")
        assertThat(vm.selectionMode.value).isTrue()
        // Removing the last one auto-exits.
        vm.toggleSelection("cat_b")
        assertThat(vm.selectedIds.value).isEmpty()
        assertThat(vm.selectionMode.value).isFalse()
    }

    @Test fun `selectAll selects every visible category id`() = runTest {
        categoriesFlow.value = listOf(
            cat("c1", "A"), cat("c2", "B"), cat("c3", "C"),
        )
        val vm = newVm()
        // Subscribe so the WhileSubscribed-policy `categories` flow
        // collects from `categoriesFlow` before selectAll() reads
        // `categories.value`.
        val subscribed = launch(start = CoroutineStart.UNDISPATCHED) {
            vm.categories.collect {}
        }
        advanceUntilIdle()

        vm.enterSelection("c1")
        vm.selectAll()
        assertThat(vm.selectedIds.value).containsExactly("c1", "c2", "c3")
        subscribed.cancel()
    }

    @Test fun `cancelSelection wipes the set + exits selection mode`() = runTest {
        val vm = newVm()
        vm.enterSelection("c1")
        vm.toggleSelection("c2")
        vm.cancelSelection()
        assertThat(vm.selectionMode.value).isFalse()
        assertThat(vm.selectedIds.value).isEmpty()
    }

    @Test fun `deleteSelected routes to softDeleteMany and exits selection mode`() = runTest {
        coEvery { categoryRepo.softDeleteMany(any()) } returns 42_000L
        val vm = newVm()
        vm.enterSelection("c1")
        vm.toggleSelection("c2")

        val deletedAt = vm.deleteSelected()

        assertThat(deletedAt).isEqualTo(42_000L)
        coVerify(exactly = 1) {
            categoryRepo.softDeleteMany(
                match { ids: List<String> -> ids.toSet() == setOf("c1", "c2") },
            )
        }
        assertThat(vm.selectionMode.value).isFalse()
        assertThat(vm.selectedIds.value).isEmpty()
    }

    @Test fun `deleteSelected with no selection returns null and skips the repo`() = runTest {
        val vm = newVm()
        val result = vm.deleteSelected()
        assertThat(result).isNull()
        coVerify(exactly = 0) { categoryRepo.softDeleteMany(any()) }
    }

    @Test fun `commitReorder plumbs the new id sequence to the repo`() = runTest {
        val vm = newVm()
        vm.commitReorder(listOf("c3", "c1", "c2"))
        advanceUntilIdle()
        coVerify(exactly = 1) { categoryRepo.reorder(listOf("c3", "c1", "c2")) }
    }

    @Test fun `undoDeleteMany plumbs the batch deletedAt to the repo`() = runTest {
        val vm = newVm()
        vm.undoDeleteMany(99_000L)
        advanceUntilIdle()
        coVerify(exactly = 1) { categoryRepo.undoSoftDeleteMany(99_000L) }
    }

    @Test fun `addManyCategories splits on newlines, trims, dedupes, and counts added vs duplicates`() = runTest {
        coEvery { categoryRepo.addCategory(name = "Pets") } returns "id1"
        coEvery { categoryRepo.addCategory(name = "Bakery") } throws
            IllegalArgumentException("Bakery exists")
        coEvery { categoryRepo.addCategory(name = "Snacks") } returns "id2"
        val vm = newVm()

        val result = vm.addManyCategories(
            // Whitespace lines drop; case-insensitive duplicates within input
            // are deduped (Pets / pets).
            "\n  Pets  \nBakery\nSnacks\nPETS\n",
        )

        assertThat(result.added).isEqualTo(2)         // Pets + Snacks
        assertThat(result.duplicates).isEqualTo(1)    // Bakery
        assertThat(result.errors).isEmpty()
        coVerify(exactly = 1) { categoryRepo.addCategory(name = "Pets") }
        coVerify(exactly = 1) { categoryRepo.addCategory(name = "Bakery") }
        coVerify(exactly = 1) { categoryRepo.addCategory(name = "Snacks") }
        coVerify(exactly = 0) { categoryRepo.addCategory(name = "PETS") }
    }

    @Test fun `addManyCategories on empty input returns the localized empty-name error`() = runTest {
        val vm = newVm()
        val result = vm.addManyCategories("   \n\n   ")
        assertThat(result.added).isEqualTo(0)
        assertThat(result.errors).containsExactly("ENAME_EMPTY")
    }

    private fun cat(id: String, name: String) = Category(
        id = id, name = name, nameKey = null, icon = null,
        isArchived = false, isSeeded = false, userId = "u",
        createdAt = 1L, updatedAt = 1L, deletedAt = null,
    )
}
