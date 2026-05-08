import Foundation

/// Firestore wire-format DTOs.
///
/// Mirrors Android `Dtos.kt` field-for-field — same names, same types, same
/// nullability — so a document written from either platform decodes
/// correctly on the other. Encoded via `FirebaseFirestore`'s Codable
/// integration (Firebase 11+ folded the former `FirebaseFirestoreSwift`
/// product into the main module); field names are the property names by
/// default, no keyEncodingStrategy override needed.
///
/// `pendingSync` is intentionally **omitted** — it's a local-only flag
/// tracking what hasn't been pushed yet. The cloud doesn't care.
///
/// All timestamps as `Int64` epoch millis (matches the entity columns;
/// avoids Firestore's `Timestamp` nanosecond precision and the
/// `@ServerTimestamp` semantics that don't fit our last-write-wins model).
struct ItemDto: Codable, Sendable, Equatable {
    var id: String
    var name: String
    var categoryId: String?
    var notes: String?
    var quantity: String?
    var isNeeded: Bool
    var lastPurchasedAt: Int64?
    var userId: String
    var createdAt: Int64
    var updatedAt: Int64
    var deletedAt: Int64?
    var brand: String?
    var imageUrl: String?
    var isStaple: Bool
    var isPriority: Bool
}

struct CategoryDto: Codable, Sendable, Equatable {
    var id: String
    var name: String
    var nameKey: String?
    var icon: String?
    var isArchived: Bool
    var isSeeded: Bool
    var userId: String
    var createdAt: Int64
    var updatedAt: Int64
    var deletedAt: Int64?
}

struct StoreDto: Codable, Sendable, Equatable {
    var id: String
    var name: String
    /// Android stores ARGB as Int (32-bit). Encoded as `Int64` here so the
    /// JSON number serialization matches across platforms — Firestore
    /// preserves the value losslessly either way.
    var colorArgb: Int64?
    var isArchived: Bool
    var isSeeded: Bool
    var userId: String
    var createdAt: Int64
    var updatedAt: Int64
    var deletedAt: Int64?
    var displayOrder: Int
}

struct ItemStoreXrefDto: Codable, Sendable, Equatable {
    var itemId: String
    var storeId: String
    var userId: String
    var createdAt: Int64
    var updatedAt: Int64
    var deletedAt: Int64?
    var isNeeded: Bool
    var lastPurchasedAt: Int64?
}

struct StoreCategoryOrderDto: Codable, Sendable, Equatable {
    var storeId: String
    var categoryId: String
    var displayOrder: Int
    var isSeeded: Bool
    var userId: String
    var createdAt: Int64
    var updatedAt: Int64
    var deletedAt: Int64?
}

struct PurchaseRecordDto: Codable, Sendable, Equatable {
    var id: String
    var itemId: String
    var storeId: String?
    var purchasedAt: Int64
    var userId: String
    var createdAt: Int64
    var updatedAt: Int64
    var deletedAt: Int64?
}
