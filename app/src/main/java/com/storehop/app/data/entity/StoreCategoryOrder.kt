package com.storehop.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "store_category_order",
    primaryKeys = ["storeId", "categoryId"],
    foreignKeys = [
        ForeignKey(
            entity = Store::class,
            parentColumns = ["id"],
            childColumns = ["storeId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("categoryId"),
        Index("userId"),
        Index("deletedAt"),
    ],
)
data class StoreCategoryOrder(
    val storeId: String,
    val categoryId: String,
    val displayOrder: Int,
    val isSeeded: Boolean,
    val userId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    @ColumnInfo(defaultValue = "1") val pendingSync: Boolean = true,
)
