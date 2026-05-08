import Foundation
import GRDB

struct ItemDao: Sendable {
    let writer: any DatabaseWriter

    // MARK: - Reactive

    func observeAll(userId: String) -> AsyncValueObservation<[ItemWithCategoryAndStores]> {
        ValueObservation
            .tracking { db in
                try ItemWithCategoryAndStores.fetchAll(db, userId: userId)
            }
            .values(in: writer)
    }

    func observeNeeded(userId: String) -> AsyncValueObservation<[Item]> {
        ValueObservation
            .tracking { db in
                try Item.fetchAll(db, sql: """
                    SELECT * FROM items
                    WHERE userId = ? AND deletedAt IS NULL AND isNeeded = 1
                    ORDER BY name COLLATE NOCASE
                    """, arguments: [userId])
            }
            .values(in: writer)
    }

    func observeById(userId: String, id: String) -> AsyncValueObservation<ItemWithCategoryAndStores?> {
        ValueObservation
            .tracking { db in
                try ItemWithCategoryAndStores.fetch(db, userId: userId, id: id)
            }
            .values(in: writer)
    }

    func observePendingPush(userId: String) -> AsyncValueObservation<[Item]> {
        ValueObservation
            .tracking { db in
                try Item.fetchAll(db, sql: "SELECT * FROM items WHERE userId = ? AND pendingSync = 1", arguments: [userId])
            }
            .values(in: writer)
    }

    // MARK: - Snapshot reads

    func findAnyById(userId: String, id: String) async throws -> Item? {
        try await writer.read { db in try Self.findAnyById(on: db, userId: userId, id: id) }
    }

    static func findAnyById(on db: Database, userId: String, id: String) throws -> Item? {
        try Item.fetchOne(db, sql: "SELECT * FROM items WHERE id = ? AND userId = ? LIMIT 1", arguments: [id, userId])
    }

    /// Snapshot of "live item with relations" for use inside a repo
    /// transaction (e.g. updateItem needs the current row to preserve
    /// isNeeded/lastPurchasedAt/createdAt).
    static func findLiveById(on db: Database, userId: String, id: String) throws -> Item? {
        try Item.fetchOne(db, sql: """
            SELECT * FROM items
            WHERE id = ? AND userId = ? AND deletedAt IS NULL
            """, arguments: [id, userId])
    }

    // MARK: - Writes

    func upsert(_ item: Item) async throws {
        try await writer.write { db in try item.upsert(db) }
    }

    func upsertFromCloud(_ rows: [Item], on db: Database) throws {
        for row in rows { try row.upsert(db) }
    }

    func softDelete(userId: String, id: String, now: Int64) async throws {
        try await writer.write { db in try Self.softDelete(on: db, userId: userId, id: id, now: now) }
    }

    static func softDelete(on db: Database, userId: String, id: String, now: Int64) throws {
        try db.execute(sql: """
            UPDATE items
            SET deletedAt = ?, updatedAt = ?, pendingSync = 1
            WHERE id = ? AND userId = ?
            """, arguments: [now, now, id, userId])
    }

    func restoreFromTombstone(userId: String, id: String, now: Int64) async throws {
        try await writer.write { db in try Self.restoreFromTombstone(on: db, userId: userId, id: id, now: now) }
    }

    static func restoreFromTombstone(on db: Database, userId: String, id: String, now: Int64) throws {
        try db.execute(sql: """
            UPDATE items
            SET deletedAt = NULL, updatedAt = ?, pendingSync = 1
            WHERE id = ? AND userId = ?
            """, arguments: [now, id, userId])
    }

    /// Item-level mark-purchased. Vestigial after v4→v5; use
    /// `ItemStoreXrefDao.markPurchasedAcrossAllStores` for the user-facing
    /// flow so per-store state cascades correctly.
    func markPurchased(userId: String, id: String, now: Int64) async throws {
        try await writer.write { db in
            try db.execute(sql: """
                UPDATE items
                SET isNeeded = 0, lastPurchasedAt = ?, updatedAt = ?, pendingSync = 1
                WHERE id = ? AND userId = ?
                """, arguments: [now, now, id, userId])
        }
    }

    func markNeeded(userId: String, id: String, now: Int64) async throws {
        try await writer.write { db in
            try db.execute(sql: """
                UPDATE items
                SET isNeeded = 1, updatedAt = ?, pendingSync = 1
                WHERE id = ? AND userId = ?
                """, arguments: [now, id, userId])
        }
    }

    func clearCategoryReferences(userId: String, categoryId: String, now: Int64) async throws {
        try await writer.write { db in
            try Self.clearCategoryReferences(on: db, userId: userId, categoryId: categoryId, now: now)
        }
    }

    static func clearCategoryReferences(on db: Database, userId: String, categoryId: String, now: Int64) throws {
        try db.execute(sql: """
            UPDATE items
            SET categoryId = NULL, updatedAt = ?, pendingSync = 1
            WHERE categoryId = ? AND userId = ? AND deletedAt IS NULL
            """, arguments: [now, categoryId, userId])
    }

    func restoreCategoryReferences(userId: String, categoryId: String, clearedAt: Int64, now: Int64) async throws {
        try await writer.write { db in
            try Self.restoreCategoryReferences(on: db, userId: userId, categoryId: categoryId, clearedAt: clearedAt, now: now)
        }
    }

    static func restoreCategoryReferences(on db: Database, userId: String, categoryId: String, clearedAt: Int64, now: Int64) throws {
        try db.execute(sql: """
            UPDATE items
            SET categoryId = ?, updatedAt = ?, pendingSync = 1
            WHERE userId = ? AND categoryId IS NULL
              AND updatedAt = ? AND deletedAt IS NULL
            """, arguments: [categoryId, now, userId, clearedAt])
    }

    func markPushed(userId: String, id: String) async throws {
        try await writer.write { db in
            try db.execute(sql: "UPDATE items SET pendingSync = 0 WHERE id = ? AND userId = ?", arguments: [id, userId])
        }
    }
}
