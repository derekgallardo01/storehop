package com.storehop.app.ui.categories

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storehop.app.R
import com.storehop.app.data.entity.Category
import com.storehop.app.data.repository.CategoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Single source of truth for the Manage Categories screen's render state:
 * the live category list joined with the in-memory selection set + the
 * current selection mode.
 */
data class ManageCategoriesUiState(
    val categories: List<Category> = emptyList(),
    val selectionMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
)

/**
 * Drives the Manage Categories screen. Mirrors [com.storehop.app.ui.shop.StorePickerViewModel]'s
 * validation pattern: localized error strings come back from add/rename
 * suspend functions; the screen renders them inline in the dialog. Delete
 * fires a fire-and-forget into the repo and undo plumbs through the same
 * way Stores' undo does.
 */
@HiltViewModel
class ManageCategoriesViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val categoryRepository: CategoryRepository,
) : ViewModel() {

    val categories: StateFlow<List<Category>> = categoryRepository
        .observeAll(includeArchived = false)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    // Selection-mode state is in-memory (resets when the screen leaves) --
    // a user who escapes back to Items and re-enters Manage Categories
    // starts with a clean slate. Mirrors Gmail / Photos behaviour.
    private val _selectionMode = MutableStateFlow(false)
    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())

    val uiState: StateFlow<ManageCategoriesUiState> = combine(
        categories, _selectionMode, _selectedIds,
    ) { rows, mode, ids ->
        // Drop selections for ids that no longer exist (race with a delete
        // landing while selection mode is active).
        val liveIds = rows.map { it.id }.toSet()
        val filteredIds = ids.intersect(liveIds)
        ManageCategoriesUiState(
            categories = rows,
            selectionMode = mode,
            selectedIds = filteredIds,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), ManageCategoriesUiState())

    val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    /**
     * Add a new category. Returns null on success, or a localized error
     * string the dialog renders inline (empty name, duplicate name, generic
     * failure).
     */
    suspend fun addCategory(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return appContext.getString(R.string.error_category_name_empty)
        return try {
            categoryRepository.addCategory(name = trimmed)
            null
        } catch (e: IllegalArgumentException) {
            appContext.getString(R.string.error_category_name_duplicate, trimmed)
        } catch (e: Exception) {
            appContext.getString(R.string.error_could_not_add_category)
        }
    }

    /**
     * Rename an existing category. Returns null on success or a localized
     * error string the rename dialog renders inline.
     */
    suspend fun renameCategory(id: String, name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return appContext.getString(R.string.error_category_name_empty)
        return try {
            categoryRepository.rename(id, trimmed)
            null
        } catch (e: IllegalArgumentException) {
            appContext.getString(R.string.error_category_name_duplicate, trimmed)
        } catch (e: Exception) {
            appContext.getString(R.string.error_could_not_rename_category)
        }
    }

    /**
     * Soft-delete a category. The repo's cascade clears `items.categoryId`
     * for every item that pointed here and tombstones every per-store aisle
     * order row referencing this category. All cascade rows share the same
     * `deletedAt` ms so [undoDeleteCategory] can restore the exact set.
     */
    fun deleteCategory(id: String) {
        viewModelScope.launch { categoryRepository.softDelete(id) }
    }

    /**
     * Reverse a recent [deleteCategory]. Restores the category row, restores
     * its per-store aisle order entries, and re-links items whose
     * categoryId was cascade-cleared at the same instant.
     */
    fun undoDeleteCategory(id: String) {
        viewModelScope.launch { categoryRepository.undoSoftDelete(id) }
    }

    // ---- v0.6.4: selection mode + bulk delete + reorder + multi-add ----

    /**
     * Enter selection mode and select [id]. Triggered by long-press on a
     * category tile. A single subsequent tap on any tile toggles its
     * presence in [_selectedIds]; tapping the X / back exits selection
     * mode and clears [_selectedIds].
     */
    fun enterSelection(id: String) {
        _selectionMode.value = true
        _selectedIds.value = setOf(id)
    }

    fun toggleSelection(id: String) {
        val current = _selectedIds.value
        _selectedIds.value = if (id in current) current - id else current + id
        if (_selectedIds.value.isEmpty()) {
            // Auto-exit when the user deselects the last item -- avoids
            // leaving a "0 selected" top app bar in a half-state.
            _selectionMode.value = false
        }
    }

    fun selectAll() {
        _selectedIds.value = categories.value.map { it.id }.toSet()
    }

    fun cancelSelection() {
        _selectionMode.value = false
        _selectedIds.value = emptySet()
    }

    /**
     * Soft-delete every currently-selected category in one batch and return
     * the batch's `deletedAt` so the screen can hand it to the UndoBar.
     * Exits selection mode immediately so the screen recomposes against
     * the now-shorter list. Returns null when the selection was empty
     * (the screen guards on this to suppress an empty undo prompt).
     */
    suspend fun deleteSelected(): Long? {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return null
        val deletedAt = categoryRepository.softDeleteMany(ids.toList())
        cancelSelection()
        return deletedAt
    }

    /**
     * Reverse a [deleteSelected] batch. Wrapped here (not inline in the
     * screen's undo callback) so the screen doesn't need to know about
     * coroutine scoping.
     */
    fun undoDeleteMany(deletedAt: Long) {
        viewModelScope.launch { categoryRepository.undoSoftDeleteMany(deletedAt) }
    }

    /**
     * Commit a drag-reorder. [orderedIds] is the new top-to-bottom
     * sequence -- the screen builds this from its local drag state and
     * hands the final order in on drop.
     */
    fun commitReorder(orderedIds: List<String>) {
        viewModelScope.launch { categoryRepository.reorder(orderedIds) }
    }

    /**
     * Bulk-add categories from a multi-line text blob. Splits on
     * newlines, trims, drops blanks, and case-insensitively de-dupes
     * within the input itself. Routes each unique name through
     * [CategoryRepository.addCategory] which already handles the
     * alive-skip + tombstone-resurrect cases.
     *
     * Returns a summary [BulkAddResult]: the screen renders it as
     * "Added N · skipped M" with per-line error details in a snackbar
     * action.
     */
    suspend fun addManyCategories(raw: String): BulkAddResult {
        val unique = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .toList()
        if (unique.isEmpty()) {
            return BulkAddResult(
                added = 0,
                duplicates = 0,
                errors = listOf(appContext.getString(R.string.error_category_name_empty)),
            )
        }
        var added = 0
        var duplicates = 0
        val errors = mutableListOf<String>()
        for (name in unique) {
            try {
                categoryRepository.addCategory(name = name)
                added++
            } catch (e: IllegalArgumentException) {
                // Duplicate name (alive collision). The user might know
                // and just want the new ones added; counting separately
                // from `errors` so the snackbar can summarise.
                duplicates++
            } catch (e: Exception) {
                errors += appContext.getString(R.string.error_could_not_add_category) + ": $name"
            }
        }
        return BulkAddResult(added = added, duplicates = duplicates, errors = errors)
    }
}

/**
 * Summary of a [ManageCategoriesViewModel.addManyCategories] call.
 *  - `added`: number of new category rows persisted (includes resurrected
 *    tombstones).
 *  - `duplicates`: input names that already existed alive and were
 *    silently skipped.
 *  - `errors`: per-line failure messages (rare; usually empty).
 */
data class BulkAddResult(
    val added: Int,
    val duplicates: Int,
    val errors: List<String>,
)
