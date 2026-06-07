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
     * means "I bought this at this store" -- doesn't affect any other store
     * the item is tagged to, so checking off milk at Lidl leaves milk's
     * Aldi xref untouched (the user mental model: each shop's row is its
     * own state).
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
