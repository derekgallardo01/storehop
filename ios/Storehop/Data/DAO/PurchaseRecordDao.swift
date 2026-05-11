import Foundation
import GRDB

/// v0.7.0 access scope: a deliberate split, NOT a flat rename.
///
///  * **Cross-cascade methods** (`softDeleteForItem`, `restoreCascadeForItem`)
///    scope by `householdId`: when an item is deleted from the household,
///    every member's purchase records for that item cascade-tombstone
///    together. Otherwise we'd leave dangling records pointing at a row
///    nobody can see.
///  * **Stats + history-view queries** (`observeForItem`, `observeTotalCount`,
///    `observeTopItems`, all the aggregates) stay scoped by `userId` — the
///    v0.7.0 design decision is **per-user stats**: "what I bought," not
///    "what we bought as a household." Mike wants his own history;
///    aggregating Amanda's into his charts would be wrong.
///  * **Per-record CRUD** (`softDelete` by id, `softDeleteForItemAtTime` for
///    snackbar undo) stays scoped by `userId` — only the purchaser can
///    rescind their own record.
///  * **Sync push** (`observePendingPush`, `markPushed`) scopes by
///    `householdId` for parity with the other DAOs that have already
///    migrated; in single-member households `userId == householdId` so
///    the set is identical.
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

    func observePendingPush(householdId: String) -> AsyncValueObservation<[PurchaseRecord]> {
        ValueObservation
            .tracking { db in
                try PurchaseRecord.fetchAll(db, sql: """
                    SELECT * FROM purchase_records
                    WHERE householdId = ? AND pendingSync = 1
                    """, arguments: [householdId])
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

    /// Per-record undo. Filtered by **user** — only the purchaser can
    /// rescind their own record.
    func softDelete(userId: String, id: String, now: Int64) async throws {
        try await writer.write { db in
            try db.execute(sql: """
                UPDATE purchase_records
                SET deletedAt = ?, updatedAt = ?, pendingSync = 1
                WHERE id = ? AND userId = ?
                """, arguments: [now, now, id, userId])
        }
    }

    /// Cascade-tombstone all purchase records for an item — including those
    /// created by other household members. Used by the item soft-delete flow
    /// so a deleted item doesn't leave purchase-history orphans visible to
    /// `observeForItem`. Bound by `householdId` so an item-delete cascades
    /// across every member's records under the household.
    func softDeleteForItem(householdId: String, itemId: String, now: Int64) async throws {
        try await writer.write { db in
            try Self.softDeleteForItem(on: db, householdId: householdId, itemId: itemId, now: now)
        }
    }

    static func softDeleteForItem(on db: Database, householdId: String, itemId: String, now: Int64) throws {
        try db.execute(sql: """
            UPDATE purchase_records
            SET deletedAt = ?, updatedAt = ?, pendingSync = 1
            WHERE itemId = ? AND householdId = ? AND deletedAt IS NULL
            """, arguments: [now, now, itemId, householdId])
    }

    /// Inverse of `softDeleteForItem`, filtered by exact `deletedAt`.
    func restoreCascadeForItem(householdId: String, itemId: String, deletedAt: Int64, now: Int64) async throws {
        try await writer.write { db in
            try Self.restoreCascadeForItem(on: db, householdId: householdId, itemId: itemId, deletedAt: deletedAt, now: now)
        }
    }

    static func restoreCascadeForItem(on db: Database, householdId: String, itemId: String, deletedAt: Int64, now: Int64) throws {
        try db.execute(sql: """
            UPDATE purchase_records
            SET deletedAt = NULL, updatedAt = ?, pendingSync = 1
            WHERE itemId = ? AND householdId = ? AND deletedAt = ?
            """, arguments: [now, itemId, householdId, deletedAt])
    }

    /// Soft-delete the live PurchaseRecord(s) for `itemId` whose
    /// `purchasedAt` matches exactly. Used by the snackbar-undo path after a
    /// cascade purchase, to roll back history alongside the xref restore.
    /// Filtered by **user** (the purchaser) and live-only — undo is per-user,
    /// so Amanda's snackbar can never rescind Mike's purchase record.
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

    func markPushed(householdId: String, id: String) async throws {
        try await writer.write { db in
            try db.execute(sql: "UPDATE purchase_records SET pendingSync = 0 WHERE id = ? AND householdId = ?", arguments: [id, householdId])
        }
    }

    // MARK: - Statistics aggregates

    /// Read-only observations that power the Settings → Statistics screen.
    /// All filter by **userId** (the purchaser) + `deletedAt IS NULL` — per
    /// the v0.7.0 design, stats are per-user, not per-household. Mike's
    /// charts don't bleed into Amanda's even when they share a household.

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
