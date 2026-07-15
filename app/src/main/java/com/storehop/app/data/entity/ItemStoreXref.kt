package com.storehop.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "item_store_xref",
    primaryKeys = ["itemId", "storeId"],
    foreignKeys = [
        ForeignKey(
            entity = Item::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Store::class,
            parentColumns = ["id"],
            childColumns = ["storeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("storeId"),
        Index("userId"),
        Index("householdId"),
        Index("deletedAt"),
    ],
)
data class ItemStoreXref(
    val itemId: String,
    val storeId: String,
    val userId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    @ColumnInfo(defaultValue = "1") val pendingSync: Boolean = true,
    /**
     * Per-store need state. True means "still need to buy this here." False
     * means "I bought this here."
     *
     * The *storage* is per-(item, store), but the *check-off cascade* is
     * global: one shopping trip satisfies the need everywhere. Buying milk at
     * Lidl cascades `isNeeded = 0` to milk's Aldi xref too
     * ([ItemStoreXrefDao.markPurchasedAcrossAllStores]); un-buying it at Lidl
     * cascades `isNeeded = 1` back across every tagged store
     * ([ItemStoreXrefDao.markNeededAcrossAllStores]). The per-store column
     * still lets the strike-through window and staple visibility differ per
     * screen, but "needed" is effectively one state shared across the item's
     * stores.
     */
    @ColumnInfo(defaultValue = "1") val isNeeded: Boolean = true,
    /**
     * When this specific (item, store) pair was last marked purchased.
     * Powers the within-session strike-through window on the Shop-at-Store
     * screen so the user can undo a mis-tap before leaving the store.
     */
    val lastPurchasedAt: Long? = null,
    /** v0.7.0 multi-user household access scope. See [Item.householdId]. */
    @ColumnInfo(defaultValue = "''") val householdId: String = "",
)
