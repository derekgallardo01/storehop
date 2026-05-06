package com.storehop.app.data.entity

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
)
