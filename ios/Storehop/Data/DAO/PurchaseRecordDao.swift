import Foundation
import GRDB

struct PurchaseRecordDao: Sendable {
    let writer: any DatabaseWriter

    // MARK: - Reactive

    func observeForItem(userId: String, itemId: String) -> AsyncValueObservation<[PurchaseRecord]> {
        ValueObservation
            .tracking { db in
                try PurchaseRecord.fetchAll(db, sql: """
                    SELECT * FROM purchase_records
                    WHERE itemId = ? AND userId = ? AND deletedAt IS NULL
                    ORDER BY purchasedAt DESC
                    """, arguments: [itemId, userId])
            }
            .values(in: writer)
    }

    func observePendingPush(userId: String) -> AsyncValueObservation<[PurchaseRecord]> {
        ValueObservation
            .tracking { db in
                try PurchaseRecord.fetchAll(db, sql: """
                    SELECT * FROM purchase_records
                    WHERE userId = ? AND pendingSync = 1
                    """, arguments: [userId])
            }
            .values(in: writer)
    }

    // MARK: - Writes

    /// Insert ABORTs on conflict — UUIDs don't collide in normal use, so a
    /// duplicate PK indicates a real bug; surface it instead of silently
    /// overwriting.
    func insert(_ record: PurchaseRecord) async throws {
        try await writer.write { db in try Self.insert(record, on: db) }
    }

    static func insert(_ record: PurchaseRecord, on db: Database) throws {
        var copy = record
        try copy.insert(db, onConflict: .abort)
    }

    /// Pull-side path uses upsert because the same record can legitimately
    /// arrive twice — once from a previous push, once from a re-pull.
    func upsertFromCloud(_ rows: [PurchaseRecord], on db: Database) throws {
        for row in rows {
            var copy = row
            try copy.upsert(db)
        }
    }

    func softDelete(userId: String, id: String, now: Int64) async throws {
        try await writer.write { db in
            try db.execute(sql: """
                UPDATE purchase_records
                SET deletedAt = ?, updatedAt = ?, pendingSync = 1
                WHERE id = ? AND userId = ?
                """, arguments: [now, now, id, userId])
        }
    }

    func softDeleteForItem(userId: String, itemId: String, now: Int64) async throws {
        try await writer.write { db in
            try Self.softDeleteForItem(on: db, userId: userId, itemId: itemId, now: now)
        }
    }

    static func softDeleteForItem(on db: Database, userId: String, itemId: String, now: Int64) throws {
        try db.execute(sql: """
            UPDATE purchase_records
            SET deletedAt = ?, updatedAt = ?, pendingSync = 1
            WHERE itemId = ? AND userId = ? AND deletedAt IS NULL
            """, arguments: [now, now, itemId, userId])
    }

    func restoreCascadeForItem(userId: String, itemId: String, deletedAt: Int64, now: Int64) async throws {
        try await writer.write { db in
            try Self.restoreCascadeForItem(on: db, userId: userId, itemId: itemId, deletedAt: deletedAt, now: now)
        }
    }

    static func restoreCascadeForItem(on db: Database, userId: String, itemId: String, deletedAt: Int64, now: Int64) throws {
        try db.execute(sql: """
            UPDATE purchase_records
            SET deletedAt = NULL, updatedAt = ?, pendingSync = 1
            WHERE itemId = ? AND userId = ? AND deletedAt = ?
            """, arguments: [now, itemId, userId, deletedAt])
    }

    func softDeleteForItemAtTime(userId: String, itemId: String, purchasedAt: Int64, now: Int64) async throws {
        try await writer.write { db in
            try Self.softDeleteForItemAtTime(on: db, userId: userId, itemId: itemId, purchasedAt: purchasedAt, now: now)
        }
    }

    static func softDeleteForItemAtTime(on db: Database, userId: String, itemId: String, purchasedAt: Int64, now: Int64) throws {
        try db.execute(sql: """
            UPDATE purchase_records
            SET deletedAt = ?, updatedAt = ?, pendingSync = 1
            WHERE itemId = ? AND userId = ?
              AND purchasedAt = ?
              AND deletedAt IS NULL
            """, arguments: [now, now, itemId, userId, purchasedAt])
    }

    func markPushed(userId: String, id: String) async throws {
        try await writer.write { db in
            try db.execute(sql: "UPDATE purchase_records SET pendingSync = 0 WHERE id = ? AND userId = ?", arguments: [id, userId])
        }
    }
}
