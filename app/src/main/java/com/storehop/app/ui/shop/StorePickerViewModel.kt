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
    sessionTracker: ShoppingSessionTracker,
) : ViewModel() {

    /**
     * The picker anchors the trip on first view -- if the user opens the app
     * and stares at the picker before tapping into a store, that's the start
     * of their trip. Subsequent purchases (within the process) are gated
     * against this same anchor so a non-staple bought at Lidl still surfaces
     * an "✓ All set" badge on Aldi where it was also tagged.
     */
    private val sessionStartMs: Long = sessionTracker.sessionStartMs()

    val rows: StateFlow<List<StorePickerRow>> = shoppingRepository
        .observeStorePickerRows(sessionStartMs)
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
