package com.storehop.app.data.entity

import androidx.room.ColumnInfo
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
    @ColumnInfo(defaultValue = "1") val pendingSync: Boolean = true,
    /**
     * User-controlled position on the Store Picker. Drag-and-drop on the
     * Shop screen rewrites this for every affected row. New stores are
     * assigned `MAX(displayOrder) + 1` so they append to the end; the user
     * can drag them where they want. Lower number sorts higher.
     */
    @ColumnInfo(defaultValue = "0") val displayOrder: Int = 0,
)
