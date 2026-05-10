import Foundation
import GRDB

struct CategoryDao: Sendable {
    let writer: any DatabaseWriter

    // MARK: - Reactive

    func observeAll(userId: String, includeArchived: Bool) -> AsyncValueObservation<[Category]> {
        ValueObservation
            .tracking { db in
                try Category.fetchAll(db, sql: """
                    SELECT * FROM categories
                    WHERE userId = ? AND deletedAt IS NULL
                      AND (? = 1 OR isArchived = 0)
                    ORDER BY displayOrder ASC, name COLLATE NOCASE
                    """, arguments: [userId, includeArchived ? 1 : 0])
            }
            .values(in: writer)
    }

    func observePendingPush(userId: String) -> AsyncValueObservation<[Category]> {
        ValueObservation
            .tracking { db in
                try Category.fetchAll(db, sql: "SELECT * FROM categories WHERE userId = ? AND pendingSync = 1", arguments: [userId])
            }
            .values(in: writer)
    }

    // MARK: - Snapshot reads (instance + static)

    func findById(userId: String, id: String) async throws -> Category? {
        try await writer.read { db in try Self.findById(on: db, userId: userId, id: id) }
    }

    static func findById(on db: Database, userId: String, id: String) throws -> Category? {
        try Category.fetchOne(db, sql: """
            SELECT * FROM categories
            WHERE id = ? AND userId = ? AND deletedAt IS NULL
            """, arguments: [id, userId])
    }

    func findByName(userId: String, name: String) async throws -> Category? {
        try await writer.read { db in try Self.findByName(on: db, userId: userId, name: name) }
    }

    /// Live-only by-name lookup. Used inside repository transactions
    /// (e.g. rename) to detect collisions without including tombstoned
    /// rows — the v6 (Android v0.5.5) fix.
    static func findByName(on db: Database, userId: String, name: String) throws -> Category? {
        try Category.fetchOne(db, sql: """
            SELECT * FROM categories
            WHERE userId = ? AND deletedAt IS NULL
              AND name = ? COLLATE NOCASE
            LIMIT 1
            """, arguments: [userId, name])
    }

    func findAnyByName(userId: String, name: String) async throws -> Category? {
        try await writer.read { db in try Self.findAnyByName(on: db, userId: userId, name: name) }
    }

    static func findAnyByName(on db: Database, userId: String, name: String) throws -> Category? {
        try Category.fetchOne(db, sql: """
            SELECT * FROM categories
            WHERE userId = ?
              AND name = ? COLLATE NOCASE
            LIMIT 1
            """, arguments: [userId, name])
    }

    func findAnyById(userId: String, id: String) async throws -> Category? {
        try await writer.read { db in try Self.findAnyById(on: db, userId: userId, id: id) }
    }

    static func findAnyById(on db: Database, userId: String, id: String) throws -> Category? {
        try Category.fetchOne(db, sql: "SELECT * FROM categories WHERE id = ? AND userId = ? LIMIT 1", arguments: [id, userId])
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

    func setArchived(userId: String, id: String, archived: Bool, now: Int64) async throws {
        try await writer.write { db in
            try db.execute(sql: """
                UPDATE categories
                SET isArchived = ?, updatedAt = ?, pendingSync = 1
                WHERE id = ? AND userId = ?
                """, arguments: [archived, now, id, userId])
        }
    }

    func softDelete(userId: String, id: String, now: Int64) async throws {
        try await writer.write { db in try Self.softDelete(on: db, userId: userId, id: id, now: now) }
    }

    static func softDelete(on db: Database, userId: String, id: String, now: Int64) throws {
        try db.execute(sql: """
            UPDATE categories
            SET deletedAt = ?, updatedAt = ?, pendingSync = 1
            WHERE id = ? AND userId = ?
            """, arguments: [now, now, id, userId])
    }

    func restoreFromTombstone(userId: String, id: String, now: Int64) async throws {
        try await writer.write { db in try Self.restoreFromTombstone(on: db, userId: userId, id: id, now: now) }
    }

    static func restoreFromTombstone(on db: Database, userId: String, id: String, now: Int64) throws {
        try db.execute(sql: """
            UPDATE categories
            SET deletedAt = NULL, updatedAt = ?, pendingSync = 1
            WHERE id = ? AND userId = ?
            """, arguments: [now, id, userId])
    }

    func markPushed(userId: String, id: String) async throws {
        try await writer.write { db in
            try db.execute(sql: "UPDATE categories SET pendingSync = 0 WHERE id = ? AND userId = ?", arguments: [id, userId])
        }
    }

    // MARK: - v0.6.4: displayOrder + batch tombstone-by-time

    /// Highest displayOrder among alive categories for this user, or nil
    /// when the user has none yet. Used by `addCategory` to append new
    /// rows at the end.
    static func maxDisplayOrder(on db: Database, userId: String) throws -> Int? {
        try Int.fetchOne(db, sql: """
            SELECT MAX(displayOrder) FROM categories
            WHERE userId = ? AND deletedAt IS NULL
            """, arguments: [userId])
    }

    /// Rewrite a single row's displayOrder. Called inside the repository's
    /// reorder transaction once per affected category.
    static func updateDisplayOrder(on db: Database, userId: String, id: String, order: Int, now: Int64) throws {
        try db.execute(sql: """
            UPDATE categories
            SET displayOrder = ?, updatedAt = ?, pendingSync = 1
            WHERE id = ? AND userId = ?
            """, arguments: [order, now, id, userId])
    }

    /// All categories tombstoned at exactly `deletedAt`. Used by
    /// `undoSoftDeleteMany` to restore the precise batch.
    static func findTombstonedAt(on db: Database, userId: String, deletedAt: Int64) throws -> [Category] {
        try Category.fetchAll(db, sql: """
            SELECT * FROM categories
            WHERE userId = ? AND deletedAt = ?
            """, arguments: [userId, deletedAt])
    }
}
