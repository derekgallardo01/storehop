package com.storehop.app.ui.categories

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storehop.app.R
import com.storehop.app.data.entity.Category
import com.storehop.app.data.repository.CategoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

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
}
