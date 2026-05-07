package com.storehop.app.ui.shop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storehop.app.data.repository.ShoppingRepository
import com.storehop.app.data.repository.StorePickerRow
import com.storehop.app.data.repository.StoreRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StorePickerViewModel @Inject constructor(
    private val shoppingRepository: ShoppingRepository,
    private val storeRepository: StoreRepository,
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

    /**
     * Persist the new picker order. Called once when the user releases a
     * drag, with the full top-to-bottom list of store ids as currently
     * laid out on screen. Repository wraps the rewrite in a transaction.
     */
    fun commitOrder(orderedIds: List<String>) {
        viewModelScope.launch { storeRepository.reorderStores(orderedIds) }
    }

    /**
     * Add a new store. Returns null on success, or an error message the
     * dialog can show inline (empty name, duplicate name -- the repo throws
     * IllegalArgumentException for both, with a user-readable message).
     * Successful adds append to the bottom of the picker via the repo's
     * `nextDisplayOrder` allocation; the user can drag from there.
     */
    suspend fun addStore(name: String): String? = try {
        storeRepository.addStore(name = name.trim())
        null
    } catch (e: IllegalArgumentException) {
        e.message ?: "Could not add store"
    }
}
