package com.storehop.app.data.entity

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
)
