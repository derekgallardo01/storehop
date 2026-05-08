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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
}
