package com.storehop.app.ui.items

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storehop.app.data.db.relations.ItemWithCategoryAndStores
import com.storehop.app.data.repository.ItemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Drives the Items master screen. Observes every live item plus a user-typed
 * search query, returns the filtered list.
 *
 * Filter is client-side (substring match on name + brand, case-insensitive)
 * because the master list is bounded — users have dozens, not thousands of
 * items, so an in-memory filter beats a parameterized SQL query for UX
 * (no debounce needed, instant feedback).
 */
@HiltViewModel
class ItemsListViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val items: StateFlow<List<ItemWithCategoryAndStores>> =
        combine(itemRepository.observeAll(), _query) { all, q ->
            val needle = q.trim()
            if (needle.isEmpty()) all
            else all.filter { row ->
                row.item.name.contains(needle, ignoreCase = true) ||
                    (row.item.brand?.contains(needle, ignoreCase = true) == true)
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList(),
        )

    fun setQuery(q: String) { _query.value = q }
}
