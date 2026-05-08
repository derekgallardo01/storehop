import Foundation
import GRDB

/// Per-store aisle ordering. One row per (store, category) the user has
/// drag-reordered. Categories without a row use a neutral fallback order
/// computed at query time (handled in ShoppingDao).
struct StoreCategoryOrder: Codable, FetchableRecord, MutablePersistableRecord, Hashable, Sendable {
    static let databaseTableName = "store_category_order"

    var storeId: String
    var categoryId: String
    var displayOrder: Int
    var isSeeded: Bool
    var userId: String
    var createdAt: Int64
    var updatedAt: Int64
    var deletedAt: Int64?
    var pendingSync: Bool

    enum Columns {
        static let storeId      = Column(CodingKeys.storeId)
        static let categoryId   = Column(CodingKeys.categoryId)
        static let displayOrder = Column(CodingKeys.displayOrder)
        static let isSeeded     = Column(CodingKeys.isSeeded)
        static let userId       = Column(CodingKeys.userId)
        static let createdAt    = Column(CodingKeys.createdAt)
        static let updatedAt    = Column(CodingKeys.updatedAt)
        static let deletedAt    = Column(CodingKeys.deletedAt)
        static let pendingSync  = Column(CodingKeys.pendingSync)
    }
}
