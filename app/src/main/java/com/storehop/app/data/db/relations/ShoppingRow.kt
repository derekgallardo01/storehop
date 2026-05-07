package com.storehop.app.data.db.relations

import androidx.room.ColumnInfo

data class ShoppingRow(
    @ColumnInfo(name = "id") val itemId: String,
    @ColumnInfo(name = "name") val itemName: String,
    @ColumnInfo(name = "quantity") val quantity: String?,
    @ColumnInfo(name = "notes") val notes: String?,
    @ColumnInfo(name = "isNeeded") val isNeeded: Boolean,
    @ColumnInfo(name = "brand") val brand: String?,
    @ColumnInfo(name = "imageUrl") val imageUrl: String?,
    @ColumnInfo(name = "isPriority") val isPriority: Boolean,
    @ColumnInfo(name = "isStaple") val isStaple: Boolean,
    @ColumnInfo(name = "cat_id") val categoryId: String?,
    @ColumnInfo(name = "cat_name") val categoryName: String?,
    /**
     * Seeded category nameKey (e.g. "cat_produce") for resource-string
     * lookup. Null for user-added categories -- in that case the screen
     * falls back to [categoryName] which is whatever the user typed.
     */
    @ColumnInfo(name = "cat_nameKey") val categoryNameKey: String?,
    @ColumnInfo(name = "cat_icon") val categoryIcon: String?,
    @ColumnInfo(name = "displayOrder") val displayOrder: Int?,
)

