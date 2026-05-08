import Foundation
import GRDB

/// Historical audit trail of purchases. One row inserted each time the user
/// marks an item purchased at a specific store. Soft-deleted (with matching
/// `purchasedAt`) when the user undoes a purchase, so the cross-store cascade
/// can roll back precisely without affecting unrelated concurrent purchases.
struct PurchaseRecord: Codable, FetchableRecord, MutablePersistableRecord, Identifiable, Hashable, Sendable {
    static let databaseTableName = "purchase_records"

    var id: String
    var itemId: String
    /// Nullable so older Android clients (pre-Store-tracking) can sync back
    /// records they wrote without a store. Modern writes always supply one.
    var storeId: String?
    var purchasedAt: Int64
    var userId: String
    var createdAt: Int64
    var updatedAt: Int64
    var deletedAt: Int64?
    var pendingSync: Bool

    enum Columns {
        static let id          = Column(CodingKeys.id)
        static let itemId      = Column(CodingKeys.itemId)
        static let storeId     = Column(CodingKeys.storeId)
        static let purchasedAt = Column(CodingKeys.purchasedAt)
        static let userId      = Column(CodingKeys.userId)
        static let createdAt   = Column(CodingKeys.createdAt)
        static let updatedAt   = Column(CodingKeys.updatedAt)
        static let deletedAt   = Column(CodingKeys.deletedAt)
        static let pendingSync = Column(CodingKeys.pendingSync)
    }
}
