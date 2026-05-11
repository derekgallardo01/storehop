package com.storehop.app.data.db.relations

import androidx.room.ColumnInfo

/**
 * One row per (item, store) pair that's relevant to the Store Picker badges
 * right now -- either currently needed at this store, OR purchased within
 * the active shopping session (so the picker can render an "✓ All set"
 * affirmation instead of a misleading "Nothing needed" once the user has
 * picked up everything that was on the list this trip).
 *
 * Multiple rows for the same item if it's tagged to multiple stores. The
 * picker repo groups by storeId and produces a
 * [com.storehop.app.data.repository.StorePickerRow] per live store.
 */
data class StorePickerItemRow(
    @ColumnInfo(name = "storeId") val storeId: String,
    @ColumnInfo(name = "itemId") val itemId: String,
    @ColumnInfo(name = "itemName") val itemName: String,
    @ColumnInfo(name = "isPriority") val isPriority: Boolean,
    @ColumnInfo(name = "isNeeded") val isNeeded: Boolean,
    @ColumnInfo(name = "isStaple") val isStaple: Boolean,
    /**
     * 1 iff `isx.lastPurchasedAt` falls inside the current session window,
     * computed in SQL so the repo doesn't need to re-thread `sessionStartMs`
     * to partition staples. Used by the picker repo: a staple that hasn't
     * been bought this session still counts as "on the list" for chip /
     * banner purposes; one bought this session moves to picked-up.
     */
    @ColumnInfo(name = "purchasedThisSession") val purchasedThisSession: Boolean,
)
