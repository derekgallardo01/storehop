package com.storehop.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "categories",
    indices = [
        Index(value = ["userId", "name"], unique = true),
        Index("userId"),
        Index("deletedAt"),
    ],
)
data class Category(
    @PrimaryKey val id: String,
    val name: String,
    val nameKey: String?,
    val icon: String?,
    val isArchived: Boolean,
    val isSeeded: Boolean,
    val userId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    @ColumnInfo(defaultValue = "1") val pendingSync: Boolean = true,
)
