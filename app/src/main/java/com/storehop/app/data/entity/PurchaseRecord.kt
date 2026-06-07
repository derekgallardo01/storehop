package com.storehop.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "purchase_records",
    indices = [
        Index("itemId"),
        Index("storeId"),
        Index("purchasedAt"),
        Index("userId"),
        Index("householdId"),
        Index("deletedAt"),
    ],
)
data class PurchaseRecord(
    @PrimaryKey val id: String,
    val itemId: String,
    val storeId: String?,
    val purchasedAt: Long,
    val userId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    @ColumnInfo(defaultValue = "1") val pendingSync: Boolean = true,
    /** v0.7.0 multi-user household access scope. See [Item.householdId]. */
    @ColumnInfo(defaultValue = "''") val householdId: String = "",
)
