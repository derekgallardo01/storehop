package com.storehop.app.ui.shop

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storehop.app.data.db.relations.ItemWithCategoryAndStores
import com.storehop.app.data.db.relations.ShoppingRow
import com.storehop.app.data.entity.Store
import com.storehop.app.data.prefs.SortMode
import com.storehop.app.data.prefs.UserPreferencesRepository
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
    /**
     * Aisle-grouped list, populated only when [sortMode] is
     * [SortMode.CATEGORY]. Empty otherwise so the screen can pick the
     * single non-empty list to render without a sentinel check.
     */
    val rowsByCategory: List<CategorySection> = emptyList(),
    /**
     * Flat alphabetic list (case-insensitive on item name), populated only
     * when [sortMode] is [SortMode.ALPHABETIC].
     */
    val rowsAlphabetic: List<ShoppingRow> = emptyList(),
    val criticalNames: List<String> = emptyList(),
    /**
     * Names of "Buy Today!"-flagged items still needed at THIS store. Drives
     * the in-store Buy Today banner so the urgency surfaces while shopping,
     * not only on the Stores overview (Mike-reported v0.9.1).
     */
    val buyTodayNames: List<String> = emptyList(),
    val query: String = "",
    val showPurchased: Boolean = true,
    val sortMode: SortMode = SortMode.CATEGORY,
)

/**
 * One row in the QuickAddBar's autocomplete. Keeps just what the suggestion
 * list needs to render (id + name + brand + category + staple flag) so the
 * VM doesn't leak the whole [com.storehop.app.data.db.relations.ItemWithCategoryAndStores]
 * surface to the screen.
 */
data class QuickAddSuggestion(
    val itemId: String,
    val name: String,
    val brand: String?,
    val categoryName: String?,
    val isStaple: Boolean,
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
    private val preferencesRepository: UserPreferencesRepository,
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

    private val _quickAddInput = MutableStateFlow("")
    val quickAddInput: StateFlow<String> = _quickAddInput.asStateFlow()

    val store: StateFlow<Store?> = storeRepository.observeById(storeId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    private val rows: StateFlow<List<ShoppingRow>> = shoppingRepository
        .shoppingListForStore(storeId, sessionStartMs)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val uiState: StateFlow<ShopAtStoreUiState> =
        combine(
            store,
            rows,
            _query,
            preferencesRepository.showPurchased,
            preferencesRepository.shopAtStoreSortMode,
        ) { st, allRows, q, showP, sortMode ->
            // When showP is false, hide every checked-off row regardless of
            // staple status -- "checked off" is a single concept to the user.
            val visible = if (showP) allRows else allRows.filter { it.isNeeded }
            val needle = q.trim()
            val filtered = if (needle.isEmpty()) visible else visible.filter {
                it.itemName.contains(needle, ignoreCase = true) ||
                    (it.brand?.contains(needle, ignoreCase = true) == true)
            }
            ShopAtStoreUiState(
                store = st,
                rowsByCategory = if (sortMode == SortMode.CATEGORY) {
                    // Order the list independently of isNeeded (aisle order,
                    // then item name) BEFORE grouping. The DAO sorts
                    // `isNeeded DESC` first, which in category mode made a row
                    // leap from the purchased tail into the needed block on
                    // un-check -- reordering whole sections and yanking the
                    // scroll anchor (Mike's v0.9 report). Alphabetic mode never
                    // jumped because it already drops isNeeded from its sort;
                    // this makes category mode match: toggling a row's checked
                    // state no longer moves it.
                    filtered
                        .sortedWith(
                            compareBy(
                                { it.displayOrder ?: 9999 },
                                // Uncategorized rows sort last (U+FFFF); they're
                                // grouped under "(uncategorized)" by groupByCategory.
                                { it.categoryName?.lowercase() ?: "￿" },
                                { it.itemName.lowercase() },
                            ),
                        )
                        .groupByCategory()
                } else emptyList(),
                rowsAlphabetic = if (sortMode == SortMode.ALPHABETIC) {
                    filtered.sortedBy { it.itemName.lowercase() }
                } else emptyList(),
                // Critical names come from the unfiltered list (search + the
                // hide-purchased toggle don't suppress critical needs from
                // the banner) but MUST be gated on isNeeded -- otherwise
                // priority items already checked off this session, and
                // priority staples carried over from prior trips, keep
                // showing as "critical" while the row underneath is
                // struck-through. "Critical" means "still unbought".
                criticalNames = allRows
                    .filter { it.isPriority && it.isNeeded }
                    .map { it.itemName },
                // Same gating as criticalNames: only items still needed here
                // count, and the banner ignores search + hide-purchased.
                buyTodayNames = allRows
                    .filter { it.isBuyToday && it.isNeeded }
                    .map { it.itemName },
                query = q,
                showPurchased = showP,
                sortMode = sortMode,
            )
        }.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000L), ShopAtStoreUiState(),
        )

    fun setQuery(q: String) { _query.value = q }

    fun setQuickAddInput(value: String) { _quickAddInput.value = value }

    fun setShowPurchased(value: Boolean) {
        viewModelScope.launch { preferencesRepository.setShowPurchased(value) }
    }

    fun setSortMode(mode: SortMode) {
        viewModelScope.launch { preferencesRepository.setShopAtStoreSortMode(mode) }
    }

    /**
     * Suggestions for the QuickAddBar autocomplete. Only populated once the
     * user starts typing — empty input yields an empty list (the bar stays
     * unobtrusive until you ask for it). Filters the master Items library
     * by name + brand substring (case-insensitive), prefix matches first,
     * capped at 8. Items already needed at this store are excluded so the
     * list isn't showing things you already have on the list.
     */
    val quickAddSuggestions: StateFlow<List<QuickAddSuggestion>> =
        combine(
            _quickAddInput,
            itemRepository.observeAll(),
            rows,
        ) { input, allItems, currentRows ->
            val needle = input.trim()
            if (needle.isEmpty()) return@combine emptyList()
            val neededHere: Set<String> = currentRows
                .asSequence()
                .filter { it.isNeeded }
                .map { it.itemId }
                .toSet()
            allItems.asSequence()
                .filter { it.item.id !in neededHere }
                .filter { row ->
                    row.item.name.contains(needle, ignoreCase = true) ||
                        (row.item.brand?.contains(needle, ignoreCase = true) == true)
                }
                .sortedWith(
                    compareBy<ItemWithCategoryAndStores> { row ->
                        // Prefix matches on name rank first, then prefix on
                        // brand, then everything else (substring matches).
                        when {
                            row.item.name.startsWith(needle, ignoreCase = true) -> 0
                            row.item.brand?.startsWith(needle, ignoreCase = true) == true -> 1
                            else -> 2
                        }
                    }.thenBy { it.item.name.lowercase() },
                )
                .take(8)
                .map { it.toSuggestion() }
                .toList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /**
     * Snapshot timestamp returned by the most recent cascade purchase, kept
     * around so [undoPurchase] can do a precision rollback. Only the latest
     * value matters: a new purchase dismisses the prior snackbar (see
     * ShopAtStoreScreen), so an Undo tap always targets whatever is in here.
     */
    private var lastPurchaseSnapshot: Long? = null

    /**
     * Tap behavior on a row.
     *  - needed -> cascade-mark purchased: a single shopping trip flips every
     *    store the item is tagged to, and writes one PurchaseRecord at the
     *    store the user is currently shopping at.
     *  - purchased -> cascade-mark needed again across every store the item is
     *    tagged to (manual un-check). Symmetric with the purchase cascade: if
     *    buying it at Continente cleared it from Pingo, un-buying it at
     *    Continente must bring it back at Pingo too (Mike-reported v0.9). No
     *    PurchaseRecord is touched -- this is a list-state correction, not a
     *    purchase event; the snackbar Undo path stays the "as if it never
     *    happened" reversal.
     */
    fun togglePurchased(row: ShoppingRow) {
        viewModelScope.launch {
            if (row.isNeeded) {
                lastPurchaseSnapshot = itemRepository.markPurchasedAtStore(row.itemId, storeId)
            } else {
                lastPurchaseSnapshot = null
                itemRepository.markNeededAcrossAllStores(row.itemId)
            }
        }
    }

    /**
     * Reverse the most recent cascade purchase: restores every xref the
     * cascade flipped (matched by the snapshot timestamp) and soft-deletes
     * the matching PurchaseRecord, so history shows no purchase at all. No-op
     * if there's no snapshot in flight (e.g. user tapped Undo on a stale
     * snackbar that somehow escaped the dismiss-on-next-tap guard).
     */
    fun undoPurchase(itemId: String) {
        val snapshot = lastPurchaseSnapshot ?: return
        lastPurchaseSnapshot = null
        viewModelScope.launch { itemRepository.undoPurchase(itemId, snapshot) }
    }

    /**
     * Submit the current QuickAdd input. Routes to
     * [ItemRepository.addItemFromQuickAdd] which dedupes by case-insensitive
     * name match before creating: existing master-list items get re-tagged
     * to this store instead of duplicated. Clears the input on success.
     * No-op for whitespace-only input.
     */
    fun submitQuickAddText() {
        val trimmed = _quickAddInput.value.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            itemRepository.addItemFromQuickAdd(trimmed, storeId)
            _quickAddInput.value = ""
        }
    }

    /**
     * The user tapped a suggestion in the QuickAdd autocomplete. Tag the
     * existing master-list item to this store (idempotent for items already
     * tagged) and clear the input.
     */
    fun pickExistingItem(itemId: String) {
        viewModelScope.launch {
            itemRepository.tagItemToStore(itemId, storeId)
            _quickAddInput.value = ""
        }
    }
}

private fun ItemWithCategoryAndStores.toSuggestion() = QuickAddSuggestion(
    itemId = item.id,
    name = item.name,
    brand = item.brand,
    categoryName = category?.name,
    isStaple = item.isStaple,
)

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
