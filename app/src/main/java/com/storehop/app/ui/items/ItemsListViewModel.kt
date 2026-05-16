package com.storehop.app.ui.items

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storehop.app.data.db.relations.ItemWithCategoryAndStores
import com.storehop.app.data.entity.Store
import com.storehop.app.data.prefs.SortMode
import com.storehop.app.data.prefs.UserPreferencesRepository
import com.storehop.app.data.repository.ItemRepository
import com.storehop.app.data.repository.StoreRepository
import com.storehop.app.ui.util.UndoEvent
import com.storehop.app.ui.util.UndoEventBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One section of the master Items list when [SortMode.CATEGORY] is active.
 * For uncategorised items (item.categoryId == null), [categoryName] is the
 * localized "(uncategorised)" label and [categoryNameKey] is null.
 */
data class ItemsCategorySection(
    val categoryName: String,
    val categoryNameKey: String?,
    val rows: List<ItemWithCategoryAndStores>,
)

data class ItemsListUiState(
    val rows: List<ItemWithCategoryAndStores> = emptyList(),
    val sections: List<ItemsCategorySection> = emptyList(),
    val sortMode: SortMode = SortMode.ALPHABETIC,
    /**
     * Item IDs that are currently "needed" at one or more tagged stores.
     * Drives the +/- toggle on each row: minus when in this set, plus
     * otherwise. Empty when the user is signed out or has no items needed.
     */
    val neededItemIds: Set<String> = emptySet(),
    /**
     * v0.8.1: item IDs the user has multi-selected (long-press → tap to
     * extend). Non-empty set means the screen is in *selection mode*:
     * the TopAppBar swaps to a contextual variant with a "[N] selected"
     * title + a "Tag to stores…" action, row taps toggle membership
     * instead of opening the editor, and the +/- toggle is hidden.
     */
    val selectedItemIds: Set<String> = emptySet(),
) {
    val isInSelectionMode: Boolean get() = selectedItemIds.isNotEmpty()
}

/**
 * Drives the Items master screen. Observes every live item plus a user-typed
 * search query, returns the filtered list.
 *
 * Filter is client-side (substring match on name + brand, case-insensitive)
 * because the master list is bounded — users have dozens, not thousands of
 * items, so an in-memory filter beats a parameterized SQL query for UX
 * (no debounce needed, instant feedback).
 *
 * The list can be rendered flat-alphabetic (default) or grouped by category;
 * the choice persists via [UserPreferencesRepository.itemsListSortMode]. In
 * CATEGORY mode, items without a category surface in a trailing
 * "(uncategorised)" section.
 */
@HiltViewModel
class ItemsListViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val preferencesRepository: UserPreferencesRepository,
    storeRepository: StoreRepository,
    undoBus: UndoEventBus,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /**
     * v0.8.1: live store list for the bulk-tag picker dialog. Mirrors the
     * pattern in `ItemFormViewModel.stores` (archived excluded). The
     * picker dialog reads from this StateFlow when the screen is in
     * selection mode and the user opens "Tag to stores…".
     */
    val stores: StateFlow<List<Store>> = storeRepository
        .observeAll(includeArchived = false)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /**
     * v0.8.1 bulk-tag selection state. See [ItemsListUiState.selectedItemIds]
     * for the UI semantics. Kept as a separate MutableStateFlow so the public
     * uiState combine can fold it in without exposing the mutable.
     */
    private val _selectedItemIds = MutableStateFlow<Set<String>>(emptySet())

    val uiState: StateFlow<ItemsListUiState> =
        combine(
            itemRepository.observeAll(),
            _query,
            preferencesRepository.itemsListSortMode,
            itemRepository.observeNeededItemIds(),
            _selectedItemIds,
        ) { all, q, sortMode, neededIds, selected ->
            val needle = q.trim()
            val filtered = if (needle.isEmpty()) all
            else all.filter { row ->
                row.item.name.contains(needle, ignoreCase = true) ||
                    (row.item.brand?.contains(needle, ignoreCase = true) == true) ||
                    // v0.6.2: also match against the category's raw name so
                    // typing "frozen" surfaces every item in the Frozen
                    // category. Note: matches `Category.name` which is the
                    // raw seeded value (English for seeded categories);
                    // localized seed labels via nameKey are not searched
                    // here -- a non-English user searching their localized
                    // category label won't hit this branch. Acceptable
                    // first pass; extend with Context-aware resolution if
                    // user feedback indicates the gap.
                    (row.category?.name?.contains(needle, ignoreCase = true) == true)
            }
            ItemsListUiState(
                rows = if (sortMode == SortMode.ALPHABETIC) {
                    filtered.sortedBy { it.item.name.lowercase() }
                } else emptyList(),
                sections = if (sortMode == SortMode.CATEGORY) {
                    filtered.groupIntoSections()
                } else emptyList(),
                sortMode = sortMode,
                neededItemIds = neededIds,
                selectedItemIds = selected,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = ItemsListUiState(),
        )

    /**
     * Pass-through stream of cross-screen undo events. The form fires an
     * ItemDeleted event on softDelete; this list shows a snackbar with UNDO
     * once it pops to the front. Channel-based, so each event is delivered
     * exactly once -- restarting the list after an undo doesn't re-show the
     * snackbar.
     */
    val undoEvents: Flow<UndoEvent> = undoBus.events

    fun setQuery(q: String) { _query.value = q }

    fun setSortMode(mode: SortMode) {
        viewModelScope.launch { preferencesRepository.setItemsListSortMode(mode) }
    }

    /**
     * Toggle the +/− button on the Items list. If the item is currently
     * needed at any tagged store ([currentlyNeeded] = true), mark it
     * not-needed across all tagged stores ("−"). Otherwise mark it
     * needed at every tagged store ("+"). The cross-store cascade design
     * means one trip clears the list everywhere, so a single toggle
     * keeps both branches coherent across the Shop tab.
     */
    fun toggleNeededAtAllStores(itemId: String, currentlyNeeded: Boolean) {
        viewModelScope.launch {
            if (currentlyNeeded) {
                itemRepository.markPurchasedAcrossAllStores(itemId)
            } else {
                itemRepository.markNeededAcrossAllStores(itemId)
            }
        }
    }

    fun undoItemDelete(itemId: String) {
        viewModelScope.launch { itemRepository.undoSoftDelete(itemId) }
    }

    // ---- v0.8.1 bulk-tag selection mode ----------------------------------

    /**
     * Toggle [itemId] in the selection set. First selected item enters
     * selection mode; removing the last one exits it (the screen reads
     * `isInSelectionMode` off the resulting state). Used by both the
     * long-press entry path and subsequent tap-to-extend interactions
     * while in selection mode.
     */
    fun toggleSelection(itemId: String) {
        val now = _selectedItemIds.value
        _selectedItemIds.value = if (itemId in now) now - itemId else now + itemId
    }

    /**
     * Drop every selected id, exiting selection mode. Wired to the
     * contextual TopAppBar's close action and to system back when the
     * screen is in selection mode.
     */
    fun clearSelection() {
        _selectedItemIds.value = emptySet()
    }

    /**
     * Apply the bulk store-tag picker's choice to every selected item:
     * add-only semantics (union with each item's existing store set; no
     * stores are removed). Exits selection mode on success. No-op if
     * either set is empty.
     */
    fun applyBulkStores(storeIdsToAdd: Set<String>) {
        val ids = _selectedItemIds.value
        if (ids.isEmpty() || storeIdsToAdd.isEmpty()) return
        viewModelScope.launch {
            itemRepository.bulkTagStoresForItems(ids, storeIdsToAdd)
            _selectedItemIds.value = emptySet()
        }
    }
}

/**
 * Group filtered items by category name (case-insensitive sort), with
 * uncategorised rows collected into a trailing section keyed by the
 * `items_uncategorised_label` resource. The screen resolves the label
 * itself; here we just emit a `null` nameKey + a sentinel string the
 * screen swaps for the localized one, mirroring the StatisticsViewModel
 * "(uncategorised)" handling.
 *
 * The key shape `__uncategorised__` is internal -- the screen layer
 * detects it via [ItemsCategorySection.categoryNameKey] being null AND
 * [ItemsCategorySection.categoryName] being this sentinel.
 */
internal const val UNCATEGORISED_SENTINEL = "__uncategorised__"

private fun List<ItemWithCategoryAndStores>.groupIntoSections(): List<ItemsCategorySection> {
    val (withCat, withoutCat) = partition { it.category != null }
    val grouped = withCat
        .groupBy { it.category!!.id }
        .map { (_, rows) ->
            val cat = rows.first().category!!
            ItemsCategorySection(
                categoryName = cat.name,
                categoryNameKey = cat.nameKey,
                rows = rows.sortedBy { it.item.name.lowercase() },
            )
        }
        .sortedBy { it.categoryName.lowercase() }
    val uncategorisedSection = if (withoutCat.isEmpty()) null else ItemsCategorySection(
        categoryName = UNCATEGORISED_SENTINEL,
        categoryNameKey = null,
        rows = withoutCat.sortedBy { it.item.name.lowercase() },
    )
    return grouped + listOfNotNull(uncategorisedSection)
}
