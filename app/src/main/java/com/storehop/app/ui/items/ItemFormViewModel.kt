package com.storehop.app.ui.items

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storehop.app.R
import com.storehop.app.data.entity.Category
import com.storehop.app.data.entity.Store
import com.storehop.app.data.repository.CategoryRepository
import com.storehop.app.data.repository.ItemRepository
import com.storehop.app.data.repository.StoreRepository
import com.storehop.app.data.storage.ImageUploader
import com.storehop.app.ui.util.UndoEvent
import com.storehop.app.ui.util.UndoEventBus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State for the Add / Edit Item form. The same ViewModel backs both screens.
 *
 * Add mode: route argument `itemId` is null. The form starts empty.
 * Edit mode: `itemId` is the existing item's id; on first construction the
 * ViewModel loads the row + its tagged stores into the form state. Saving
 * calls `updateItem`; the `Delete` action calls `softDelete`.
 */
data class ItemFormState(
    val name: String = "",
    val brand: String = "",
    val categoryId: String? = null,
    val storeIds: Set<String> = emptySet(),
    val isStaple: Boolean = false,
    val isPriority: Boolean = false,
    /** Already-uploaded image URL from a prior save. */
    val imageUrl: String? = null,
    /** A local URI the user just picked but hasn't uploaded yet. */
    val localImageUri: Uri? = null,
    /** True while the upload kicked off by submit() is in flight. */
    val isUploadingImage: Boolean = false,
    val isLoading: Boolean = false,
    val isSubmitting: Boolean = false,
    /** True when the user has tried to save without typing a name. Screen
     *  resolves this to a localized error string -- the VM doesn't hold UI
     *  copy. */
    val nameError: Boolean = false,
    val saveError: String? = null,
    val saved: Boolean = false,
    val deleted: Boolean = false,
)

@HiltViewModel
class ItemFormViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val itemRepository: ItemRepository,
    private val imageUploader: ImageUploader,
    private val undoBus: UndoEventBus,
    private val categoryRepository: CategoryRepository,
    storeRepository: StoreRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val itemId: String? = savedStateHandle.get<String>("itemId")
    val isEdit: Boolean = itemId != null

    private val _state = MutableStateFlow(ItemFormState(isLoading = isEdit))
    val state: StateFlow<ItemFormState> = _state.asStateFlow()

    /**
     * Snapshot of the user-input fields at "no edits since last save" — used to
     * derive [isDirty] for the discard-changes prompt on back. Null while an
     * Edit-mode load is still in flight; once set, any divergence between the
     * current state and this snapshot means the user has unsaved changes.
     *
     * Reset to the current state after a successful save so the user can keep
     * editing without immediately re-prompting.
     */
    private val _initialFormData = MutableStateFlow<ItemFormState?>(
        if (isEdit) null else ItemFormState().userFields(),
    )

    /**
     * True when the user has changed something since opening the form (Add)
     * or since the last save (Edit). Drives the back-arrow confirmation in
     * [com.storehop.app.ui.items.ItemFormScreen].
     */
    val isDirty: StateFlow<Boolean> =
        combine(_state, _initialFormData) { current, initial ->
            initial != null && current.userFields() != initial
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val categories: StateFlow<List<Category>> = categoryRepository
        .observeAll(includeArchived = false)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val stores: StateFlow<List<Store>> = storeRepository
        .observeAll(includeArchived = false)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    init {
        if (itemId != null) {
            viewModelScope.launch {
                val row = itemRepository.observeById(itemId).first()
                if (row != null) {
                    // v0.8.0.5: read tagged stores via `aliveStoreIdsForItem`
                    // (filters tombstoned xrefs) instead of `row.stores`,
                    // which uses Room's @Junction join and surfaces ghost
                    // stores from soft-deleted bridge rows. Fixes Mike's
                    // "Aldi keeps showing checked after I uncheck + save".
                    _state.value = _state.value.copy(
                        name = row.item.name,
                        brand = row.item.brand.orEmpty(),
                        categoryId = row.item.categoryId,
                        storeIds = itemRepository.aliveStoreIdsForItem(itemId),
                        isStaple = row.item.isStaple,
                        isPriority = row.item.isPriority,
                        imageUrl = row.item.imageUrl,
                        isLoading = false,
                    )
                    // Capture the just-loaded form as the "no edits yet"
                    // baseline; isDirty stays false until the user types.
                    _initialFormData.value = _state.value.userFields()
                } else {
                    _state.value = _state.value.copy(isLoading = false, saveError = "Item not found")
                }
            }
        }
    }

    fun setName(v: String) {
        _state.value = _state.value.copy(name = v, nameError = false, saveError = null)
    }
    fun setBrand(v: String) { _state.value = _state.value.copy(brand = v) }
    fun setCategoryId(v: String?) { _state.value = _state.value.copy(categoryId = v) }
    fun toggleStore(id: String) {
        val now = _state.value.storeIds
        _state.value = _state.value.copy(
            storeIds = if (id in now) now - id else now + id,
        )
    }
    fun setStaple(v: Boolean) { _state.value = _state.value.copy(isStaple = v) }
    fun setPriority(v: Boolean) { _state.value = _state.value.copy(isPriority = v) }

    /**
     * Create a new category from the inline "+ New category" affordance in
     * the category picker. On success, the new category is auto-selected on
     * the form so the user doesn't have to re-tap it. Returns null on
     * success, or a localized error string the dialog renders inline.
     *
     * Mirrors [com.storehop.app.ui.categories.ManageCategoriesViewModel.addCategory]'s
     * validation pattern (empty / duplicate / generic), routed through the
     * same [CategoryRepository.addCategory] which handles the alive-skip
     * and tombstone-resurrect paths.
     */
    suspend fun addCategory(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return appContext.getString(R.string.error_category_name_empty)
        return try {
            val newId = categoryRepository.addCategory(name = trimmed)
            // Auto-select so the just-created category is already chosen on
            // the form when the dialog dismisses.
            _state.value = _state.value.copy(categoryId = newId)
            null
        } catch (e: IllegalArgumentException) {
            appContext.getString(R.string.error_category_name_duplicate, trimmed)
        } catch (e: Exception) {
            appContext.getString(R.string.error_could_not_add_category)
        }
    }

    fun pickLocalImage(uri: Uri) {
        // Stage the local URI for preview. Upload happens on submit() so we
        // already have an itemId (Add mode generates one inside addItem).
        _state.value = _state.value.copy(localImageUri = uri)
    }

    fun clearImage() {
        // Clear both the staged local URI and the persisted URL. The saved
        // imageUrl gets nulled in the next submit() call.
        _state.value = _state.value.copy(localImageUri = null, imageUrl = null)
    }

    fun submit() {
        val s = _state.value
        if (s.name.trim().isEmpty()) {
            _state.value = s.copy(nameError = true)
            return
        }
        _state.value = s.copy(isSubmitting = true, saveError = null)
        viewModelScope.launch {
            try {
                // Persist the row first so we have an id for the upload path.
                // Image URL gets patched in by a follow-up updateItem if the
                // user staged a local pick; otherwise we save as-is.
                val savedId = if (itemId == null) {
                    itemRepository.addItem(
                        name = s.name,
                        categoryId = s.categoryId,
                        storeIds = s.storeIds,
                        brand = s.brand.takeIf { it.isNotBlank() },
                        imageUrl = s.imageUrl,
                        isStaple = s.isStaple,
                        isPriority = s.isPriority,
                    )
                } else {
                    itemRepository.updateItem(
                        id = itemId,
                        name = s.name,
                        categoryId = s.categoryId,
                        storeIds = s.storeIds,
                        quantity = null,
                        notes = null,
                        brand = s.brand.takeIf { it.isNotBlank() },
                        imageUrl = s.imageUrl,
                        isStaple = s.isStaple,
                        isPriority = s.isPriority,
                    )
                    itemId
                }

                if (s.localImageUri != null) {
                    _state.value = _state.value.copy(isUploadingImage = true)
                    val url = imageUploader.upload(s.localImageUri, savedId)
                    itemRepository.updateItem(
                        id = savedId,
                        name = s.name,
                        categoryId = s.categoryId,
                        storeIds = s.storeIds,
                        quantity = null,
                        notes = null,
                        brand = s.brand.takeIf { it.isNotBlank() },
                        imageUrl = url,
                        isStaple = s.isStaple,
                        isPriority = s.isPriority,
                    )
                }
                _state.value = _state.value.copy(
                    isSubmitting = false,
                    isUploadingImage = false,
                    saved = true,
                )
                // Reset the dirty baseline so the user can keep editing
                // post-save without an immediate "discard?" prompt.
                _initialFormData.value = _state.value.userFields()
            } catch (e: IllegalArgumentException) {
                _state.value = _state.value.copy(
                    isSubmitting = false,
                    isUploadingImage = false,
                    saveError = e.message ?: "Could not save",
                )
            } catch (e: Exception) {
                // Upload failure: the row is already saved, surface a soft error
                // and let the user retry. Don't block save on a network blip.
                _state.value = _state.value.copy(
                    isSubmitting = false,
                    isUploadingImage = false,
                    saveError = e.message ?: "Image upload failed",
                )
            }
        }
    }

    /**
     * Strip transient UI flags (loading / submitting / errors / saved /
     * deleted) so the dirty-state comparison is over actual user input only.
     * Without this, every flick of `isSubmitting` or every `nameError` clear
     * would register as "dirty."
     */
    private fun ItemFormState.userFields(): ItemFormState = copy(
        isLoading = false,
        isSubmitting = false,
        isUploadingImage = false,
        nameError = false,
        saveError = null,
        saved = false,
        deleted = false,
    )

    fun delete() {
        val id = itemId ?: return
        val nameSnapshot = _state.value.name.trim().ifEmpty { _state.value.name }
        _state.value = _state.value.copy(isSubmitting = true)
        viewModelScope.launch {
            try {
                itemRepository.softDelete(id)
                _state.value = _state.value.copy(isSubmitting = false, deleted = true)
                // Hand the undo prompt to whichever screen pops in next
                // (the items list, today). Using the bus instead of plumbing
                // a callback up through the screen because the form is about
                // to be destroyed by the back-navigation effect.
                undoBus.emit(UndoEvent.ItemDeleted(itemId = id, itemName = nameSnapshot))
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isSubmitting = false,
                    saveError = e.message ?: "Could not delete",
                )
            }
        }
    }
}
