package com.storehop.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "categories",
    indices = [
        // Non-unique on (userId, name) -- the index is here for query speed
        // (findByName / findAnyByName lookups are hot during CSV import and
        // every add/rename). DB-level uniqueness was intentionally dropped at
        // schema v6: the old UNIQUE index counted tombstones, so a deleted
        // "Pets" blocked the user from renaming a different category to
        // "Pets" -- a real bug Mike hit after his v0.5.4 import. Uniqueness is
        // now enforced at the application layer (`CategoryRepositoryImpl`'s
        // add/rename guards + the `withTransaction` wrap that serializes
        // concurrent mutations); cross-device sync conflicts have always been
        // handled separately by the pull-side merge engine.
        Index(value = ["userId", "name"]),
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
    /**
     * Position on the Manage Categories screen. Drag-and-drop reorder on
     * that screen rewrites this for affected rows. New categories get
     * `MAX(displayOrder) + 1` so they append. This is the GLOBAL order;
     * per-store aisle order lives in `store_category_order` and is
     * independent.
     */
    @ColumnInfo(defaultValue = "0") val displayOrder: Int = 0,
)
