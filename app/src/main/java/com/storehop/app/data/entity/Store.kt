package com.storehop.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "stores",
    indices = [
        Index(value = ["userId", "name"], unique = true),
        Index("userId"),
        Index("deletedAt"),
    ],
)
data class Store(
    @PrimaryKey val id: String,
    val name: String,
    val colorArgb: Int?,
    val isArchived: Boolean,
    val isSeeded: Boolean,
    val userId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)
