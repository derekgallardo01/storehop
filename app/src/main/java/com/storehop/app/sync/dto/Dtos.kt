package com.storehop.app.sync.dto

import androidx.annotation.Keep

/**
 * Firestore DTOs.
 *
 * Each class has:
 * - `@Keep` so R8 doesn't strip the no-arg constructor / setters Firestore's
 *   reflective deserialization needs.
 * - **No-arg constructor with `var` fields and JVM defaults**, NOT data classes
 *   — Firestore's `toObject()` / `fromObject()` requires this shape.
 * - `userId` field for defense-in-depth: path-based scoping (`/users/{uid}/...`)
 *   is the source of truth, but having it in the doc body makes audits trivial.
 * - All timestamps as `Long` epoch millis (matches the entity columns; avoids
 *   Firestore's `Timestamp` type's nanosecond precision and the `@ServerTimestamp`
 *   semantics that don't fit our last-write-wins model).
 *
 * `pendingSync` is intentionally **omitted** from DTOs — it's a local-only flag
 * tracking what hasn't been pushed yet. The cloud doesn't care.
 */

@Keep
class ItemDto(
    var id: String = "",
    var name: String = "",
    var categoryId: String? = null,
    var notes: String? = null,
    var quantity: String? = null,
    var isNeeded: Boolean = true,
    var lastPurchasedAt: Long? = null,
    var userId: String = "",
    var createdAt: Long = 0L,
    var updatedAt: Long = 0L,
    var deletedAt: Long? = null,
)

@Keep
class CategoryDto(
    var id: String = "",
    var name: String = "",
    var nameKey: String? = null,
    var icon: String? = null,
    var isArchived: Boolean = false,
    var isSeeded: Boolean = false,
    var userId: String = "",
    var createdAt: Long = 0L,
    var updatedAt: Long = 0L,
    var deletedAt: Long? = null,
)

@Keep
class StoreDto(
    var id: String = "",
    var name: String = "",
    var colorArgb: Int? = null,
    var isArchived: Boolean = false,
    var isSeeded: Boolean = false,
    var userId: String = "",
    var createdAt: Long = 0L,
    var updatedAt: Long = 0L,
    var deletedAt: Long? = null,
)

@Keep
class ItemStoreXrefDto(
    var itemId: String = "",
    var storeId: String = "",
    var userId: String = "",
    var createdAt: Long = 0L,
    var updatedAt: Long = 0L,
    var deletedAt: Long? = null,
)

@Keep
class StoreCategoryOrderDto(
    var storeId: String = "",
    var categoryId: String = "",
    var displayOrder: Int = 0,
    var isSeeded: Boolean = false,
    var userId: String = "",
    var createdAt: Long = 0L,
    var updatedAt: Long = 0L,
    var deletedAt: Long? = null,
)

@Keep
class PurchaseRecordDto(
    var id: String = "",
    var itemId: String = "",
    var storeId: String? = null,
    var purchasedAt: Long = 0L,
    var userId: String = "",
    var createdAt: Long = 0L,
    var updatedAt: Long = 0L,
    var deletedAt: Long? = null,
)
