package com.storehop.app.ui.items

import android.content.Context
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
import io.mockk.every
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
    private val categoryRepo: CategoryRepository = mockk(relaxed = true) {
        coEvery { observeAll(any()) } returns flowOf(emptyList())
    }
    private val storeRepo: StoreRepository = mockk {
        coEvery { observeAll(any()) } returns flowOf(emptyList())
    }
    // Returns generic placeholder strings for the addCategory error paths.
    // Tests assert null-vs-non-null on the result, not the message text.
    private val appContext: Context = mockk(relaxed = true) {
        every { getString(any<Int>()) } returns "error"
        every { getString(any<Int>(), *anyVararg<Any>()) } returns "error"
    }

    private fun newAddVm() = ItemFormViewModel(
        appContext = appContext,
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
            appContext = appContext,
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

    @Test fun `isDirty starts false in Add mode`() = runTest {
        val vm = newAddVm()
        advanceUntilIdle()
        assertThat(vm.isDirty.value).isFalse()
    }

    @Test fun `isDirty becomes true after typing into Add-mode form`() = runTest {
        val vm = newAddVm()
        advanceUntilIdle()
        vm.setName("M")
        advanceUntilIdle()
        assertThat(vm.isDirty.value).isTrue()
    }

    @Test fun `isDirty returns to false when an Add-mode edit is reverted`() = runTest {
        val vm = newAddVm()
        advanceUntilIdle()
        vm.setName("Milk")
        advanceUntilIdle()
        assertThat(vm.isDirty.value).isTrue()
        vm.setName("")  // back to the empty baseline
        advanceUntilIdle()
        assertThat(vm.isDirty.value).isFalse()
    }

    @Test fun `isDirty starts false in Edit mode after the item loads`() = runTest {
        val existing = ItemWithCategoryAndStores(
            item = Item(
                id = "id", name = "Milk", categoryId = "cat", notes = null,
                quantity = null, isNeeded = true, lastPurchasedAt = null,
                userId = "u", createdAt = 1L, updatedAt = 1L, deletedAt = null,
                brand = "Mimosa", imageUrl = null,
                isStaple = false, isPriority = false,
            ),
            category = null,
            stores = emptyList(),
        )
        val vm = newEditVm("id", existing)
        advanceUntilIdle()
        assertThat(vm.isDirty.value).isFalse()
    }

    @Test fun `isDirty becomes true after editing a loaded Edit-mode item`() = runTest {
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
        vm.setName("Skim Milk")
        advanceUntilIdle()
        assertThat(vm.isDirty.value).isTrue()
    }

    @Test fun `isDirty returns to false after a successful save resets the baseline`() = runTest {
        coEvery {
            itemRepo.addItem(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns "new-id"

        val vm = newAddVm()
        advanceUntilIdle()
        vm.setName("Milk")
        advanceUntilIdle()
        assertThat(vm.isDirty.value).isTrue()

        vm.submit()
        advanceUntilIdle()

        // The baseline snapshot resets to current state on successful save —
        // the user can keep editing without an immediate "discard?" prompt.
        assertThat(vm.isDirty.value).isFalse()
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

    // ---- v0.6.1: inline "+ New category" from the item edit screen --------

    @Test fun `addCategory on success calls repo and auto-selects the new id`() = runTest {
        coEvery { categoryRepo.addCategory(name = "Pet supplies") } returns "cat-new-1"

        val vm = newAddVm()
        val result = vm.addCategory("  Pet supplies  ")  // trim is the VM's job

        assertThat(result).isNull()
        assertThat(vm.state.value.categoryId).isEqualTo("cat-new-1")
        coVerify(exactly = 1) { categoryRepo.addCategory(name = "Pet supplies") }
    }

    @Test fun `addCategory with blank name returns the empty error and does not call the repo`() = runTest {
        val vm = newAddVm()
        val result = vm.addCategory("   ")

        assertThat(result).isNotNull()
        assertThat(vm.state.value.categoryId).isNull()
        coVerify(exactly = 0) { categoryRepo.addCategory(any(), any()) }
    }

    @Test fun `addCategory with duplicate name returns a non-null error and does not change selection`() = runTest {
        coEvery { categoryRepo.addCategory(name = "Produce") } throws
            IllegalArgumentException("Produce already exists")

        val vm = newAddVm()
        // Pre-select a sentinel so we can confirm the failure path doesn't
        // wipe the existing selection on the form.
        vm.setCategoryId("cat-existing")
        val result = vm.addCategory("Produce")

        assertThat(result).isNotNull()
        assertThat(vm.state.value.categoryId).isEqualTo("cat-existing")
    }

    @Test fun `addCategory catches generic Exception via the catch-all branch`() = runTest {
        coEvery { categoryRepo.addCategory(name = any()) } throws RuntimeException("disk")
        val vm = newAddVm()
        val result = vm.addCategory("Whatever")
        assertThat(result).isNotNull()
    }

    // ---- Setters + image staging -----------------------------------------

    @Test fun `setBrand setStaple setPriority all flow through into state`() = runTest {
        val vm = newAddVm()
        vm.setBrand("Sara Lee")
        vm.setStaple(true)
        vm.setPriority(true)
        assertThat(vm.state.value.brand).isEqualTo("Sara Lee")
        assertThat(vm.state.value.isStaple).isTrue()
        assertThat(vm.state.value.isPriority).isTrue()
    }

    @Test fun `toggleStore adds and then removes the same store`() = runTest {
        val vm = newAddVm()
        vm.toggleStore("store_lidl")
        assertThat(vm.state.value.storeIds).containsExactly("store_lidl")
        vm.toggleStore("store_lidl")
        assertThat(vm.state.value.storeIds).isEmpty()
    }

    @Test fun `pickLocalImage stages the uri and clearImage wipes both staged and persisted urls`() = runTest {
        val vm = newAddVm()
        val uri: Uri = mockk()
        vm.pickLocalImage(uri)
        assertThat(vm.state.value.localImageUri).isEqualTo(uri)
        vm.clearImage()
        assertThat(vm.state.value.localImageUri).isNull()
        assertThat(vm.state.value.imageUrl).isNull()
    }

    @Test fun `submit catches generic Exception (e_g_ image upload failure) as a soft saveError`() = runTest {
        // addItem succeeds, but the upload step throws. The row is saved;
        // the saveError surfaces but `saved` remains false because the
        // image patch couldn't land.
        coEvery {
            itemRepo.addItem(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns "new-id"
        val uri: Uri = mockk()
        coEvery { imageUploader.upload(uri, "new-id") } throws java.io.IOException("network blip")

        val vm = newAddVm()
        vm.setName("Milk")
        vm.pickLocalImage(uri)
        vm.submit()
        advanceUntilIdle()

        assertThat(vm.state.value.isSubmitting).isFalse()
        assertThat(vm.state.value.isUploadingImage).isFalse()
        assertThat(vm.state.value.saveError).isEqualTo("network blip")
    }

    @Test fun `delete catches Exception from softDelete and surfaces saveError without setting deleted=true`() = runTest {
        val existing = ItemWithCategoryAndStores(
            item = Item(
                id = "id", name = "Milk", categoryId = null, notes = null,
                quantity = null, isNeeded = true, lastPurchasedAt = null,
                userId = "u", createdAt = 1L, updatedAt = 1L, deletedAt = null,
            ),
            category = null,
            stores = emptyList(),
        )
        coEvery { itemRepo.softDelete("id") } throws RuntimeException("db locked")

        val vm = newEditVm("id", existing)
        advanceUntilIdle()

        vm.delete()
        advanceUntilIdle()

        assertThat(vm.state.value.deleted).isFalse()
        assertThat(vm.state.value.saveError).isEqualTo("db locked")
    }
}
