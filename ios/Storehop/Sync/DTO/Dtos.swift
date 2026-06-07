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
/// Every DTO carries both `userId` (creator/audit traceability) AND
/// `householdId` (v0.7.0 multi-user access scope). For single-member
/// households the two columns hold the same value, so the wire format
/// stays backward-compatible with v0.6.x DTOs that lacked `householdId`.
///
/// **Backward-compat note (v0.8.1.2 bug fix):** Swift's *synthesized*
/// Codable decoder does NOT respect property-level default values —
/// `var householdId: String = ""` still throws `keyNotFound` if the
/// Firestore document has no `householdId` field. To stay tolerant of
/// pre-v0.7.0 documents (Android wrote those before the field existed)
/// every DTO has a custom `init(from decoder:)` that uses
/// `decodeIfPresent(...) ?? default` for the defaulted columns. Without
/// this, a single legacy doc breaks the entire pull → "Cloud sync
/// incomplete" banner that no retry can clear.
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
    var householdId: String = ""

    enum CodingKeys: String, CodingKey {
        case id, name, categoryId, notes, quantity, isNeeded, lastPurchasedAt
        case userId, createdAt, updatedAt, deletedAt, brand, imageUrl
        case isStaple, isPriority, householdId
    }

    init(id: String, name: String, categoryId: String?, notes: String?,
         quantity: String?, isNeeded: Bool, lastPurchasedAt: Int64?,
         userId: String, createdAt: Int64, updatedAt: Int64, deletedAt: Int64?,
         brand: String?, imageUrl: String?, isStaple: Bool, isPriority: Bool,
         householdId: String = "") {
        self.id = id; self.name = name; self.categoryId = categoryId
        self.notes = notes; self.quantity = quantity; self.isNeeded = isNeeded
        self.lastPurchasedAt = lastPurchasedAt; self.userId = userId
        self.createdAt = createdAt; self.updatedAt = updatedAt
        self.deletedAt = deletedAt; self.brand = brand
        self.imageUrl = imageUrl; self.isStaple = isStaple
        self.isPriority = isPriority; self.householdId = householdId
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(String.self, forKey: .id) ?? ""
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        categoryId = try c.decodeIfPresent(String.self, forKey: .categoryId)
        notes = try c.decodeIfPresent(String.self, forKey: .notes)
        quantity = try c.decodeIfPresent(String.self, forKey: .quantity)
        isNeeded = try c.decodeIfPresent(Bool.self, forKey: .isNeeded) ?? false
        lastPurchasedAt = try c.decodeIfPresent(Int64.self, forKey: .lastPurchasedAt)
        userId = try c.decodeIfPresent(String.self, forKey: .userId) ?? ""
        createdAt = try c.decodeIfPresent(Int64.self, forKey: .createdAt) ?? 0
        updatedAt = try c.decodeIfPresent(Int64.self, forKey: .updatedAt) ?? 0
        deletedAt = try c.decodeIfPresent(Int64.self, forKey: .deletedAt)
        brand = try c.decodeIfPresent(String.self, forKey: .brand)
        imageUrl = try c.decodeIfPresent(String.self, forKey: .imageUrl)
        isStaple = try c.decodeIfPresent(Bool.self, forKey: .isStaple) ?? false
        isPriority = try c.decodeIfPresent(Bool.self, forKey: .isPriority) ?? false
        householdId = try c.decodeIfPresent(String.self, forKey: .householdId) ?? userId
    }
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
    // v0.6.4: position on the Manage Categories screen. Older docs may not
    // have this field; decoder defaults to 0.
    var displayOrder: Int = 0
    var householdId: String = ""

    enum CodingKeys: String, CodingKey {
        case id, name, nameKey, icon, isArchived, isSeeded
        case userId, createdAt, updatedAt, deletedAt, displayOrder, householdId
    }

    init(id: String, name: String, nameKey: String?, icon: String?,
         isArchived: Bool, isSeeded: Bool, userId: String,
         createdAt: Int64, updatedAt: Int64, deletedAt: Int64?,
         displayOrder: Int = 0, householdId: String = "") {
        self.id = id; self.name = name; self.nameKey = nameKey
        self.icon = icon; self.isArchived = isArchived
        self.isSeeded = isSeeded; self.userId = userId
        self.createdAt = createdAt; self.updatedAt = updatedAt
        self.deletedAt = deletedAt; self.displayOrder = displayOrder
        self.householdId = householdId
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(String.self, forKey: .id) ?? ""
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        nameKey = try c.decodeIfPresent(String.self, forKey: .nameKey)
        icon = try c.decodeIfPresent(String.self, forKey: .icon)
        isArchived = try c.decodeIfPresent(Bool.self, forKey: .isArchived) ?? false
        isSeeded = try c.decodeIfPresent(Bool.self, forKey: .isSeeded) ?? false
        userId = try c.decodeIfPresent(String.self, forKey: .userId) ?? ""
        createdAt = try c.decodeIfPresent(Int64.self, forKey: .createdAt) ?? 0
        updatedAt = try c.decodeIfPresent(Int64.self, forKey: .updatedAt) ?? 0
        deletedAt = try c.decodeIfPresent(Int64.self, forKey: .deletedAt)
        displayOrder = try c.decodeIfPresent(Int.self, forKey: .displayOrder) ?? 0
        householdId = try c.decodeIfPresent(String.self, forKey: .householdId) ?? userId
    }
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
    var householdId: String = ""
    /// v0.9.0 — see `Store.isOneOff` for the user-facing semantics.
    /// Defaults to `false` so pushes from pre-v0.9 devices (which omit
    /// the field entirely) deserialize as regular stores. Round-trips
    /// to Firestore via the normal LWW path; multi-device households
    /// converge to whichever device last wrote the flag.
    var isOneOff: Bool = false

    enum CodingKeys: String, CodingKey {
        case id, name, colorArgb, isArchived, isSeeded
        case userId, createdAt, updatedAt, deletedAt, displayOrder, householdId, isOneOff
    }

    init(id: String, name: String, colorArgb: Int64?, isArchived: Bool,
         isSeeded: Bool, userId: String, createdAt: Int64, updatedAt: Int64,
         deletedAt: Int64?, displayOrder: Int, householdId: String = "",
         isOneOff: Bool = false) {
        self.id = id; self.name = name; self.colorArgb = colorArgb
        self.isArchived = isArchived; self.isSeeded = isSeeded
        self.userId = userId; self.createdAt = createdAt
        self.updatedAt = updatedAt; self.deletedAt = deletedAt
        self.displayOrder = displayOrder; self.householdId = householdId
        self.isOneOff = isOneOff
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(String.self, forKey: .id) ?? ""
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        colorArgb = try c.decodeIfPresent(Int64.self, forKey: .colorArgb)
        isArchived = try c.decodeIfPresent(Bool.self, forKey: .isArchived) ?? false
        isSeeded = try c.decodeIfPresent(Bool.self, forKey: .isSeeded) ?? false
        userId = try c.decodeIfPresent(String.self, forKey: .userId) ?? ""
        createdAt = try c.decodeIfPresent(Int64.self, forKey: .createdAt) ?? 0
        updatedAt = try c.decodeIfPresent(Int64.self, forKey: .updatedAt) ?? 0
        deletedAt = try c.decodeIfPresent(Int64.self, forKey: .deletedAt)
        displayOrder = try c.decodeIfPresent(Int.self, forKey: .displayOrder) ?? 0
        householdId = try c.decodeIfPresent(String.self, forKey: .householdId) ?? userId
        isOneOff = try c.decodeIfPresent(Bool.self, forKey: .isOneOff) ?? false
    }
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
    var householdId: String = ""

    enum CodingKeys: String, CodingKey {
        case itemId, storeId, userId, createdAt, updatedAt, deletedAt
        case isNeeded, lastPurchasedAt, householdId
    }

    init(itemId: String, storeId: String, userId: String,
         createdAt: Int64, updatedAt: Int64, deletedAt: Int64?,
         isNeeded: Bool, lastPurchasedAt: Int64?, householdId: String = "") {
        self.itemId = itemId; self.storeId = storeId; self.userId = userId
        self.createdAt = createdAt; self.updatedAt = updatedAt
        self.deletedAt = deletedAt; self.isNeeded = isNeeded
        self.lastPurchasedAt = lastPurchasedAt; self.householdId = householdId
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        itemId = try c.decodeIfPresent(String.self, forKey: .itemId) ?? ""
        storeId = try c.decodeIfPresent(String.self, forKey: .storeId) ?? ""
        userId = try c.decodeIfPresent(String.self, forKey: .userId) ?? ""
        createdAt = try c.decodeIfPresent(Int64.self, forKey: .createdAt) ?? 0
        updatedAt = try c.decodeIfPresent(Int64.self, forKey: .updatedAt) ?? 0
        deletedAt = try c.decodeIfPresent(Int64.self, forKey: .deletedAt)
        isNeeded = try c.decodeIfPresent(Bool.self, forKey: .isNeeded) ?? false
        lastPurchasedAt = try c.decodeIfPresent(Int64.self, forKey: .lastPurchasedAt)
        householdId = try c.decodeIfPresent(String.self, forKey: .householdId) ?? userId
    }
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
    var householdId: String = ""

    enum CodingKeys: String, CodingKey {
        case storeId, categoryId, displayOrder, isSeeded
        case userId, createdAt, updatedAt, deletedAt, householdId
    }

    init(storeId: String, categoryId: String, displayOrder: Int,
         isSeeded: Bool, userId: String, createdAt: Int64,
         updatedAt: Int64, deletedAt: Int64?, householdId: String = "") {
        self.storeId = storeId; self.categoryId = categoryId
        self.displayOrder = displayOrder; self.isSeeded = isSeeded
        self.userId = userId; self.createdAt = createdAt
        self.updatedAt = updatedAt; self.deletedAt = deletedAt
        self.householdId = householdId
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        storeId = try c.decodeIfPresent(String.self, forKey: .storeId) ?? ""
        categoryId = try c.decodeIfPresent(String.self, forKey: .categoryId) ?? ""
        displayOrder = try c.decodeIfPresent(Int.self, forKey: .displayOrder) ?? 0
        isSeeded = try c.decodeIfPresent(Bool.self, forKey: .isSeeded) ?? false
        userId = try c.decodeIfPresent(String.self, forKey: .userId) ?? ""
        createdAt = try c.decodeIfPresent(Int64.self, forKey: .createdAt) ?? 0
        updatedAt = try c.decodeIfPresent(Int64.self, forKey: .updatedAt) ?? 0
        deletedAt = try c.decodeIfPresent(Int64.self, forKey: .deletedAt)
        householdId = try c.decodeIfPresent(String.self, forKey: .householdId) ?? userId
    }
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
    var householdId: String = ""

    enum CodingKeys: String, CodingKey {
        case id, itemId, storeId, purchasedAt
        case userId, createdAt, updatedAt, deletedAt, householdId
    }

    init(id: String, itemId: String, storeId: String?,
         purchasedAt: Int64, userId: String, createdAt: Int64,
         updatedAt: Int64, deletedAt: Int64?, householdId: String = "") {
        self.id = id; self.itemId = itemId; self.storeId = storeId
        self.purchasedAt = purchasedAt; self.userId = userId
        self.createdAt = createdAt; self.updatedAt = updatedAt
        self.deletedAt = deletedAt; self.householdId = householdId
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(String.self, forKey: .id) ?? ""
        itemId = try c.decodeIfPresent(String.self, forKey: .itemId) ?? ""
        storeId = try c.decodeIfPresent(String.self, forKey: .storeId)
        purchasedAt = try c.decodeIfPresent(Int64.self, forKey: .purchasedAt) ?? 0
        userId = try c.decodeIfPresent(String.self, forKey: .userId) ?? ""
        createdAt = try c.decodeIfPresent(Int64.self, forKey: .createdAt) ?? 0
        updatedAt = try c.decodeIfPresent(Int64.self, forKey: .updatedAt) ?? 0
        deletedAt = try c.decodeIfPresent(Int64.self, forKey: .deletedAt)
        householdId = try c.decodeIfPresent(String.self, forKey: .householdId) ?? userId
    }
}
