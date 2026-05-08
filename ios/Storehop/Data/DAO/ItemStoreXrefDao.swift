import Foundation
import GRDB

/// Owns per-store shopping state since migration v4→v5. Two operations are
/// load-bearing:
///
/// 1. `setStoresForItem` — diff/upsert that the form save flow uses.
/// 2. `markPurchasedAcrossAllStores` — v0.5.1 cross-store cascade. One trip
///    to Lidl marks mozzarella purchased at Aldi and Pingo Doce too.
struct ItemStoreXrefDao: Sendable {
    let writer: any DatabaseWriter

    // MARK: - Reactive

    func observeStoreIdsForItem(itemId: String) -> AsyncValueObservation<[String]> {
        ValueObservation
            .tracking { db in
                try String.fetchAll(db, sql: """
                    SELECT storeId FROM item_store_xref
                    WHERE itemId = ? AND deletedAt IS NULL
                    """, arguments: [itemId])
            }
            .values(in: writer)
    }

    func observePendingPush(userId: String) -> AsyncValueObservation<[ItemStoreXref]> {
        ValueObservation
            .tracking { db in
                try ItemStoreXref.fetchAll(db, sql: """
                    SELECT * FROM item_store_xref
                    WHERE userId = ? AND pendingSync = 1
                    """, arguments: [userId])
            }
            .values(in: writer)
    }

    // MARK: - Snapshot

    func findForItem(itemId: String) async throws -> [ItemStoreXref] {
        try await writer.read { db in try Self.findForItem(on: db, itemId: itemId) }
    }

    static func findForItem(on db: Database, itemId: String) throws -> [ItemStoreXref] {
        try ItemStoreXref.fetchAll(db, sql: """
            SELECT * FROM item_store_xref
            WHERE itemId = ? AND deletedAt IS NULL
            """, arguments: [itemId])
    }

    // MARK: - Writes

    func upsert(_ xref: ItemStoreXref) async throws {
        try await writer.write { db in
            var copy = xref
            try copy.upsert(db)
        }
    }

    func upsertFromCloud(_ rows: [ItemStoreXref], on db: Database) throws {
        for row in rows {
            var copy = row
            try copy.upsert(db)
        }
    }

    func softDelete(userId: String, itemId: String, storeId: String, now: Int64) async throws {
        try await writer.write { db in
            try Self.softDelete(on: db, userId: userId, itemId: itemId, storeId: storeId, now: now)
        }
    }

    static func softDelete(on db: Database, userId: String, itemId: String, storeId: String, now: Int64) throws {
        try db.execute(sql: """
            UPDATE item_store_xref
            SET deletedAt = ?, updatedAt = ?, pendingSync = 1
            WHERE itemId = ? AND storeId = ? AND userId = ?
            """, arguments: [now, now, itemId, storeId, userId])
    }

    func softDeleteForItem(userId: String, itemId: String, now: Int64) async throws {
        try await writer.write { db in try Self.softDeleteForItem(on: db, userId: userId, itemId: itemId, now: now) }
    }

    static func softDeleteForItem(on db: Database, userId: String, itemId: String, now: Int64) throws {
        try db.execute(sql: """
            UPDATE item_store_xref
            SET deletedAt = ?, updatedAt = ?, pendingSync = 1
            WHERE itemId = ? AND userId = ? AND deletedAt IS NULL
            """, arguments: [now, now, itemId, userId])
    }

    func softDeleteForStore(userId: String, storeId: String, now: Int64) async throws {
        try await writer.write { db in try Self.softDeleteForStore(on: db, userId: userId, storeId: storeId, now: now) }
    }

    static func softDeleteForStore(on db: Database, userId: String, storeId: String, now: Int64) throws {
        try db.execute(sql: """
            UPDATE item_store_xref
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
            UPDATE item_store_xref
            SET deletedAt = NULL, updatedAt = ?, pendingSync = 1
            WHERE storeId = ? AND userId = ? AND deletedAt = ?
            """, arguments: [now, storeId, userId, deletedAt])
    }

    func restoreCascadeForItem(userId: String, itemId: String, deletedAt: Int64, now: Int64) async throws {
        try await writer.write { db in
            try Self.restoreCascadeForItem(on: db, userId: userId, itemId: itemId, deletedAt: deletedAt, now: now)
        }
    }

    static func restoreCascadeForItem(on db: Database, userId: String, itemId: String, deletedAt: Int64, now: Int64) throws {
        try db.execute(sql: """
            UPDATE item_store_xref
            SET deletedAt = NULL, updatedAt = ?, pendingSync = 1
            WHERE itemId = ? AND userId = ? AND deletedAt = ?
            """, arguments: [now, itemId, userId, deletedAt])
    }

    /// Replace the set of stores an item is tagged to. Tombstones any xref
    /// no longer in `storeIds` and upserts the new set.
    func setStoresForItem(itemId: String, storeIds: Set<String>, userId: String, now: Int64) async throws {
        try await writer.write { db in
            try Self.setStoresForItem(on: db, itemId: itemId, storeIds: storeIds, userId: userId, now: now)
        }
    }

    static func setStoresForItem(on db: Database, itemId: String, storeIds: Set<String>, userId: String, now: Int64) throws {
        let existing = try findForItem(on: db, itemId: itemId)
        let existingIds = Set(existing.map(\.storeId))
        let toRemove = existingIds.subtracting(storeIds)
        let toAdd = storeIds.subtracting(existingIds)

        for storeId in toRemove {
            try softDelete(on: db, userId: userId, itemId: itemId, storeId: storeId, now: now)
        }
        for storeId in toAdd {
            var xref = ItemStoreXref(
                itemId: itemId,
                storeId: storeId,
                userId: userId,
                createdAt: now,
                updatedAt: now,
                deletedAt: nil,
                pendingSync: true,
                isNeeded: true,
                lastPurchasedAt: nil
            )
            try xref.upsert(db)
        }
    }

    func markPushed(userId: String, itemId: String, storeId: String) async throws {
        try await writer.write { db in
            try db.execute(sql: """
                UPDATE item_store_xref SET pendingSync = 0
                WHERE itemId = ? AND storeId = ? AND userId = ?
                """, arguments: [itemId, storeId, userId])
        }
    }

    // MARK: - Per-store check-off

    func markPurchasedAtStore(userId: String, itemId: String, storeId: String, now: Int64) async throws {
        try await writer.write { db in
            try Self.markPurchasedAtStore(on: db, userId: userId, itemId: itemId, storeId: storeId, now: now)
        }
    }

    static func markPurchasedAtStore(on db: Database, userId: String, itemId: String, storeId: String, now: Int64) throws {
        try db.execute(sql: """
            UPDATE item_store_xref
            SET isNeeded = 0,
                lastPurchasedAt = ?,
                updatedAt = ?,
                pendingSync = 1
            WHERE itemId = ? AND storeId = ? AND userId = ?
            """, arguments: [now, now, itemId, storeId, userId])
    }

    func markPurchasedAcrossAllStores(userId: String, itemId: String, now: Int64) async throws {
        try await writer.write { db in
            try Self.markPurchasedAcrossAllStores(on: db, userId: userId, itemId: itemId, now: now)
        }
    }

    static func markPurchasedAcrossAllStores(on db: Database, userId: String, itemId: String, now: Int64) throws {
        try db.execute(sql: """
            UPDATE item_store_xref
            SET isNeeded = 0,
                lastPurchasedAt = ?,
                updatedAt = ?,
                pendingSync = 1
            WHERE itemId = ? AND userId = ? AND deletedAt IS NULL
            """, arguments: [now, now, itemId, userId])
    }

    func markNeededAtStore(userId: String, itemId: String, storeId: String, now: Int64) async throws {
        try await writer.write { db in
            try Self.markNeededAtStore(on: db, userId: userId, itemId: itemId, storeId: storeId, now: now)
        }
    }

    static func markNeededAtStore(on db: Database, userId: String, itemId: String, storeId: String, now: Int64) throws {
        try db.execute(sql: """
            UPDATE item_store_xref
            SET isNeeded = 1,
                updatedAt = ?,
                pendingSync = 1
            WHERE itemId = ? AND storeId = ? AND userId = ?
            """, arguments: [now, itemId, storeId, userId])
    }

    func restorePurchaseAcrossAllStores(userId: String, itemId: String, lastPurchasedAt: Int64, now: Int64) async throws {
        try await writer.write { db in
            try Self.restorePurchaseAcrossAllStores(on: db, userId: userId, itemId: itemId, lastPurchasedAt: lastPurchasedAt, now: now)
        }
    }

    static func restorePurchaseAcrossAllStores(on db: Database, userId: String, itemId: String, lastPurchasedAt: Int64, now: Int64) throws {
        try db.execute(sql: """
            UPDATE item_store_xref
            SET isNeeded = 1,
                lastPurchasedAt = NULL,
                updatedAt = ?,
                pendingSync = 1
            WHERE itemId = ? AND userId = ?
              AND lastPurchasedAt = ?
              AND isNeeded = 0
            """, arguments: [now, itemId, userId, lastPurchasedAt])
    }
}
