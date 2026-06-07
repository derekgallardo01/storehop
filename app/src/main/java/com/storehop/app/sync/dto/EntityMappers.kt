package com.storehop.app.sync.dto

import com.storehop.app.data.entity.Category
import com.storehop.app.data.entity.Item
import com.storehop.app.data.entity.ItemStoreXref
import com.storehop.app.data.entity.PurchaseRecord
import com.storehop.app.data.entity.Store
import com.storehop.app.data.entity.StoreCategoryOrder

/**
 * Entity → DTO conversion for the push side, and DTO → entity for the pull side
 * (v0.4). DTOs omit `pendingSync` (cloud doesn't care); inverse mappers
 * always set it to `false` because pulled rows are by definition already in
 * the cloud, so they don't need to be re-pushed.
 *
 * v0.7.0: every mapper carries `householdId` in both directions. Older
 * Firestore documents (written by v0.6.x clients) deserialise with
 * `householdId = ""`; the v7→v8 Room migration backfills these to
 * `householdId = userId` on the next pull cycle.
 */

fun Item.toDto() = ItemDto(
    id = id,
    name = name,
    categoryId = categoryId,
    notes = notes,
    quantity = quantity,
    isNeeded = isNeeded,
    lastPurchasedAt = lastPurchasedAt,
    userId = userId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    brand = brand,
    imageUrl = imageUrl,
    isStaple = isStaple,
    isPriority = isPriority,
    householdId = householdId,
)

fun Category.toDto() = CategoryDto(
    id = id,
    name = name,
    nameKey = nameKey,
    icon = icon,
    isArchived = isArchived,
    isSeeded = isSeeded,
    userId = userId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    displayOrder = displayOrder,
    householdId = householdId,
)

fun Store.toDto() = StoreDto(
    id = id,
    name = name,
    colorArgb = colorArgb,
    isArchived = isArchived,
    isSeeded = isSeeded,
    userId = userId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    displayOrder = displayOrder,
    householdId = householdId,
    isOneOff = isOneOff,
)

fun ItemStoreXref.toDto() = ItemStoreXrefDto(
    itemId = itemId,
    storeId = storeId,
    userId = userId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    isNeeded = isNeeded,
    lastPurchasedAt = lastPurchasedAt,
    householdId = householdId,
)

fun StoreCategoryOrder.toDto() = StoreCategoryOrderDto(
    storeId = storeId,
    categoryId = categoryId,
    displayOrder = displayOrder,
    isSeeded = isSeeded,
    userId = userId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    householdId = householdId,
)

fun PurchaseRecord.toDto() = PurchaseRecordDto(
    id = id,
    itemId = itemId,
    storeId = storeId,
    purchasedAt = purchasedAt,
    userId = userId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    householdId = householdId,
)

// ---- DTO → Entity (pull side, v0.4) -------------------------------------
//
// pendingSync is hardcoded to `false`: pulled rows are already in the cloud,
// so the next sync cycle has nothing to push for them. Setting it to `true`
// would cause every pull to immediately re-push the same data, potentially
// overwriting newer cloud edits made by another device between pull start
// and push.
//
// v0.7.0: `householdId` falls back to `userId` when the cloud document was
// written by a v0.6.x client and so doesn't carry the field. The schema-v8
// migration applies the same fallback for pre-migration local rows; the
// two paths converge on `householdId == userId` for single-member households.

fun ItemDto.toEntity() = Item(
    id = id,
    name = name,
    categoryId = categoryId,
    notes = notes,
    quantity = quantity,
    isNeeded = isNeeded,
    lastPurchasedAt = lastPurchasedAt,
    userId = userId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    pendingSync = false,
    brand = brand,
    imageUrl = imageUrl,
    isStaple = isStaple,
    isPriority = isPriority,
    householdId = householdId.ifEmpty { userId },
)

fun CategoryDto.toEntity() = Category(
    id = id,
    name = name,
    nameKey = nameKey,
    icon = icon,
    isArchived = isArchived,
    isSeeded = isSeeded,
    userId = userId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    pendingSync = false,
    displayOrder = displayOrder,
    householdId = householdId.ifEmpty { userId },
)

fun StoreDto.toEntity() = Store(
    id = id,
    name = name,
    colorArgb = colorArgb,
    isArchived = isArchived,
    isSeeded = isSeeded,
    userId = userId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    pendingSync = false,
    displayOrder = displayOrder,
    householdId = householdId.ifEmpty { userId },
    isOneOff = isOneOff,
)

fun ItemStoreXrefDto.toEntity() = ItemStoreXref(
    itemId = itemId,
    storeId = storeId,
    userId = userId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    pendingSync = false,
    isNeeded = isNeeded,
    lastPurchasedAt = lastPurchasedAt,
    householdId = householdId.ifEmpty { userId },
)

fun StoreCategoryOrderDto.toEntity() = StoreCategoryOrder(
    storeId = storeId,
    categoryId = categoryId,
    displayOrder = displayOrder,
    isSeeded = isSeeded,
    userId = userId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    pendingSync = false,
    householdId = householdId.ifEmpty { userId },
)

fun PurchaseRecordDto.toEntity() = PurchaseRecord(
    id = id,
    itemId = itemId,
    storeId = storeId,
    purchasedAt = purchasedAt,
    userId = userId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    pendingSync = false,
    householdId = householdId.ifEmpty { userId },
)

/**
 * The Firestore collection name for each entity. Documents live under
 * `/users/{householdId}/<collection>/<docId>`.
 *
 * Note: the `users` segment name is preserved from the v0.4 path. v0.7.0
 * re-interprets `{users/x}` semantically as `{households/x}` — for single-
 * member households `householdId == userId`, so existing cloud data
 * persists at the same wire path. Renaming the collection to `households`
 * would orphan every existing user's data, which is unacceptable for
 * Play Closed Testing roll-out; we keep the legacy name and rely on the
 * new `householdId` field inside each document for cross-household
 * isolation in the upcoming Firestore security rules.
 */
object SyncCollections {
    const val ITEMS = "items"
    const val CATEGORIES = "categories"
    const val STORES = "stores"
    const val ITEM_STORE_XREFS = "item_store_xref"
    const val STORE_CATEGORY_ORDERS = "store_category_order"
    const val PURCHASE_RECORDS = "purchase_records"
}

/**
 * Composite-PK helpers for documents whose Firestore docId can't just be `entity.id`.
 */
fun ItemStoreXref.docId(): String = "${itemId}__${storeId}"
fun StoreCategoryOrder.docId(): String = "${storeId}__${categoryId}"
