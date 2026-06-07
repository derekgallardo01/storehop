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
///
/// v0.7.0 access scope: queries filter by `householdId` (not `userId`).
/// `userId` remains on the entity as creator/audit metadata but is no
/// longer part of the access predicate. For single-member households
/// (everyone pre-Phase 3) `householdId == userId` on every row, so the
/// behavioural result is unchanged. When a user accepts an invite the
/// shared rows have `householdId = inviter.uid` and both members see
/// them via the household filter.
struct StoreDao: Sendable {
    let writer: any DatabaseWriter

    // MARK: - Reactive

    func observeAll(householdId: String, includeArchived: Bool) -> AsyncValueObservation<[Store]> {
        ValueObservation
            .tracking { db in
                try Store.fetchAll(db, sql: """
                    SELECT * FROM stores
                    WHERE householdId = ? AND deletedAt IS NULL
                      AND (? = 1 OR isArchived = 0)
                    ORDER BY displayOrder ASC, name COLLATE NOCASE
                    """, arguments: [householdId, includeArchived ? 1 : 0])
            }
            .values(in: writer)
    }

    func observeById(householdId: String, id: String) -> AsyncValueObservation<Store?> {
        ValueObservation
            .tracking { db in
                try Store.fetchOne(db, sql: """
                    SELECT * FROM stores
                    WHERE id = ? AND householdId = ? AND deletedAt IS NULL
                    """, arguments: [id, householdId])
            }
            .values(in: writer)
    }

    func observePendingPush(householdId: String) -> AsyncValueObservation<[Store]> {
        ValueObservation
            .tracking { db in
                try Store.fetchAll(db, sql: "SELECT * FROM stores WHERE householdId = ? AND pendingSync = 1", arguments: [householdId])
            }
            .values(in: writer)
    }

    /// v0.7.1: row-count of pending pushes for the Force-sync-now UX.
    func countPendingPush(householdId: String) -> AsyncValueObservation<Int> {
        ValueObservation
            .tracking { db in
                try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM stores WHERE householdId = ? AND pendingSync = 1", arguments: [householdId]) ?? 0
            }
            .values(in: writer)
    }

    // MARK: - Snapshot reads (instance API + on: Database for repo transactions)

    func findById(householdId: String, id: String) async throws -> Store? {
        try await writer.read { db in try Self.findById(on: db, householdId: householdId, id: id) }
    }

    static func findById(on db: Database, householdId: String, id: String) throws -> Store? {
        try Store.fetchOne(db, sql: """
            SELECT * FROM stores
            WHERE id = ? AND householdId = ? AND deletedAt IS NULL
            """, arguments: [id, householdId])
    }

    func findByName(householdId: String, name: String) async throws -> Store? {
        try await writer.read { db in try Self.findByName(on: db, householdId: householdId, name: name) }
    }

    /// Live-only by-name lookup. Used inside repository transactions
    /// (e.g. rename) to detect collisions without including tombstoned
    /// rows — the v6 (Android v0.5.5) fix.
    static func findByName(on db: Database, householdId: String, name: String) throws -> Store? {
        try Store.fetchOne(db, sql: """
            SELECT * FROM stores
            WHERE householdId = ? AND deletedAt IS NULL
              AND name = ? COLLATE NOCASE
            LIMIT 1
            """, arguments: [householdId, name])
    }

    /// Tombstone-aware lookup; powers the resurrect-on-re-add path.
    func findAnyByName(householdId: String, name: String) async throws -> Store? {
        try await writer.read { db in try Self.findAnyByName(on: db, householdId: householdId, name: name) }
    }

    static func findAnyByName(on db: Database, householdId: String, name: String) throws -> Store? {
        try Store.fetchOne(db, sql: """
            SELECT * FROM stores
            WHERE householdId = ?
              AND name = ? COLLATE NOCASE
            LIMIT 1
            """, arguments: [householdId, name])
    }

    func findAnyById(householdId: String, id: String) async throws -> Store? {
        try await writer.read { db in try Self.findAnyById(on: db, householdId: householdId, id: id) }
    }

    static func findAnyById(on db: Database, householdId: String, id: String) throws -> Store? {
        try Store.fetchOne(db, sql: "SELECT * FROM stores WHERE id = ? AND householdId = ? LIMIT 1", arguments: [id, householdId])
    }

    func nextDisplayOrder(householdId: String) async throws -> Int {
        try await writer.read { db in try Self.nextDisplayOrder(on: db, householdId: householdId) }
    }

    static func nextDisplayOrder(on db: Database, householdId: String) throws -> Int {
        try Int.fetchOne(db, sql: """
            SELECT COALESCE(MAX(displayOrder), -1) + 1
            FROM stores
            WHERE householdId = ? AND deletedAt IS NULL
            """, arguments: [householdId]) ?? 0
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

    func setArchived(householdId: String, id: String, archived: Bool, now: Int64) async throws {
        try await writer.write { db in
            try db.execute(sql: """
                UPDATE stores
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
            UPDATE stores
            SET deletedAt = ?, updatedAt = ?, pendingSync = 1
            WHERE id = ? AND householdId = ?
            """, arguments: [now, now, id, householdId])
    }

    func restoreFromTombstone(householdId: String, id: String, now: Int64) async throws {
        try await writer.write { db in try Self.restoreFromTombstone(on: db, householdId: householdId, id: id, now: now) }
    }

    static func restoreFromTombstone(on db: Database, householdId: String, id: String, now: Int64) throws {
        try db.execute(sql: """
            UPDATE stores
            SET deletedAt = NULL, updatedAt = ?, pendingSync = 1
            WHERE id = ? AND householdId = ?
            """, arguments: [now, id, householdId])
    }

    func setDisplayOrder(householdId: String, id: String, displayOrder: Int, now: Int64) async throws {
        try await writer.write { db in
            try Self.setDisplayOrder(on: db, householdId: householdId, id: id, displayOrder: displayOrder, now: now)
        }
    }

    static func setDisplayOrder(on db: Database, householdId: String, id: String, displayOrder: Int, now: Int64) throws {
        try db.execute(sql: """
            UPDATE stores
            SET displayOrder = ?, updatedAt = ?, pendingSync = 1
            WHERE id = ? AND householdId = ?
            """, arguments: [displayOrder, now, id, householdId])
    }

    func markPushed(householdId: String, id: String) async throws {
        try await writer.write { db in
            try db.execute(sql: "UPDATE stores SET pendingSync = 0 WHERE id = ? AND householdId = ?", arguments: [id, householdId])
        }
    }
}
