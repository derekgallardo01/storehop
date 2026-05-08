import Foundation
import GRDB

struct Category: Codable, FetchableRecord, MutablePersistableRecord, Identifiable, Hashable, Sendable {
    static let databaseTableName = "categories"

    var id: String
    var name: String
    /// i18n key for seeded categories (e.g. "cat_produce"). nil for user-added.
    var nameKey: String?
    /// Material icon name from the seed file (e.g. "Eco", "BakeryDining"). The
    /// iOS UI maps these to SF Symbols in `CategoryIcon.swift`.
    var icon: String?
    var isArchived: Bool
    var isSeeded: Bool
    var userId: String
    var createdAt: Int64
    var updatedAt: Int64
    var deletedAt: Int64?
    var pendingSync: Bool

    enum Columns {
        static let id          = Column(CodingKeys.id)
        static let name        = Column(CodingKeys.name)
        static let nameKey     = Column(CodingKeys.nameKey)
        static let icon        = Column(CodingKeys.icon)
        static let isArchived  = Column(CodingKeys.isArchived)
        static let isSeeded    = Column(CodingKeys.isSeeded)
        static let userId      = Column(CodingKeys.userId)
        static let createdAt   = Column(CodingKeys.createdAt)
        static let updatedAt   = Column(CodingKeys.updatedAt)
        static let deletedAt   = Column(CodingKeys.deletedAt)
        static let pendingSync = Column(CodingKeys.pendingSync)
    }
}
