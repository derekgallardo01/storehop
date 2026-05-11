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
        Index("householdId"),
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
    val brand: String? = null,
    val imageUrl: String? = null,
    @ColumnInfo(defaultValue = "0") val isStaple: Boolean = false,
    @ColumnInfo(defaultValue = "0") val isPriority: Boolean = false,
    /**
     * v0.7.0 multi-user: access scope. Every entity belongs to one household.
     * Single-user households have `householdId == userId` (auto-migrated on
     * first launch). When a user joins another household via invite, every
     * write here uses the new householdId so the data lands in the shared
     * Firestore path. `userId` stays as creator/audit metadata.
     *
     * Defaults to "" for backward-compat with pre-v0.7.0 test fixtures; the
     * v7→v8 migration backfills existing rows with `householdId = userId`.
     */
    @ColumnInfo(defaultValue = "''") val householdId: String = "",
)
