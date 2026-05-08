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
    }
}
