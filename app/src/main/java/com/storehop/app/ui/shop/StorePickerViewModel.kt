package com.storehop.app.ui.shop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storehop.app.data.repository.ShoppingRepository
import com.storehop.app.data.repository.StorePickerRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class StorePickerViewModel @Inject constructor(
    private val shoppingRepository: ShoppingRepository,
) : ViewModel() {

    val rows: StateFlow<List<StorePickerRow>> = shoppingRepository
        .observeStorePickerRows()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /**
     * The cross-store critical-needs list — every priority+needed item that's
     * tagged to ANY store, deduplicated by name. Drives the banner above the
     * picker list. Empty when nothing critical is needed.
     */
    val criticalAcrossStores: StateFlow<List<String>> = rows
        .map { all -> all.flatMap { it.criticalItemNames }.distinct() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())
}
