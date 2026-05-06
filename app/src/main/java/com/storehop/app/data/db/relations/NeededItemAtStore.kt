package com.storehop.app.data.db.relations

import androidx.room.ColumnInfo

/**
 * Flat row of one item that is currently needed at one store. Multiple rows
 * for the same item if it's tagged to multiple stores.
 *
 * Used by the Store Picker to compute per-store needed counts and per-store
 * priority badges from a single Flow, avoiding N+1 per-store queries.
 */
data class NeededItemAtStore(
    @ColumnInfo(name = "storeId") val storeId: String,
    @ColumnInfo(name = "itemId") val itemId: String,
    @ColumnInfo(name = "itemName") val itemName: String,
    @ColumnInfo(name = "isPriority") val isPriority: Boolean,
)
