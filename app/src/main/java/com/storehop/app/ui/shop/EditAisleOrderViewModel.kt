package com.storehop.app.ui.shop

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storehop.app.data.entity.Category
import com.storehop.app.data.entity.Store
import com.storehop.app.data.repository.CategoryRepository
import com.storehop.app.data.repository.StoreCategoryOrderRepository
import com.storehop.app.data.repository.StoreRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the per-store Edit Aisle Order screen.
 *
 * The reorderable list is the intersection of
 * `StoreCategoryOrderRepository.observeForStore(storeId)` (which categories
 * have an SCO row for this store) joined with
 * `CategoryRepository.observeAll()` (the live category set, for names + icons).
 * Categories without an SCO row don't appear in v0.5 — the user adds them
 * to the store's aisle plan implicitly by tagging an item to this store; a
 * future patch can auto-create a default SCO row for that case.
 *
 * On drag release the screen calls [commitOrder] with the full top-to-bottom
 * list of category ids; the repository wraps the rewrite in a transaction.
 */
@HiltViewModel
class EditAisleOrderViewModel @Inject constructor(
    private val storeCategoryOrderRepository: StoreCategoryOrderRepository,
    private val storeRepository: StoreRepository,
    categoryRepository: CategoryRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val storeId: String = checkNotNull(savedStateHandle.get<String>("storeId")) {
        "EditAisleOrder route requires a storeId arg"
    }

    val store: StateFlow<Store?> = storeRepository.observeById(storeId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    /**
     * Categories visible at this store, ordered by displayOrder. Joins SCO
     * rows with the live Category set so we have name + icon. Categories
     * without an SCO row for this store are filtered out (the screen's
     * empty-state messaging handles that case).
     */
    val orderedCategories: StateFlow<List<Category>> =
        combine(
            storeCategoryOrderRepository.observeForStore(storeId),
            categoryRepository.observeAll(includeArchived = false),
        ) { scoRows, allCategories ->
            val byId = allCategories.associateBy { it.id }
            scoRows
                .sortedBy { it.displayOrder }
                .mapNotNull { byId[it.categoryId] }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /**
     * Persist a new aisle order. Called by the screen after the user
     * releases a drag. Repository wraps the rewrite in a transaction.
     */
    fun commitOrder(orderedCategoryIds: List<String>) {
        viewModelScope.launch {
            storeCategoryOrderRepository.reorderCategoriesForStore(storeId, orderedCategoryIds)
        }
    }
}
