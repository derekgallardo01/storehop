import Foundation

/// Record ↔ DTO mapping for the push (record → DTO) and pull (DTO → record)
/// sides. Pull mappers always set `pendingSync = false`: pulled rows are
/// already in the cloud, so the next sync cycle has nothing to push for
/// them. Setting `pendingSync = true` would cause every pull to immediately
/// re-push, potentially overwriting newer cloud edits made by another
/// device between pull start and push.

extension Item {
    func toDto() -> ItemDto {
        ItemDto(
            id: id,
            name: name,
            categoryId: categoryId,
            notes: notes,
            quantity: quantity,
            isNeeded: isNeeded,
            lastPurchasedAt: lastPurchasedAt,
            userId: userId,
            createdAt: createdAt,
            updatedAt: updatedAt,
            deletedAt: deletedAt,
            brand: brand,
            imageUrl: imageUrl,
            isStaple: isStaple,
            isPriority: isPriority
        )
    }
}

extension ItemDto {
    func toEntity() -> Item {
        Item(
            id: id,
            name: name,
            categoryId: categoryId,
            notes: notes,
            quantity: quantity,
            isNeeded: isNeeded,
            lastPurchasedAt: lastPurchasedAt,
            userId: userId,
            createdAt: createdAt,
            updatedAt: updatedAt,
            deletedAt: deletedAt,
            pendingSync: false,
            brand: brand,
            imageUrl: imageUrl,
            isStaple: isStaple,
            isPriority: isPriority
        )
    }
}

extension Category {
    func toDto() -> CategoryDto {
        CategoryDto(
            id: id,
            name: name,
            nameKey: nameKey,
            icon: icon,
            isArchived: isArchived,
            isSeeded: isSeeded,
            userId: userId,
            createdAt: createdAt,
            updatedAt: updatedAt,
            deletedAt: deletedAt
        )
    }
}

extension CategoryDto {
    func toEntity() -> Category {
        Category(
            id: id,
            name: name,
            nameKey: nameKey,
            icon: icon,
            isArchived: isArchived,
            isSeeded: isSeeded,
            userId: userId,
            createdAt: createdAt,
            updatedAt: updatedAt,
            deletedAt: deletedAt,
            pendingSync: false
        )
    }
}

extension Store {
    func toDto() -> StoreDto {
        StoreDto(
            id: id,
            name: name,
            colorArgb: colorArgb,
            isArchived: isArchived,
            isSeeded: isSeeded,
            userId: userId,
            createdAt: createdAt,
            updatedAt: updatedAt,
            deletedAt: deletedAt,
            displayOrder: displayOrder
        )
    }
}

extension StoreDto {
    func toEntity() -> Store {
        Store(
            id: id,
            name: name,
            colorArgb: colorArgb,
            isArchived: isArchived,
            isSeeded: isSeeded,
            userId: userId,
            createdAt: createdAt,
            updatedAt: updatedAt,
            deletedAt: deletedAt,
            pendingSync: false,
            displayOrder: displayOrder
        )
    }
}

extension ItemStoreXref {
    func toDto() -> ItemStoreXrefDto {
        ItemStoreXrefDto(
            itemId: itemId,
            storeId: storeId,
            userId: userId,
            createdAt: createdAt,
            updatedAt: updatedAt,
            deletedAt: deletedAt,
            isNeeded: isNeeded,
            lastPurchasedAt: lastPurchasedAt
        )
    }

    /// Composite-PK doc id: `<itemId>__<storeId>`. Mirrors Android.
    var docId: String { "\(itemId)__\(storeId)" }
}

extension ItemStoreXrefDto {
    func toEntity() -> ItemStoreXref {
        ItemStoreXref(
            itemId: itemId,
            storeId: storeId,
            userId: userId,
            createdAt: createdAt,
            updatedAt: updatedAt,
            deletedAt: deletedAt,
            pendingSync: false,
            isNeeded: isNeeded,
            lastPurchasedAt: lastPurchasedAt
        )
    }
}

extension StoreCategoryOrder {
    func toDto() -> StoreCategoryOrderDto {
        StoreCategoryOrderDto(
            storeId: storeId,
            categoryId: categoryId,
            displayOrder: displayOrder,
            isSeeded: isSeeded,
            userId: userId,
            createdAt: createdAt,
            updatedAt: updatedAt,
            deletedAt: deletedAt
        )
    }

    /// Composite-PK doc id: `<storeId>__<categoryId>`. Mirrors Android.
    var docId: String { "\(storeId)__\(categoryId)" }
}

extension StoreCategoryOrderDto {
    func toEntity() -> StoreCategoryOrder {
        StoreCategoryOrder(
            storeId: storeId,
            categoryId: categoryId,
            displayOrder: displayOrder,
            isSeeded: isSeeded,
            userId: userId,
            createdAt: createdAt,
            updatedAt: updatedAt,
            deletedAt: deletedAt,
            pendingSync: false
        )
    }
}

extension PurchaseRecord {
    func toDto() -> PurchaseRecordDto {
        PurchaseRecordDto(
            id: id,
            itemId: itemId,
            storeId: storeId,
            purchasedAt: purchasedAt,
            userId: userId,
            createdAt: createdAt,
            updatedAt: updatedAt,
            deletedAt: deletedAt
        )
    }
}

extension PurchaseRecordDto {
    func toEntity() -> PurchaseRecord {
        PurchaseRecord(
            id: id,
            itemId: itemId,
            storeId: storeId,
            purchasedAt: purchasedAt,
            userId: userId,
            createdAt: createdAt,
            updatedAt: updatedAt,
            deletedAt: deletedAt,
            pendingSync: false
        )
    }
}

/// Firestore collection names. Documents live at
/// `/users/{uid}/<collection>/<docId>`. Mirrors Android `SyncCollections`.
enum SyncCollections {
    static let items = "items"
    static let categories = "categories"
    static let stores = "stores"
    static let itemStoreXrefs = "item_store_xref"
    static let storeCategoryOrders = "store_category_order"
    static let purchaseRecords = "purchase_records"
}
