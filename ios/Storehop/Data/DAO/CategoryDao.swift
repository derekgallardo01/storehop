import Foundation
import GRDB

/// v0.7.0 access scope: queries filter by `householdId` (not `userId`).
/// See `StoreDao` for the rationale.
struct CategoryDao: Sendable {
    let writer: any DatabaseWriter

    // MARK: - Reactive

    func observeAll(householdId: String, includeArchived: Bool) -> AsyncValueObservation<[Category]> {
        ValueObservation
            .tracking { db in
                try Category.fetchAll(db, sql: """
                    SELECT * FROM categories
                    WHERE householdId = ? AND deletedAt IS NULL
                      AND (? = 1 OR isArchived = 0)
                    ORDER BY displayOrder ASC, name COLLATE NOCASE
                    """, arguments: [householdId, includeArchived ? 1 : 0])
            }
            .values(in: writer)
    }

    func observePendingPush(householdId: String) -> AsyncValueObservation<[Category]> {
        ValueObservation
            .tracking { db in
                try Category.fetchAll(db, sql: "SELECT * FROM categories WHERE householdId = ? AND pendingSync = 1", arguments: [householdId])
            }
            .values(in: writer)
    }

    /// v0.7.1: row-count of pending pushes for the Force-sync-now UX.
    func countPendingPush(householdId: String) -> AsyncValueObservation<Int> {
        ValueObservation
            .tracking { db in
                try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM categories WHERE householdId = ? AND pendingSync = 1", arguments: [householdId]) ?? 0
            }
            .values(in: writer)
    }

    // MARK: - Snapshot reads (instance + static)

    func findById(householdId: String, id: String) async throws -> Category? {
        try await writer.read { db in try Self.findById(on: db, householdId: householdId, id: id) }
    }

    static func findById(on db: Database, householdId: String, id: String) throws -> Category? {
        try Category.fetchOne(db, sql: """
            SELECT * FROM categories
            WHERE id = ? AND householdId = ? AND deletedAt IS NULL
            """, arguments: [id, householdId])
    }

    func findByName(householdId: String, name: String) async throws -> Category? {
        try await writer.read { db in try Self.findByName(on: db, householdId: householdId, name: name) }
    }

    /// Live-only by-name lookup. Used inside repository transactions
    /// (e.g. rename) to detect collisions without including tombstoned
    /// rows — the v6 (Android v0.5.5) fix.
    static func findByName(on db: Database, householdId: String, name: String) throws -> Category? {
        try Category.fetchOne(db, sql: """
            SELECT * FROM categories
            WHERE householdId = ? AND deletedAt IS NULL
              AND name = ? COLLATE NOCASE
            LIMIT 1
            """, arguments: [householdId, name])
    }

    func findAnyByName(householdId: String, name: String) async throws -> Category? {
        try await writer.read { db in try Self.findAnyByName(on: db, householdId: householdId, name: name) }
    }

    static func findAnyByName(on db: Database, householdId: String, name: String) throws -> Category? {
        try Category.fetchOne(db, sql: """
            SELECT * FROM categories
            WHERE householdId = ?
              AND name = ? COLLATE NOCASE
            LIMIT 1
            """, arguments: [householdId, name])
    }

    func findAnyById(householdId: String, id: String) async throws -> Category? {
        try await writer.read { db in try Self.findAnyById(on: db, householdId: householdId, id: id) }
    }

    static func findAnyById(on db: Database, householdId: String, id: String) throws -> Category? {
        try Category.fetchOne(db, sql: "SELECT * FROM categories WHERE id = ? AND householdId = ? LIMIT 1", arguments: [id, householdId])
    }

    // MARK: - Writes

    func upsert(_ category: Category) async throws {
        try await writer.write { db in
            var copy = category
            try copy.upsert(db)
        }
    }

    func upsertFromCloud(_ rows: [Category], on db: Database) throws {
        for row in rows {
            var copy = row
            try copy.upsert(db)
        }
    }

    func setArchived(householdId: String, id: String, archived: Bool, now: Int64) async throws {
        try await writer.write { db in
            try db.execute(sql: """
                UPDATE categories
                SET isArchived = ?, updatedAt = ?, pendingSync = 1
                WHERE id = ? AND householdId = ?
                """, arguments: [archived, now, id, householdId])
        }
    }

    func softDelete(householdId: String, id: String, now: Int64) async throws {
        try await writer.write { db in try Self.softDelete(on: db, householdId: householdId, id: id, now: now) }
    }

    static func softDelete(on db: Database, householdId: String, id: String, now: Int64) throws {
        try db.execute(sql: """
            UPDATE categories
            SET deletedAt = ?, updatedAt = ?, pendingSync = 1
            WHERE id = ? AND householdId = ?
            """, arguments: [now, now, id, householdId])
    }

    func restoreFromTombstone(householdId: String, id: String, now: Int64) async throws {
        try await writer.write { db in try Self.restoreFromTombstone(on: db, householdId: householdId, id: id, now: now) }
    }

    static func restoreFromTombstone(on db: Database, householdId: String, id: String, now: Int64) throws {
        try db.execute(sql: """
            UPDATE categories
            SET deletedAt = NULL, updatedAt = ?, pendingSync = 1
            WHERE id = ? AND householdId = ?
            """, arguments: [now, id, householdId])
    }

    func markPushed(householdId: String, id: String) async throws {
        try await writer.write { db in
            try db.execute(sql: "UPDATE categories SET pendingSync = 0 WHERE id = ? AND householdId = ?", arguments: [id, householdId])
        }
    }

    // MARK: - v0.6.4: displayOrder + batch tombstone-by-time

    /// Highest displayOrder among alive categories for this household, or nil
    /// when the household has none yet. Used by `addCategory` to append new
    /// rows at the end.
    static func maxDisplayOrder(on db: Database, householdId: String) throws -> Int? {
        try Int.fetchOne(db, sql: """
            SELECT MAX(displayOrder) FROM categories
            WHERE householdId = ? AND deletedAt IS NULL
            """, arguments: [householdId])
    }

    /// Rewrite a single row's displayOrder. Called inside the repository's
    /// reorder transaction once per affected category.
    static func updateDisplayOrder(on db: Database, householdId: String, id: String, order: Int, now: Int64) throws {
        try db.execute(sql: """
            UPDATE categories
            SET displayOrder = ?, updatedAt = ?, pendingSync = 1
            WHERE id = ? AND householdId = ?
            """, arguments: [order, now, id, householdId])
    }

    /// All categories tombstoned at exactly `deletedAt`. Used by
    /// `undoSoftDeleteMany` to restore the precise batch.
    static func findTombstonedAt(on db: Database, householdId: String, deletedAt: Int64) throws -> [Category] {
        try Category.fetchAll(db, sql: """
            SELECT * FROM categories
            WHERE householdId = ? AND deletedAt = ?
            """, arguments: [householdId, deletedAt])
    }
}
