import Foundation
import GRDB

/// Mirrors Android `StoreDao`. Each write operation is exposed in two
/// shapes:
///   - **Instance method** (`async throws`): wraps a fresh `writer.write { }`
///     for one-off DAO calls.
///   - **Static `on: Database` method**: runs on an existing `Database`
///     connection so a repository can compose multiple DAO writes inside a
///     single transaction. GRDB's `write` is not reentrant, so this split
///     is mandatory for cross-DAO atomicity.
struct StoreDao: Sendable {
    let writer: any DatabaseWriter

    // MARK: - Reactive

    func observeAll(userId: String, includeArchived: Bool) -> AsyncValueObservation<[Store]> {
        ValueObservation
            .tracking { db in
                try Store.fetchAll(db, sql: """
                    SELECT * FROM stores
                    WHERE userId = ? AND deletedAt IS NULL
                      AND (? = 1 OR isArchived = 0)
                    ORDER BY displayOrder ASC, name COLLATE NOCASE
                    """, arguments: [userId, includeArchived ? 1 : 0])
            }
            .values(in: writer)
    }

    func observeById(userId: String, id: String) -> AsyncValueObservation<Store?> {
        ValueObservation
            .tracking { db in
                try Store.fetchOne(db, sql: """
                    SELECT * FROM stores
                    WHERE id = ? AND userId = ? AND deletedAt IS NULL
                    """, arguments: [id, userId])
            }
            .values(in: writer)
    }

    func observePendingPush(userId: String) -> AsyncValueObservation<[Store]> {
        ValueObservation
            .tracking { db in
                try Store.fetchAll(db, sql: "SELECT * FROM stores WHERE userId = ? AND pendingSync = 1", arguments: [userId])
            }
            .values(in: writer)
    }

    // MARK: - Snapshot reads (instance API + on: Database for repo transactions)

    func findById(userId: String, id: String) async throws -> Store? {
        try await writer.read { db in try Self.findById(on: db, userId: userId, id: id) }
    }

    static func findById(on db: Database, userId: String, id: String) throws -> Store? {
        try Store.fetchOne(db, sql: """
            SELECT * FROM stores
            WHERE id = ? AND userId = ? AND deletedAt IS NULL
            """, arguments: [id, userId])
    }

    func findByName(userId: String, name: String) async throws -> Store? {
        try await writer.read { db in
            try Store.fetchOne(db, sql: """
                SELECT * FROM stores
                WHERE userId = ? AND deletedAt IS NULL
                  AND name = ? COLLATE NOCASE
                LIMIT 1
                """, arguments: [userId, name])
        }
    }

    /// Tombstone-aware lookup; powers the resurrect-on-re-add path.
    func findAnyByName(userId: String, name: String) async throws -> Store? {
        try await writer.read { db in try Self.findAnyByName(on: db, userId: userId, name: name) }
    }

    static func findAnyByName(on db: Database, userId: String, name: String) throws -> Store? {
        try Store.fetchOne(db, sql: """
            SELECT * FROM stores
            WHERE userId = ?
              AND name = ? COLLATE NOCASE
            LIMIT 1
            """, arguments: [userId, name])
    }

    func findAnyById(userId: String, id: String) async throws -> Store? {
        try await writer.read { db in try Self.findAnyById(on: db, userId: userId, id: id) }
    }

    static func findAnyById(on db: Database, userId: String, id: String) throws -> Store? {
        try Store.fetchOne(db, sql: "SELECT * FROM stores WHERE id = ? AND userId = ? LIMIT 1", arguments: [id, userId])
    }

    func nextDisplayOrder(userId: String) async throws -> Int {
        try await writer.read { db in try Self.nextDisplayOrder(on: db, userId: userId) }
    }

    static func nextDisplayOrder(on db: Database, userId: String) throws -> Int {
        try Int.fetchOne(db, sql: """
            SELECT COALESCE(MAX(displayOrder), -1) + 1
            FROM stores
            WHERE userId = ? AND deletedAt IS NULL
            """, arguments: [userId]) ?? 0
    }

    // MARK: - Writes

    func upsert(_ store: Store) async throws {
        try await writer.write { db in
            var copy = store
            try copy.upsert(db)
        }
    }

    func upsertFromCloud(_ rows: [Store], on db: Database) throws {
        for row in rows {
            var copy = row
            try copy.upsert(db)
        }
    }

    func setArchived(userId: String, id: String, archived: Bool, now: Int64) async throws {
        try await writer.write { db in
            try db.execute(sql: """
                UPDATE stores
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
            UPDATE stores
            SET deletedAt = ?, updatedAt = ?, pendingSync = 1
            WHERE id = ? AND userId = ?
            """, arguments: [now, now, id, userId])
    }

    func restoreFromTombstone(userId: String, id: String, now: Int64) async throws {
        try await writer.write { db in try Self.restoreFromTombstone(on: db, userId: userId, id: id, now: now) }
    }

    static func restoreFromTombstone(on db: Database, userId: String, id: String, now: Int64) throws {
        try db.execute(sql: """
            UPDATE stores
            SET deletedAt = NULL, updatedAt = ?, pendingSync = 1
            WHERE id = ? AND userId = ?
            """, arguments: [now, id, userId])
    }

    func setDisplayOrder(userId: String, id: String, displayOrder: Int, now: Int64) async throws {
        try await writer.write { db in
            try Self.setDisplayOrder(on: db, userId: userId, id: id, displayOrder: displayOrder, now: now)
        }
    }

    static func setDisplayOrder(on db: Database, userId: String, id: String, displayOrder: Int, now: Int64) throws {
        try db.execute(sql: """
            UPDATE stores
            SET displayOrder = ?, updatedAt = ?, pendingSync = 1
            WHERE id = ? AND userId = ?
            """, arguments: [displayOrder, now, id, userId])
    }

    func markPushed(userId: String, id: String) async throws {
        try await writer.write { db in
            try db.execute(sql: "UPDATE stores SET pendingSync = 0 WHERE id = ? AND userId = ?", arguments: [id, userId])
        }
    }
}
