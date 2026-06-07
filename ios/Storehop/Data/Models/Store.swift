import Foundation
import GRDB

struct Store: Codable, FetchableRecord, MutablePersistableRecord, Identifiable, Hashable, Sendable {
    static let databaseTableName = "stores"

    var id: String
    var name: String
    var colorArgb: Int64?
    var isArchived: Bool
    var isSeeded: Bool
    var userId: String
    var createdAt: Int64
    var updatedAt: Int64
    var deletedAt: Int64?
    var pendingSync: Bool
    var displayOrder: Int
    /// v0.7.0 multi-user access scope. See `Item.householdId` for the full
    /// rationale; single-member households have `householdId == userId`.
    var householdId: String = ""
    /// v0.9.0 (Android schema v10, iOS migration `v9_stores_one_off`).
    /// Marks the store as a "one-off" store for non-recurring purchases
    /// — e.g. "Hardware (One Off)" for a drying rack, "Online (One
    /// Off)" for a couch. Items whose alive xrefs ALL point to one-off
    /// stores are hidden from the master Items list; they live only
    /// inside the one-off store's Shop view. Mixed-tagging (one-off +
    /// regular xref) keeps the item on the master list. The critical-
    /// needs banner skips one-off stores entirely — it's a "grocery
    /// run" signal, not for specialty purchases.
    ///
    /// Defaults to `false` so any DTO read from an older device's
    /// Firestore push (pre-v0.9) deserializes cleanly into a regular
    /// store.
    var isOneOff: Bool = false

    enum Columns {
        static let id           = Column(CodingKeys.id)
        static let name         = Column(CodingKeys.name)
        static let colorArgb    = Column(CodingKeys.colorArgb)
        static let isArchived   = Column(CodingKeys.isArchived)
        static let isSeeded     = Column(CodingKeys.isSeeded)
        static let userId       = Column(CodingKeys.userId)
        static let createdAt    = Column(CodingKeys.createdAt)
        static let updatedAt    = Column(CodingKeys.updatedAt)
        static let deletedAt    = Column(CodingKeys.deletedAt)
        static let pendingSync  = Column(CodingKeys.pendingSync)
        static let displayOrder = Column(CodingKeys.displayOrder)
        static let householdId  = Column(CodingKeys.householdId)
        static let isOneOff     = Column(CodingKeys.isOneOff)
    }
}
