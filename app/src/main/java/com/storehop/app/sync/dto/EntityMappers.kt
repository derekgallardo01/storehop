package com.storehop.app.sync.dto

import com.storehop.app.data.entity.Category
import com.storehop.app.data.entity.Item
import com.storehop.app.data.entity.ItemStoreXref
import com.storehop.app.data.entity.PurchaseRecord
import com.storehop.app.data.entity.Store
import com.storehop.app.data.entity.StoreCategoryOrder

/**
 * Entity → DTO conversion for the push side. The reverse direction lands in M5
 * for the pull side.
 *
 * `pendingSync` doesn't cross over — it's a local-only flag.
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
)

fun ItemStoreXref.toDto() = ItemStoreXrefDto(
    itemId = itemId,
    storeId = storeId,
    userId = userId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
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
