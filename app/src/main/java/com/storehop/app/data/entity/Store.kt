package com.storehop.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "stores",
    indices = [
        // Non-unique on (userId, name) -- mirror of categories. DB-level
        // uniqueness was dropped at schema v6 because the old UNIQUE index
        // included tombstones, blocking name reuse after a soft-delete.
        // Application-layer guards in `StoreRepositoryImpl` (add: rejects
        // alive collisions, resurrects tombstones; rename: rejects alive
        // collisions only) plus the `withTransaction` serialization keep
        // single-device state consistent. See Category.kt for the full
        // rationale.
        Index(value = ["userId", "name"]),
        Index("userId"),
        Index("householdId"),
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
    /** v0.7.0 multi-user household access scope. See [Item.householdId]. */
    @ColumnInfo(defaultValue = "''") val householdId: String = "",
)
