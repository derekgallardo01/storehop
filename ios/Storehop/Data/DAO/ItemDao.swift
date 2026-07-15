import Foundation
import GRDB

/// v0.7.0 access scope: queries filter by `householdId` (not `userId`).
/// See `StoreDao` for the rationale.
struct ItemDao: Sendable {
    let writer: any DatabaseWriter

    // MARK: - Reactive

    func observeAll(householdId: String) -> AsyncValueObservation<[ItemWithCategoryAndStores]> {
        ValueObservation
            .tracking { db in
                try ItemWithCategoryAndStores.fetchAll(db, householdId: householdId)
            }
            .values(in: writer)
    }

    func observeNeeded(householdId: String) -> AsyncValueObservation<[Item]> {
        ValueObservation
            .tracking { db in
                try Item.fetchAll(db, sql: """
                    SELECT * FROM items
                    WHERE householdId = ? AND deletedAt IS NULL AND isNeeded = 1
                    ORDER BY name COLLATE NOCASE
                    """, arguments: [householdId])
            }
            .values(in: writer)
    }

    func observeById(householdId: String, id: String) -> AsyncValueObservation<ItemWithCategoryAndStores?> {
        ValueObservation
            .tracking { db in
                try ItemWithCategoryAndStores.fetch(db, householdId: householdId, id: id)
            }
            .values(in: writer)
    }

    func observePendingPush(householdId: String) -> AsyncValueObservation<[Item]> {
        ValueObservation
            .tracking { db in
                try Item.fetchAll(db, sql: "SELECT * FROM items WHERE householdId = ? AND pendingSync = 1", arguments: [householdId])
            }
            .values(in: writer)
    }

    /// v0.7.1: row-count of pending pushes for the Force-sync-now UX.
    func countPendingPush(householdId: String) -> AsyncValueObservation<Int> {
        ValueObservation
            .tracking { db in
                try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM items WHERE householdId = ? AND pendingSync = 1", arguments: [householdId]) ?? 0
            }
            .values(in: writer)
    }

    // MARK: - Snapshot reads

    func findAnyById(householdId: String, id: String) async throws -> Item? {
        try await writer.read { db in try Self.findAnyById(on: db, householdId: householdId, id: id) }
    }

    static func findAnyById(on db: Database, householdId: String, id: String) throws -> Item? {
        try Item.fetchOne(db, sql: "SELECT * FROM items WHERE id = ? AND householdId = ? LIMIT 1", arguments: [id, householdId])
    }

    /// Snapshot of "live item with relations" for use inside a repo
    /// transaction (e.g. updateItem needs the current row to preserve
    /// isNeeded/lastPurchasedAt/createdAt).
    static func findLiveById(on db: Database, householdId: String, id: String) throws -> Item? {
        try Item.fetchOne(db, sql: """
            SELECT * FROM items
            WHERE id = ? AND householdId = ? AND deletedAt IS NULL
            """, arguments: [id, householdId])
    }

    /// Live, case-insensitive name lookup. Used by the Shop-at-Store
    /// QuickAdd flow to dedupe before creating: typing "Milk" when "Milk"
    /// already exists must re-tag the existing item, not duplicate it.
    /// Mirrors Android's `ItemDao.findByName` (`COLLATE NOCASE`).
    static func findByName(on db: Database, householdId: String, name: String) throws -> Item? {
        try Item.fetchOne(db, sql: """
            SELECT * FROM items
            WHERE householdId = ?
              AND name = ? COLLATE NOCASE
              AND deletedAt IS NULL
            LIMIT 1
            """, arguments: [householdId, name])
    }

    // MARK: - Writes

    func upsert(_ item: Item) async throws {
        try await writer.write { db in
            var copy = item
            try copy.upsert(db)
        }
    }

    func upsertFromCloud(_ rows: [Item], on db: Database) throws {
        for row in rows {
            var copy = row
            try copy.upsert(db)
        }
    }

    func softDelete(householdId: String, id: String, now: Int64) async throws {
        try await writer.write { db in try Self.softDelete(on: db, householdId: householdId, id: id, now: now) }
    }

    static func softDelete(on db: Database, householdId: String, id: String, now: Int64) throws {
        try db.execute(sql: """
            UPDATE items
            SET deletedAt = ?, updatedAt = ?, pendingSync = 1
            WHERE id = ? AND householdId = ?
            """, arguments: [now, now, id, householdId])
    }

    func restoreFromTombstone(householdId: String, id: String, now: Int64) async throws {
        try await writer.write { db in try Self.restoreFromTombstone(on: db, householdId: householdId, id: id, now: now) }
    }

    static func restoreFromTombstone(on db: Database, householdId: String, id: String, now: Int64) throws {
        try db.execute(sql: """
            UPDATE items
            SET deletedAt = NULL, updatedAt = ?, pendingSync = 1
            WHERE id = ? AND householdId = ?
            """, arguments: [now, id, householdId])
    }

    /// Item-level mark-purchased. Vestigial after v4→v5; use
    /// `ItemStoreXrefDao.markPurchasedAcrossAllStores` for the user-facing
    /// flow so per-store state cascades correctly.
    func markPurchased(householdId: String, id: String, now: Int64) async throws {
        try await writer.write { db in
            try db.execute(sql: """
                UPDATE items
                SET isNeeded = 0, lastPurchasedAt = ?, updatedAt = ?, pendingSync = 1
                WHERE id = ? AND householdId = ?
                """, arguments: [now, now, id, householdId])
        }
    }

    func markNeeded(householdId: String, id: String, now: Int64) async throws {
        try await writer.write { db in
            try db.execute(sql: """
                UPDATE items
                SET isNeeded = 1, updatedAt = ?, pendingSync = 1
                WHERE id = ? AND householdId = ?
                """, arguments: [now, id, householdId])
        }
    }

    func clearCategoryReferences(householdId: String, categoryId: String, now: Int64) async throws {
        try await writer.write { db in
            try Self.clearCategoryReferences(on: db, householdId: householdId, categoryId: categoryId, now: now)
        }
    }

    static func clearCategoryReferences(on db: Database, householdId: String, categoryId: String, now: Int64) throws {
        try db.execute(sql: """
            UPDATE items
            SET categoryId = NULL, updatedAt = ?, pendingSync = 1
            WHERE categoryId = ? AND householdId = ? AND deletedAt IS NULL
            """, arguments: [now, categoryId, householdId])
    }

    /// v0.9 "Buy Today!": set (or clear) the transient urgency flag on an item.
    /// Cleared automatically on purchase; set from the item form. Idempotent.
    static func setBuyToday(on db: Database, householdId: String, id: String, value: Bool, now: Int64) throws {
        try db.execute(sql: """
            UPDATE items
            SET isBuyToday = ?, updatedAt = ?, pendingSync = 1
            WHERE id = ? AND householdId = ?
            """, arguments: [value, now, id, householdId])
    }

    func restoreCategoryReferences(householdId: String, categoryId: String, clearedAt: Int64, now: Int64) async throws {
        try await writer.write { db in
            try Self.restoreCategoryReferences(on: db, householdId: householdId, categoryId: categoryId, clearedAt: clearedAt, now: now)
        }
    }

    static func restoreCategoryReferences(on db: Database, householdId: String, categoryId: String, clearedAt: Int64, now: Int64) throws {
        try db.execute(sql: """
            UPDATE items
            SET categoryId = ?, updatedAt = ?, pendingSync = 1
            WHERE householdId = ? AND categoryId IS NULL
              AND updatedAt = ? AND deletedAt IS NULL
            """, arguments: [categoryId, now, householdId, clearedAt])
    }

    func markPushed(householdId: String, id: String) async throws {
        try await writer.write { db in
            try db.execute(sql: "UPDATE items SET pendingSync = 0 WHERE id = ? AND householdId = ?", arguments: [id, householdId])
        }
    }
}
