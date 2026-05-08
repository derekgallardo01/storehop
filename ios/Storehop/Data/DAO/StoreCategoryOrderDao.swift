import Foundation
import GRDB

struct StoreCategoryOrderDao: Sendable {
    let writer: any DatabaseWriter

    // MARK: - Reactive

    func observeForStore(storeId: String) -> AsyncValueObservation<[StoreCategoryOrder]> {
        ValueObservation
            .tracking { db in
                try StoreCategoryOrder.fetchAll(db, sql: """
                    SELECT * FROM store_category_order
                    WHERE storeId = ? AND deletedAt IS NULL
                    ORDER BY displayOrder, categoryId
                    """, arguments: [storeId])
            }
            .values(in: writer)
    }

    func observePendingPush(userId: String) -> AsyncValueObservation<[StoreCategoryOrder]> {
        ValueObservation
            .tracking { db in
                try StoreCategoryOrder.fetchAll(db, sql: """
                    SELECT * FROM store_category_order
                    WHERE userId = ? AND pendingSync = 1
                    """, arguments: [userId])
            }
            .values(in: writer)
    }

    // MARK: - Snapshot

    func findForStore(storeId: String) async throws -> [StoreCategoryOrder] {
        try await writer.read { db in try Self.findForStore(on: db, storeId: storeId) }
    }

    static func findForStore(on db: Database, storeId: String) throws -> [StoreCategoryOrder] {
        try StoreCategoryOrder.fetchAll(db, sql: """
            SELECT * FROM store_category_order
            WHERE storeId = ? AND deletedAt IS NULL
            """, arguments: [storeId])
    }

    func findAnyByPk(storeId: String, categoryId: String) async throws -> StoreCategoryOrder? {
        try await writer.read { db in try Self.findAnyByPk(on: db, storeId: storeId, categoryId: categoryId) }
    }

    static func findAnyByPk(on db: Database, storeId: String, categoryId: String) throws -> StoreCategoryOrder? {
        try StoreCategoryOrder.fetchOne(db, sql: """
            SELECT * FROM store_category_order
            WHERE storeId = ? AND categoryId = ?
            """, arguments: [storeId, categoryId])
    }

    func maxDisplayOrderForStore(storeId: String) async throws -> Int? {
        try await writer.read { db in try Self.maxDisplayOrderForStore(on: db, storeId: storeId) }
    }

    static func maxDisplayOrderForStore(on db: Database, storeId: String) throws -> Int? {
        try Int.fetchOne(db, sql: """
            SELECT MAX(displayOrder) FROM store_category_order
            WHERE storeId = ? AND deletedAt IS NULL
            """, arguments: [storeId])
    }

    // MARK: - Writes

    func upsert(_ order: StoreCategoryOrder) async throws {
        try await writer.write { db in
            var copy = order
            try copy.upsert(db)
        }
    }

    func upsertFromCloud(_ rows: [StoreCategoryOrder], on db: Database) throws {
        for row in rows {
            var copy = row
            try copy.upsert(db)
        }
    }

    /// Idempotently make sure `(storeId, categoryId)` has a live SCO row.
    /// Fresh row → displayOrder = max+1; revived row → repositioned to bottom.
    func appendIfMissing(storeId: String, categoryId: String, userId: String, now: Int64) async throws {
        try await writer.write { db in
            try Self.appendIfMissing(on: db, storeId: storeId, categoryId: categoryId, userId: userId, now: now)
        }
    }

    static func appendIfMissing(on db: Database, storeId: String, categoryId: String, userId: String, now: Int64) throws {
        let existing = try findAnyByPk(on: db, storeId: storeId, categoryId: categoryId)
        if let existing, existing.deletedAt == nil { return }

        let nextOrder = (try maxDisplayOrderForStore(on: db, storeId: storeId) ?? -1) + 1
        if existing == nil {
            var fresh = StoreCategoryOrder(
                storeId: storeId,
                categoryId: categoryId,
                displayOrder: nextOrder,
                isSeeded: false,
                userId: userId,
                createdAt: now,
                updatedAt: now,
                deletedAt: nil,
                pendingSync: true
            )
            try fresh.upsert(db)
        } else if var revive = existing {
            revive.displayOrder = nextOrder
            revive.updatedAt = now
            revive.deletedAt = nil
            revive.pendingSync = true
            try revive.upsert(db)
        }
    }

    func softDelete(storeId: String, categoryId: String, now: Int64) async throws {
        try await writer.write { db in try Self.softDelete(on: db, storeId: storeId, categoryId: categoryId, now: now) }
    }

    static func softDelete(on db: Database, storeId: String, categoryId: String, now: Int64) throws {
        try db.execute(sql: """
            UPDATE store_category_order
            SET deletedAt = ?, updatedAt = ?, pendingSync = 1
            WHERE storeId = ? AND categoryId = ?
            """, arguments: [now, now, storeId, categoryId])
    }

    func softDeleteForStore(userId: String, storeId: String, now: Int64) async throws {
        try await writer.write { db in try Self.softDeleteForStore(on: db, userId: userId, storeId: storeId, now: now) }
    }

    static func softDeleteForStore(on db: Database, userId: String, storeId: String, now: Int64) throws {
        try db.execute(sql: """
            UPDATE store_category_order
            SET deletedAt = ?, updatedAt = ?, pendingSync = 1
            WHERE storeId = ? AND userId = ? AND deletedAt IS NULL
            """, arguments: [now, now, storeId, userId])
    }

    func restoreCascadeForStore(userId: String, storeId: String, deletedAt: Int64, now: Int64) async throws {
        try await writer.write { db in
            try Self.restoreCascadeForStore(on: db, userId: userId, storeId: storeId, deletedAt: deletedAt, now: now)
        }
    }

    static func restoreCascadeForStore(on db: Database, userId: String, storeId: String, deletedAt: Int64, now: Int64) throws {
        try db.execute(sql: """
            UPDATE store_category_order
            SET deletedAt = NULL, updatedAt = ?, pendingSync = 1
            WHERE storeId = ? AND userId = ? AND deletedAt = ?
            """, arguments: [now, storeId, userId, deletedAt])
    }

    func softDeleteForCategory(userId: String, categoryId: String, now: Int64) async throws {
        try await writer.write { db in
            try Self.softDeleteForCategory(on: db, userId: userId, categoryId: categoryId, now: now)
        }
    }

    static func softDeleteForCategory(on db: Database, userId: String, categoryId: String, now: Int64) throws {
        try db.execute(sql: """
            UPDATE store_category_order
            SET deletedAt = ?, updatedAt = ?, pendingSync = 1
            WHERE categoryId = ? AND userId = ? AND deletedAt IS NULL
            """, arguments: [now, now, categoryId, userId])
    }

    func restoreCascadeForCategory(userId: String, categoryId: String, deletedAt: Int64, now: Int64) async throws {
        try await writer.write { db in
            try Self.restoreCascadeForCategory(on: db, userId: userId, categoryId: categoryId, deletedAt: deletedAt, now: now)
        }
    }

    static func restoreCascadeForCategory(on db: Database, userId: String, categoryId: String, deletedAt: Int64, now: Int64) throws {
        try db.execute(sql: """
            UPDATE store_category_order
            SET deletedAt = NULL, updatedAt = ?, pendingSync = 1
            WHERE categoryId = ? AND userId = ? AND deletedAt = ?
            """, arguments: [now, categoryId, userId, deletedAt])
    }

    /// Atomic replace: tombstone the existing order set, upsert the new one.
    func replaceAllForStore(storeId: String, ordered: [StoreCategoryOrder], now: Int64) async throws {
        try await writer.write { db in
            try Self.replaceAllForStore(on: db, storeId: storeId, ordered: ordered, now: now)
        }
    }

    static func replaceAllForStore(on db: Database, storeId: String, ordered: [StoreCategoryOrder], now: Int64) throws {
        let existing = try findForStore(on: db, storeId: storeId)
        let incomingKeys = Set(ordered.map(\.categoryId))
        for old in existing where !incomingKeys.contains(old.categoryId) {
            try softDelete(on: db, storeId: old.storeId, categoryId: old.categoryId, now: now)
        }
        for var row in ordered {
            row.updatedAt = now
            row.deletedAt = nil
            row.pendingSync = true
            try row.upsert(db)
        }
    }

    func markPushed(userId: String, storeId: String, categoryId: String) async throws {
        try await writer.write { db in
            try db.execute(sql: """
                UPDATE store_category_order SET pendingSync = 0
                WHERE storeId = ? AND categoryId = ? AND userId = ?
                """, arguments: [storeId, categoryId, userId])
        }
    }
}
