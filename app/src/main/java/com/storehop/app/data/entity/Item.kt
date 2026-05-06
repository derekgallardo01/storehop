package com.storehop.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "items",
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("categoryId"),
        Index("isNeeded"),
        Index("name"),
        Index("userId"),
        Index("deletedAt"),
    ],
)
data class Item(
    @PrimaryKey val id: String,
    val name: String,
    val categoryId: String?,
    val notes: String?,
    val quantity: String?,
    val isNeeded: Boolean,
    val lastPurchasedAt: Long?,
    val userId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    @ColumnInfo(defaultValue = "1") val pendingSync: Boolean = true,
)
