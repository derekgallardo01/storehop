package com.storehop.app.ui.shop

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storehop.app.data.db.relations.ShoppingRow
import com.storehop.app.data.entity.Store
import com.storehop.app.data.repository.ItemRepository
import com.storehop.app.data.repository.ShoppingRepository
import com.storehop.app.data.repository.StoreRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ShopAtStoreUiState(
    val store: Store? = null,
    val rowsByCategory: List<CategorySection> = emptyList(),
    val criticalNames: List<String> = emptyList(),
    val query: String = "",
)

data class CategorySection(
    val categoryName: String,
    /**
     * Seeded nameKey (e.g. "cat_produce") for localized header lookup.
     * Null for user-added categories (header falls back to [categoryName]).
     */
    val categoryNameKey: String?,
    val displayOrder: Int?,
    val rows: List<ShoppingRow>,
)

@HiltViewModel
class ShopAtStoreViewModel @Inject constructor(
    private val shoppingRepository: ShoppingRepository,
    private val itemRepository: ItemRepository,
    sessionTracker: ShoppingSessionTracker,
    storeRepository: StoreRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val storeId: String = checkNotNull(savedStateHandle.get<String>("storeId")) {
        "ShopAtStore route requires a storeId arg"
    }

    /**
     * Anchored to the *process-wide* shopping session, not this VM's lifetime.
     * That way an item purchased at Lidl shows struck-through at Continente
     * too within the same trip -- the strike-through is the cross-store sync
     * confirmation. The anchor resets when the app process restarts; see
     * [ShoppingSessionTracker] for details.
     */
    private val sessionStartMs: Long = sessionTracker.sessionStartMs()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val store: StateFlow<Store?> = storeRepository.observeById(storeId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    private val rows: StateFlow<List<ShoppingRow>> = shoppingRepository
        .shoppingListForStore(storeId, sessionStartMs)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val uiState: StateFlow<ShopAtStoreUiState> =
        combine(store, rows, _query) { st, allRows, q ->
            val needle = q.trim()
            val filtered = if (needle.isEmpty()) allRows else allRows.filter {
                it.itemName.contains(needle, ignoreCase = true) ||
                    (it.brand?.contains(needle, ignoreCase = true) == true)
            }
            ShopAtStoreUiState(
                store = st,
                rowsByCategory = filtered.groupByCategory(),
                // Critical names come from the unfiltered list -- the search shouldn't
                // hide critical needs from the banner.
                criticalNames = allRows.filter { it.isPriority }.map { it.itemName },
                query = q,
            )
        }.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000L), ShopAtStoreUiState(),
        )

    fun setQuery(q: String) { _query.value = q }

    /**
     * Tap behavior on a row, scoped to THIS store only -- per-store need
     * state means checking off milk at Lidl never touches milk at Aldi.
     *  - needed -> mark purchased at this store (writes one PurchaseRecord)
     *  - purchased -> mark needed again at this store (un-checks; no record)
     */
    fun togglePurchased(row: ShoppingRow) {
        viewModelScope.launch {
            if (row.isNeeded) itemRepository.markPurchasedAtStore(row.itemId, storeId)
            else itemRepository.markNeededAtStore(row.itemId, storeId)
        }
    }

    /**
     * Reverse a freshly-checked-off row. Called from the snackbar UNDO action
     * after [togglePurchased]. No PurchaseRecord is rolled back -- the record
     * is harmless to keep (history is append-only) and trying to delete the
     * exact one we just inserted is fragile.
     */
    fun undoPurchase(itemId: String) {
        viewModelScope.launch { itemRepository.markNeededAtStore(itemId, storeId) }
    }

    /**
     * Add a new item from the bottom quick-add bar: name only, auto-tagged
     * to this store, defaults for everything else (no brand/category/photo,
     * not a staple, not priority). Lets the user capture an item without
     * leaving the shopping flow.
     */
    fun quickAdd(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            itemRepository.addItem(
                name = trimmed,
                categoryId = null,
                storeIds = setOf(storeId),
            )
        }
    }
}

private fun List<ShoppingRow>.groupByCategory(): List<CategorySection> {
    // Group by displayOrder + category name; preserve incoming order which is
    // already aisle-sorted by the DAO.
    val groups = LinkedHashMap<String, MutableList<ShoppingRow>>()
    val groupOrders = HashMap<String, Int?>()
    val groupNameKeys = HashMap<String, String?>()
    forEach { row ->
        val key = row.categoryName ?: "(uncategorized)"
        groups.getOrPut(key) { mutableListOf() }.add(row)
        groupOrders.putIfAbsent(key, row.displayOrder)
        groupNameKeys.putIfAbsent(key, row.categoryNameKey)
    }
    return groups.entries.map { (name, items) ->
        CategorySection(
            categoryName = name,
            categoryNameKey = groupNameKeys[name],
            displayOrder = groupOrders[name],
            rows = items,
        )
    }
}
