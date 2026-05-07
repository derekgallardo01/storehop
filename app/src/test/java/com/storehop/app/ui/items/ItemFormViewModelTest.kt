package com.storehop.app.ui.items

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.storehop.app.data.db.relations.ItemWithCategoryAndStores
import com.storehop.app.data.entity.Category
import com.storehop.app.data.entity.Item
import com.storehop.app.data.entity.Store
import com.storehop.app.data.repository.CategoryRepository
import com.storehop.app.data.repository.ItemRepository
import com.storehop.app.data.repository.StoreRepository
import com.storehop.app.data.storage.ImageUploader
import com.storehop.app.testing.MainDispatcherRule
import com.storehop.app.ui.util.UndoEvent
import com.storehop.app.ui.util.UndoEventBus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Coverage for the Add / Edit form behavior:
 *  - blank-name validation gates submit before the repo is touched
 *  - Add mode -> addItem; Edit mode -> updateItem
 *  - imageUrl is patched in by a follow-up updateItem when the user staged a
 *    local pick (the two-step save path)
 *  - delete fires UndoEvent.ItemDeleted onto the bus
 *  - existing-row load populates form state in Edit mode
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ItemFormViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val itemRepo: ItemRepository = mockk(relaxed = true)
    private val imageUploader: ImageUploader = mockk()
    private val undoBus = UndoEventBus()
    private val categoryRepo: CategoryRepository = mockk {
        coEvery { observeAll(any()) } returns flowOf(emptyList())
    }
    private val storeRepo: StoreRepository = mockk {
        coEvery { observeAll(any()) } returns flowOf(emptyList())
    }

    private fun newAddVm() = ItemFormViewModel(
        itemRepository = itemRepo,
        imageUploader = imageUploader,
        undoBus = undoBus,
        categoryRepository = categoryRepo,
        storeRepository = storeRepo,
        savedStateHandle = SavedStateHandle(),
    )

    private fun newEditVm(itemId: String, existing: ItemWithCategoryAndStores?) = run {
        coEvery { itemRepo.observeById(itemId) } returns flowOf(existing)
        ItemFormViewModel(
            itemRepository = itemRepo,
            imageUploader = imageUploader,
            undoBus = undoBus,
            categoryRepository = categoryRepo,
            storeRepository = storeRepo,
            savedStateHandle = SavedStateHandle(mapOf("itemId" to itemId)),
        )
    }

    @Test fun `submit with blank name flips nameError and skips the repo`() = runTest {
        val vm = newAddVm()
        vm.setName("   ")
        vm.submit()
        advanceUntilIdle()

        assertThat(vm.state.value.nameError).isTrue()
        assertThat(vm.state.value.saved).isFalse()
        coVerify(exactly = 0) {
            itemRepo.addItem(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test fun `Add mode submit calls addItem and marks saved=true`() = runTest {
        coEvery {
            itemRepo.addItem(
                name = "Milk", categoryId = "cat", storeIds = setOf("s1"),
                quantity = null, notes = null, isNeeded = true,
                brand = "Mimosa", imageUrl = null, isStaple = true, isPriority = false,
            )
        } returns "new-id"

        val vm = newAddVm()
        vm.setName("Milk")
        vm.setBrand("Mimosa")
        vm.setCategoryId("cat")
        vm.toggleStore("s1")
        vm.setStaple(true)
        vm.submit()
        advanceUntilIdle()

        assertThat(vm.state.value.saved).isTrue()
        assertThat(vm.state.value.isSubmitting).isFalse()
        coVerify(exactly = 1) {
            itemRepo.addItem(
                name = "Milk", categoryId = "cat", storeIds = setOf("s1"),
                quantity = null, notes = null, isNeeded = true,
                brand = "Mimosa", imageUrl = null, isStaple = true, isPriority = false,
            )
        }
    }

    @Test fun `Edit mode loads the existing row into form state`() = runTest {
        val existing = ItemWithCategoryAndStores(
            item = Item(
                id = "id", name = "Milk", categoryId = "cat", notes = null,
                quantity = null, isNeeded = true, lastPurchasedAt = null,
                userId = "u", createdAt = 1L, updatedAt = 1L, deletedAt = null,
                brand = "Mimosa", imageUrl = "https://img/old.jpg",
                isStaple = true, isPriority = true,
            ),
            category = null,
            stores = listOf(
                Store(
                    id = "s1", name = "Lidl", colorArgb = null,
                    isArchived = false, isSeeded = true, userId = "u",
                    createdAt = 1L, updatedAt = 1L, deletedAt = null,
                ),
            ),
        )

        val vm = newEditVm("id", existing)
        advanceUntilIdle()

        val s = vm.state.value
        assertThat(s.name).isEqualTo("Milk")
        assertThat(s.brand).isEqualTo("Mimosa")
        assertThat(s.categoryId).isEqualTo("cat")
        assertThat(s.storeIds).containsExactly("s1")
        assertThat(s.isStaple).isTrue()
        assertThat(s.isPriority).isTrue()
        assertThat(s.imageUrl).isEqualTo("https://img/old.jpg")
        assertThat(s.isLoading).isFalse()
    }

    @Test fun `Edit mode with deleted upstream row surfaces a load error`() = runTest {
        val vm = newEditVm("missing", existing = null)
        advanceUntilIdle()

        assertThat(vm.state.value.isLoading).isFalse()
        assertThat(vm.state.value.saveError).isEqualTo("Item not found")
    }

    @Test fun `submit with a staged local image runs upload and patches imageUrl`() = runTest {
        coEvery {
            itemRepo.addItem(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns "new-id"
        val uri: Uri = mockk()
        coEvery { imageUploader.upload(uri, "new-id") } returns "https://img/new.jpg"

        val vm = newAddVm()
        vm.setName("Milk")
        vm.pickLocalImage(uri)
        vm.submit()
        advanceUntilIdle()

        // Two updates: addItem (initial save, no URL yet) and updateItem
        // (imageUrl patch). The two-step path keeps save-the-row resilient
        // when the upload itself is slow or blips.
        coVerify(exactly = 1) {
            imageUploader.upload(uri, "new-id")
        }
        coVerify(exactly = 1) {
            itemRepo.updateItem(
                id = "new-id", name = "Milk", categoryId = null, storeIds = emptySet(),
                quantity = null, notes = null, brand = null,
                imageUrl = "https://img/new.jpg", isStaple = false, isPriority = false,
            )
        }
        assertThat(vm.state.value.saved).isTrue()
    }

    @Test fun `delete soft-deletes the item and emits an UndoEvent`() = runTest {
        val existing = ItemWithCategoryAndStores(
            item = Item(
                id = "id", name = "Milk", categoryId = null, notes = null,
                quantity = null, isNeeded = true, lastPurchasedAt = null,
                userId = "u", createdAt = 1L, updatedAt = 1L, deletedAt = null,
            ),
            category = null,
            stores = emptyList(),
        )

        val vm = newEditVm("id", existing)
        advanceUntilIdle()

        undoBus.events.test {
            vm.delete()
            advanceUntilIdle()

            val event = awaitItem()
            assertThat(event).isInstanceOf(UndoEvent.ItemDeleted::class.java)
            event as UndoEvent.ItemDeleted
            assertThat(event.itemId).isEqualTo("id")
            assertThat(event.itemName).isEqualTo("Milk")

            assertThat(vm.state.value.deleted).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { itemRepo.softDelete("id") }
    }

    @Test fun `submit catches IllegalArgumentException from the repo as a save error`() = runTest {
        coEvery {
            itemRepo.addItem(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } throws IllegalArgumentException("name too long")

        val vm = newAddVm()
        vm.setName("Milk")
        vm.submit()
        advanceUntilIdle()

        assertThat(vm.state.value.isSubmitting).isFalse()
        assertThat(vm.state.value.saveError).isEqualTo("name too long")
        assertThat(vm.state.value.saved).isFalse()
    }
}
