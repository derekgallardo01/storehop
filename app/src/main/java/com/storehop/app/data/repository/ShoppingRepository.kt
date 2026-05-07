package com.storehop.app.data.repository

import com.storehop.app.data.entity.Store
import com.storehop.app.data.db.relations.ShoppingRow
import kotlinx.coroutines.flow.Flow

/**
 * One row per live (non-archived) store, augmented with the data the Store
 * Picker needs to render its badges and the cross-store critical banner.
 */
data class StorePickerRow(
    val store: Store,
    val neededCount: Int,
    /**
     * Names of priority-flagged items currently needed at this store, in
     * insertion order. Empty if none. Drives the per-store "⚠ N critical"
     * badge AND the cross-store banner (caller dedupes across rows).
     */
    val criticalItemNames: List<String>,
)

interface ShoppingRepository {
    /**
     * The shopping list for a single store, in that store's aisle order.
     *
     * Includes items that are needed, staples, or purchased within the current
     * session. The session window (`sessionStartMs`) is supplied by the caller
     * -- the ViewModel captures it once at construction so re-entering the
     * screen creates a fresh window and previously purchased non-staples drop
     * out (cross-store sync semantics: bought somewhere else, no longer shown).
     *
     * @param sessionStartMs millis since epoch. Pass [Long.MAX_VALUE] to
     *   disable the window (only needed/staple items).
     */
    fun shoppingListForStore(storeId: String, sessionStartMs: Long): Flow<List<ShoppingRow>>

    /**
     * One row per live store with needed count + priority names. Drives the
     * Store Picker home screen.
     */
    fun observeStorePickerRows(): Flow<List<StorePickerRow>>
}
