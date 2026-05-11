import Foundation

/// Record ↔ DTO mapping for the push (record → DTO) and pull (DTO → record)
/// sides. Pull mappers always set `pendingSync = false`: pulled rows are
/// already in the cloud, so the next sync cycle has nothing to push for
/// them. Setting `pendingSync = true` would cause every pull to immediately
/// re-push, potentially overwriting newer cloud edits made by another
/// device between pull start and push.
///
/// v0.7.0: every mapper carries `householdId` in both directions. Older
/// Firestore documents (written by v0.6.x clients) deserialise with
/// `householdId = ""`; the DTO → entity mappers fall back to `userId` in
/// that case so the schema-v8 invariant (`householdId == userId` for
/// single-member households) holds.

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
            isPriority: isPriority,
            householdId: householdId
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
            isPriority: isPriority,
            householdId: householdId.isEmpty ? userId : householdId
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
            deletedAt: deletedAt,
            displayOrder: displayOrder,
            householdId: householdId
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
            pendingSync: false,
            displayOrder: displayOrder,
            householdId: householdId.isEmpty ? userId : householdId
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
            displayOrder: displayOrder,
            householdId: householdId
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
            displayOrder: displayOrder,
            householdId: householdId.isEmpty ? userId : householdId
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
            lastPurchasedAt: lastPurchasedAt,
            householdId: householdId
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
            lastPurchasedAt: lastPurchasedAt,
            householdId: householdId.isEmpty ? userId : householdId
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
            deletedAt: deletedAt,
            householdId: householdId
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
            pendingSync: false,
            householdId: householdId.isEmpty ? userId : householdId
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
            deletedAt: deletedAt,
            householdId: householdId
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
            pendingSync: false,
            householdId: householdId.isEmpty ? userId : householdId
        )
    }
}

/// Firestore collection names. Documents live at
/// `/users/{householdId}/<collection>/<docId>`. Mirrors Android
/// `SyncCollections`.
///
/// Note: the `users` segment name is preserved from the v0.4 path. v0.7.0
/// re-interprets `{users/x}` semantically as `{households/x}` — for
/// single-member households `householdId == userId`, so existing cloud
/// data persists at the same wire path. Renaming the collection to
/// `households` would orphan every existing user's data, which is
/// unacceptable for roll-out; we keep the legacy name and rely on the
/// new `householdId` field inside each document for cross-household
/// isolation in the upcoming Firestore security rules.
enum SyncCollections {
    static let items = "items"
    static let categories = "categories"
    static let stores = "stores"
    static let itemStoreXrefs = "item_store_xref"
    static let storeCategoryOrders = "store_category_order"
    static let purchaseRecords = "purchase_records"
}
