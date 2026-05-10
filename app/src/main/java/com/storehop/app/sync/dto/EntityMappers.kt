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
)

// ---- DTO → Entity (pull side, v0.4) -------------------------------------
//
// pendingSync is hardcoded to `false`: pulled rows are already in the cloud,
// so the next sync cycle has nothing to push for them. Setting it to `true`
// would cause every pull to immediately re-push the same data, potentially
// overwriting newer cloud edits made by another device between pull start
// and push.

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
)

/**
 * The Firestore collection name for each entity. Documents live under
 * `/users/{uid}/<collection>/<docId>`.
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
