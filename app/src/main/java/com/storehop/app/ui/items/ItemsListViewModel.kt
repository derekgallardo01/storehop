package com.storehop.app.ui.items

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storehop.app.data.db.relations.ItemWithCategoryAndStores
import com.storehop.app.data.prefs.SortMode
import com.storehop.app.data.prefs.UserPreferencesRepository
import com.storehop.app.data.repository.ItemRepository
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
)

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
    undoBus: UndoEventBus,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val uiState: StateFlow<ItemsListUiState> =
        combine(
            itemRepository.observeAll(),
            _query,
            preferencesRepository.itemsListSortMode,
        ) { all, q, sortMode ->
            val needle = q.trim()
            val filtered = if (needle.isEmpty()) all
            else all.filter { row ->
                row.item.name.contains(needle, ignoreCase = true) ||
                    (row.item.brand?.contains(needle, ignoreCase = true) == true)
            }
            ItemsListUiState(
                rows = if (sortMode == SortMode.ALPHABETIC) {
                    filtered.sortedBy { it.item.name.lowercase() }
                } else emptyList(),
                sections = if (sortMode == SortMode.CATEGORY) {
                    filtered.groupIntoSections()
                } else emptyList(),
                sortMode = sortMode,
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

    fun undoItemDelete(itemId: String) {
        viewModelScope.launch { itemRepository.undoSoftDelete(itemId) }
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
