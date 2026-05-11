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
 * - `userId` field for creator/audit traceability AND `householdId` for the
 *   v0.7.0 multi-user access scope. For single-member households the two
 *   columns hold the same value, so the wire format stays backward-compatible
 *   with v0.6.x DTOs that lacked `householdId` (a missing field deserialises
 *   to "", which the schema-v8 migration then backfills to `userId`).
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
    var brand: String? = null,
    var imageUrl: String? = null,
    var isStaple: Boolean = false,
    var isPriority: Boolean = false,
    var householdId: String = "",
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
    // v0.6.4: position on the Manage Categories screen. Default 0 keeps
    // older docs deserialising cleanly (their backfill happened during the
    // schema v6 -> v7 migration on each device).
    var displayOrder: Int = 0,
    var householdId: String = "",
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
    var displayOrder: Int = 0,
    var householdId: String = "",
)

@Keep
class ItemStoreXrefDto(
    var itemId: String = "",
    var storeId: String = "",
    var userId: String = "",
    var createdAt: Long = 0L,
    var updatedAt: Long = 0L,
    var deletedAt: Long? = null,
    var isNeeded: Boolean = true,
    var lastPurchasedAt: Long? = null,
    var householdId: String = "",
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
    var householdId: String = "",
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
    var householdId: String = "",
)
