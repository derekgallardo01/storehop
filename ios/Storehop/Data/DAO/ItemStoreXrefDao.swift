import Foundation
import GRDB

/// Owns per-store shopping state since migration v4→v5. Two operations are
/// load-bearing:
///
/// 1. `setStoresForItem` — diff/upsert that the form save flow uses.
/// 2. `markPurchasedAcrossAllStores` — v0.5.1 cross-store cascade. One trip
///    to Lidl marks mozzarella purchased at Aldi and Pingo Doce too.
///
/// v0.7.0 access scope: queries filter by `householdId` (not `userId`).
/// `userId` remains on each xref as creator/audit metadata — copied from
/// the parent item at insert time so cross-table ownership stays coherent
/// across session changes. `householdId` is what scopes who can see and
/// mutate the row. For single-member households both columns hold the
/// same value, so behaviour matches v0.6.x exactly.
///
/// `setStoresForItem` takes both: `householdId` to scope the existing-row
/// lookup and to stamp on new rows, plus `userId` to carry the parent's
/// creator stamp through onto the freshly-inserted junction rows.
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

    /// v0.6.1: distinct item IDs that have at least one alive xref with
    /// `isNeeded = 1` for the given household. Powers the +/- toggle on the
    /// Items list -- "−" when the item is on the list at any tagged store,
    /// "+" otherwise.
    func observeNeededItemIds(householdId: String) -> AsyncValueObservation<[String]> {
        ValueObservation
            .tracking { db in
                try String.fetchAll(db, sql: """
                    SELECT DISTINCT itemId FROM item_store_xref
                    WHERE householdId = ? AND deletedAt IS NULL AND isNeeded = 1
                    """, arguments: [householdId])
            }
            .values(in: writer)
    }

    /// v0.7.1: row-count of pending pushes for the Force-sync-now UX.
    func countPendingPush(householdId: String) -> AsyncValueObservation<Int> {
        ValueObservation
            .tracking { db in
                try Int.fetchOne(db, sql: """
                    SELECT COUNT(*) FROM item_store_xref
                    WHERE householdId = ? AND pendingSync = 1
                    """, arguments: [householdId]) ?? 0
            }
            .values(in: writer)
    }

    func observePendingPush(householdId: String) -> AsyncValueObservation<[ItemStoreXref]> {
        ValueObservation
            .tracking { db in
                try ItemStoreXref.fetchAll(db, sql: """
                    SELECT * FROM item_store_xref
                    WHERE householdId = ? AND pendingSync = 1
                    """, arguments: [householdId])
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

    func softDelete(householdId: String, itemId: String, storeId: String, now: Int64) async throws {
        try await writer.write { db in
            try Self.softDelete(on: db, householdId: householdId, itemId: itemId, storeId: storeId, now: now)
        }
    }

    static func softDelete(on db: Database, householdId: String, itemId: String, storeId: String, now: Int64) throws {
        try db.execute(sql: """
            UPDATE item_store_xref
            SET deletedAt = ?, updatedAt = ?, pendingSync = 1
            WHERE itemId = ? AND storeId = ? AND householdId = ?
            """, arguments: [now, now, itemId, storeId, householdId])
    }

    func softDeleteForItem(householdId: String, itemId: String, now: Int64) async throws {
        try await writer.write { db in try Self.softDeleteForItem(on: db, householdId: householdId, itemId: itemId, now: now) }
    }

    static func softDeleteForItem(on db: Database, householdId: String, itemId: String, now: Int64) throws {
        try db.execute(sql: """
            UPDATE item_store_xref
            SET deletedAt = ?, updatedAt = ?, pendingSync = 1
            WHERE itemId = ? AND householdId = ? AND deletedAt IS NULL
            """, arguments: [now, now, itemId, householdId])
    }

    func softDeleteForStore(householdId: String, storeId: String, now: Int64) async throws {
        try await writer.write { db in try Self.softDeleteForStore(on: db, householdId: householdId, storeId: storeId, now: now) }
    }

    static func softDeleteForStore(on db: Database, householdId: String, storeId: String, now: Int64) throws {
        try db.execute(sql: """
            UPDATE item_store_xref
            SET deletedAt = ?, updatedAt = ?, pendingSync = 1
            WHERE storeId = ? AND householdId = ? AND deletedAt IS NULL
            """, arguments: [now, now, storeId, householdId])
    }

    func restoreCascadeForStore(householdId: String, storeId: String, deletedAt: Int64, now: Int64) async throws {
        try await writer.write { db in
            try Self.restoreCascadeForStore(on: db, householdId: householdId, storeId: storeId, deletedAt: deletedAt, now: now)
        }
    }

    static func restoreCascadeForStore(on db: Database, householdId: String, storeId: String, deletedAt: Int64, now: Int64) throws {
        try db.execute(sql: """
            UPDATE item_store_xref
            SET deletedAt = NULL, updatedAt = ?, pendingSync = 1
            WHERE storeId = ? AND householdId = ? AND deletedAt = ?
            """, arguments: [now, storeId, householdId, deletedAt])
    }

    func restoreCascadeForItem(householdId: String, itemId: String, deletedAt: Int64, now: Int64) async throws {
        try await writer.write { db in
            try Self.restoreCascadeForItem(on: db, householdId: householdId, itemId: itemId, deletedAt: deletedAt, now: now)
        }
    }

    static func restoreCascadeForItem(on db: Database, householdId: String, itemId: String, deletedAt: Int64, now: Int64) throws {
        try db.execute(sql: """
            UPDATE item_store_xref
            SET deletedAt = NULL, updatedAt = ?, pendingSync = 1
            WHERE itemId = ? AND householdId = ? AND deletedAt = ?
            """, arguments: [now, itemId, householdId, deletedAt])
    }

    /// Replace the set of stores an item is tagged to. Tombstones any xref
    /// no longer in `storeIds` and upserts the new set.
    /// `householdId` scopes who owns the row (the parent item's householdId);
    /// `userId` is the parent's creator-stamp copied onto the new junction rows.
    func setStoresForItem(itemId: String, storeIds: Set<String>, householdId: String, userId: String, now: Int64) async throws {
        try await writer.write { db in
            try Self.setStoresForItem(on: db, itemId: itemId, storeIds: storeIds, householdId: householdId, userId: userId, now: now)
        }
    }

    static func setStoresForItem(on db: Database, itemId: String, storeIds: Set<String>, householdId: String, userId: String, now: Int64) throws {
        let existing = try findForItem(on: db, itemId: itemId)
        let existingIds = Set(existing.map(\.storeId))
        let toRemove = existingIds.subtracting(storeIds)
        let toAdd = storeIds.subtracting(existingIds)

        for storeId in toRemove {
            try softDelete(on: db, householdId: householdId, itemId: itemId, storeId: storeId, now: now)
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
                lastPurchasedAt: nil,
                householdId: householdId
            )
            try xref.upsert(db)
        }
    }

    func markPushed(householdId: String, itemId: String, storeId: String) async throws {
        try await writer.write { db in
            try db.execute(sql: """
                UPDATE item_store_xref SET pendingSync = 0
                WHERE itemId = ? AND storeId = ? AND householdId = ?
                """, arguments: [itemId, storeId, householdId])
        }
    }

    // MARK: - Per-store check-off

    func markPurchasedAtStore(householdId: String, itemId: String, storeId: String, now: Int64) async throws {
        try await writer.write { db in
            try Self.markPurchasedAtStore(on: db, householdId: householdId, itemId: itemId, storeId: storeId, now: now)
        }
    }

    static func markPurchasedAtStore(on db: Database, householdId: String, itemId: String, storeId: String, now: Int64) throws {
        try db.execute(sql: """
            UPDATE item_store_xref
            SET isNeeded = 0,
                lastPurchasedAt = ?,
                updatedAt = ?,
                pendingSync = 1
            WHERE itemId = ? AND storeId = ? AND householdId = ?
            """, arguments: [now, now, itemId, storeId, householdId])
    }

    func markPurchasedAcrossAllStores(householdId: String, itemId: String, now: Int64) async throws {
        try await writer.write { db in
            try Self.markPurchasedAcrossAllStores(on: db, householdId: householdId, itemId: itemId, now: now)
        }
    }

    static func markPurchasedAcrossAllStores(on db: Database, householdId: String, itemId: String, now: Int64) throws {
        try db.execute(sql: """
            UPDATE item_store_xref
            SET isNeeded = 0,
                lastPurchasedAt = ?,
                updatedAt = ?,
                pendingSync = 1
            WHERE itemId = ? AND householdId = ? AND deletedAt IS NULL
            """, arguments: [now, now, itemId, householdId])
    }

    /// v0.6.1: inverse of [markPurchasedAcrossAllStores]. Sets every alive
    /// xref for [itemId] to `isNeeded = 1, lastPurchasedAt = NULL`. Used by
    /// the "+" tap on the Items list to add an item to every tagged store
    /// without writing a PurchaseRecord -- the user is on the master list,
    /// not at a store.
    func markNeededAcrossAllStores(householdId: String, itemId: String, now: Int64) async throws {
        try await writer.write { db in
            try Self.markNeededAcrossAllStores(on: db, householdId: householdId, itemId: itemId, now: now)
        }
    }

    static func markNeededAcrossAllStores(on db: Database, householdId: String, itemId: String, now: Int64) throws {
        try db.execute(sql: """
            UPDATE item_store_xref
            SET isNeeded = 1,
                lastPurchasedAt = NULL,
                updatedAt = ?,
                pendingSync = 1
            WHERE itemId = ? AND householdId = ? AND deletedAt IS NULL
            """, arguments: [now, itemId, householdId])
    }

    func markNeededAtStore(householdId: String, itemId: String, storeId: String, now: Int64) async throws {
        try await writer.write { db in
            try Self.markNeededAtStore(on: db, householdId: householdId, itemId: itemId, storeId: storeId, now: now)
        }
    }

    static func markNeededAtStore(on db: Database, householdId: String, itemId: String, storeId: String, now: Int64) throws {
        try db.execute(sql: """
            UPDATE item_store_xref
            SET isNeeded = 1,
                updatedAt = ?,
                pendingSync = 1
            WHERE itemId = ? AND storeId = ? AND householdId = ?
            """, arguments: [now, itemId, storeId, householdId])
    }

    func restorePurchaseAcrossAllStores(householdId: String, itemId: String, lastPurchasedAt: Int64, now: Int64) async throws {
        try await writer.write { db in
            try Self.restorePurchaseAcrossAllStores(on: db, householdId: householdId, itemId: itemId, lastPurchasedAt: lastPurchasedAt, now: now)
        }
    }

    static func restorePurchaseAcrossAllStores(on db: Database, householdId: String, itemId: String, lastPurchasedAt: Int64, now: Int64) throws {
        try db.execute(sql: """
            UPDATE item_store_xref
            SET isNeeded = 1,
                lastPurchasedAt = NULL,
                updatedAt = ?,
                pendingSync = 1
            WHERE itemId = ? AND householdId = ?
              AND lastPurchasedAt = ?
              AND isNeeded = 0
            """, arguments: [now, itemId, householdId, lastPurchasedAt])
    }
}
