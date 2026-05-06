package com.storehop.app.data.db.relations

import androidx.room.ColumnInfo

data class ShoppingRow(
    @ColumnInfo(name = "id") val itemId: String,
    @ColumnInfo(name = "name") val itemName: String,
    @ColumnInfo(name = "quantity") val quantity: String?,
    @ColumnInfo(name = "notes") val notes: String?,
    @ColumnInfo(name = "isNeeded") val isNeeded: Boolean,
    @ColumnInfo(name = "cat_id") val categoryId: String?,
    @ColumnInfo(name = "cat_name") val categoryName: String?,
    @ColumnInfo(name = "cat_icon") val categoryIcon: String?,
    @ColumnInfo(name = "displayOrder") val displayOrder: Int?,
)
