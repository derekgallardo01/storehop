import Foundation
import GRDB

/// One row per shopping item the user has ever added.
///
/// Note on vestigial fields after migration v4→v5: `isNeeded` and
/// `lastPurchasedAt` are no longer the source of truth for shopping state —
/// `ItemStoreXref` now owns per-store state. They remain on this row for
/// Firestore back-compat (older Android clients still read/write them) and
/// because Room's migration only added the new columns to the xref table; it
/// did not drop the old ones from `items`. Treat them as read-only mirrors.
struct Item: Codable, FetchableRecord, MutablePersistableRecord, Identifiable, Hashable, Sendable {
    static let databaseTableName = "items"

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
    var pendingSync: Bool
    var brand: String?
    var imageUrl: String?
    var isStaple: Bool
    var isPriority: Bool
    /// v0.9 "Buy Today!": transient urgency flag, distinct from the permanent
    /// `isPriority` ("Critical"). Set when an item must be bought today;
    /// surfaces in the Buy Today banner atop the Stores screen and auto-clears
    /// on purchase. Defaulted so existing `Item(...)` construction sites and
    /// GRDB decodes of pre-migration rows keep compiling / decoding.
    var isBuyToday: Bool = false
    /// v0.7.0 multi-user access scope. Every entity belongs to one
    /// household. Single-member households have `householdId == userId`
    /// (auto-migrated on first launch by the v7→v8 schema migration).
    /// When a user joins another household via invite, every write here
    /// uses the new householdId so the data lands in the shared
    /// Firestore path. `userId` stays as creator/audit metadata.
    var householdId: String = ""

    enum Columns {
        static let id              = Column(CodingKeys.id)
        static let name            = Column(CodingKeys.name)
        static let categoryId      = Column(CodingKeys.categoryId)
        static let notes           = Column(CodingKeys.notes)
        static let quantity        = Column(CodingKeys.quantity)
        static let isNeeded        = Column(CodingKeys.isNeeded)
        static let lastPurchasedAt = Column(CodingKeys.lastPurchasedAt)
        static let userId          = Column(CodingKeys.userId)
        static let createdAt       = Column(CodingKeys.createdAt)
        static let updatedAt       = Column(CodingKeys.updatedAt)
        static let deletedAt       = Column(CodingKeys.deletedAt)
        static let pendingSync     = Column(CodingKeys.pendingSync)
        static let brand           = Column(CodingKeys.brand)
        static let imageUrl        = Column(CodingKeys.imageUrl)
        static let isStaple        = Column(CodingKeys.isStaple)
        static let isPriority      = Column(CodingKeys.isPriority)
        static let isBuyToday      = Column(CodingKeys.isBuyToday)
        static let householdId     = Column(CodingKeys.householdId)
    }
}
