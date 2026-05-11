import Foundation
import GRDB

/// One row per (item, store) pair that the user has tagged together. Owns the
/// per-store shopping state since migration v4→v5: `isNeeded` says whether the
/// item is still needed at this specific store, and `lastPurchasedAt` records
/// when it was last marked purchased there. Marking purchased at one store
/// cascades `isNeeded = false` across every live xref for the item (the
/// v0.5.1 cross-store cascade), but `lastPurchasedAt` is set only on the
/// store where the user actually shopped.
struct ItemStoreXref: Codable, FetchableRecord, MutablePersistableRecord, Hashable, Sendable {
    static let databaseTableName = "item_store_xref"

    var itemId: String
    var storeId: String
    var userId: String
    var createdAt: Int64
    var updatedAt: Int64
    var deletedAt: Int64?
    var pendingSync: Bool
    var isNeeded: Bool
    var lastPurchasedAt: Int64?
    /// v0.7.0 multi-user access scope. See `Item.householdId` for the full
    /// rationale; single-member households have `householdId == userId`.
    var householdId: String = ""

    enum Columns {
        static let itemId          = Column(CodingKeys.itemId)
        static let storeId         = Column(CodingKeys.storeId)
        static let userId          = Column(CodingKeys.userId)
        static let createdAt       = Column(CodingKeys.createdAt)
        static let updatedAt       = Column(CodingKeys.updatedAt)
        static let deletedAt       = Column(CodingKeys.deletedAt)
        static let pendingSync     = Column(CodingKeys.pendingSync)
        static let isNeeded        = Column(CodingKeys.isNeeded)
        static let lastPurchasedAt = Column(CodingKeys.lastPurchasedAt)
        static let householdId     = Column(CodingKeys.householdId)
    }
}
