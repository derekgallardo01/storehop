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

    // MARK: - Statistics aggregates

    /// Read-only observations that power the Settings → Statistics screen.
    /// All filter by userId + deletedAt IS NULL so tombstones and other
    /// users' history never leak into visible totals.

    func observeTotalCount(userId: String) -> AsyncValueObservation<Int> {
        ValueObservation
            .tracking { db in
                try Int.fetchOne(db, sql: """
                    SELECT COUNT(*) FROM purchase_records
                    WHERE userId = ? AND deletedAt IS NULL
                    """, arguments: [userId]) ?? 0
            }
            .values(in: writer)
    }

    func observeCountSince(userId: String, sinceMillis: Int64) -> AsyncValueObservation<Int> {
        ValueObservation
            .tracking { db in
                try Int.fetchOne(db, sql: """
                    SELECT COUNT(*) FROM purchase_records
                    WHERE userId = ? AND deletedAt IS NULL AND purchasedAt >= ?
                    """, arguments: [userId, sinceMillis]) ?? 0
            }
            .values(in: writer)
    }

    func observePurchasesPerDay(userId: String, sinceMillis: Int64) -> AsyncValueObservation<[DayCount]> {
        ValueObservation
            .tracking { db in
                try DayCount.fetchAll(db, sql: """
                    SELECT date(purchasedAt / 1000, 'unixepoch', 'localtime') AS day,
                           COUNT(*) AS count
                    FROM purchase_records
                    WHERE userId = ? AND deletedAt IS NULL AND purchasedAt >= ?
                    GROUP BY day
                    ORDER BY day ASC
                    """, arguments: [userId, sinceMillis])
            }
            .values(in: writer)
    }

    func observePurchasesByDayOfWeek(userId: String) -> AsyncValueObservation<[DayOfWeekCount]> {
        ValueObservation
            .tracking { db in
                try DayOfWeekCount.fetchAll(db, sql: """
                    SELECT CAST(strftime('%w', purchasedAt / 1000, 'unixepoch', 'localtime') AS INTEGER) AS dayOfWeek,
                           COUNT(*) AS count
                    FROM purchase_records
                    WHERE userId = ? AND deletedAt IS NULL
                    GROUP BY dayOfWeek
                    ORDER BY count DESC
                    """, arguments: [userId])
            }
            .values(in: writer)
    }

    func observeTopItems(userId: String, limit: Int) -> AsyncValueObservation<[ItemPurchaseCount]> {
        ValueObservation
            .tracking { db in
                try ItemPurchaseCount.fetchAll(db, sql: """
                    SELECT itemId, COUNT(*) AS count
                    FROM purchase_records
                    WHERE userId = ? AND deletedAt IS NULL
                    GROUP BY itemId
                    ORDER BY count DESC
                    LIMIT ?
                    """, arguments: [userId, limit])
            }
            .values(in: writer)
    }

    func observePurchasesByStore(userId: String) -> AsyncValueObservation<[StorePurchaseCount]> {
        ValueObservation
            .tracking { db in
                try StorePurchaseCount.fetchAll(db, sql: """
                    SELECT storeId, COUNT(*) AS count
                    FROM purchase_records
                    WHERE userId = ? AND deletedAt IS NULL AND storeId IS NOT NULL
                    GROUP BY storeId
                    ORDER BY count DESC
                    """, arguments: [userId])
            }
            .values(in: writer)
    }

    /// Group purchase records by the category of the item that was bought.
    /// Items whose categoryId is NULL (uncategorised) report the empty
    /// string so the UI can show an "Uncategorised" bucket.
    func observePurchasesByCategory(userId: String) -> AsyncValueObservation<[CategoryPurchaseCount]> {
        ValueObservation
            .tracking { db in
                try CategoryPurchaseCount.fetchAll(db, sql: """
                    SELECT COALESCE(items.categoryId, '') AS categoryId, COUNT(*) AS count
                    FROM purchase_records
                    INNER JOIN items ON items.id = purchase_records.itemId
                    WHERE purchase_records.userId = ?
                      AND purchase_records.deletedAt IS NULL
                      AND items.deletedAt IS NULL
                    GROUP BY categoryId
                    ORDER BY count DESC
                    """, arguments: [userId])
            }
            .values(in: writer)
    }
}

/// Daily purchase count, projection of `observePurchasesPerDay`.
struct DayCount: Codable, FetchableRecord, Hashable, Sendable {
    /// ISO date (YYYY-MM-DD) in the device's local timezone.
    let day: String
    let count: Int
}

/// Day-of-week purchase count where dayOfWeek is 0 (Sunday) – 6 (Saturday).
struct DayOfWeekCount: Codable, FetchableRecord, Hashable, Sendable {
    let dayOfWeek: Int
    let count: Int
}

/// Per-item aggregate, projection of `observeTopItems`.
struct ItemPurchaseCount: Codable, FetchableRecord, Hashable, Sendable {
    let itemId: String
    let count: Int
}

/// Per-store aggregate, projection of `observePurchasesByStore`.
struct StorePurchaseCount: Codable, FetchableRecord, Hashable, Sendable {
    let storeId: String
    let count: Int
}

/// Per-category aggregate; empty `categoryId` means uncategorised.
struct CategoryPurchaseCount: Codable, FetchableRecord, Hashable, Sendable {
    let categoryId: String
    let count: Int
}
