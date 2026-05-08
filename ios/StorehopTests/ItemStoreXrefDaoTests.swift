import XCTest
import GRDB
@testable import Storehop

/// Covers the workhorse DAO. The two load-bearing operations:
///   - setStoresForItem: diff/upsert in one transaction
///   - markPurchasedAcrossAllStores + restorePurchaseAcrossAllStores: the
///     v0.5.1 cross-store cascade and snapshot-precise undo
final class ItemStoreXrefDaoTests: XCTestCase {

    private func setup() throws -> (StorehopDatabase, ItemStoreXrefDao) {
        let db = try StorehopDatabase.inMemoryForTests()
        try db.seed(
            items: [TestFixtures.item(id: "i1", name: "Mozzarella")],
            stores: [
                TestFixtures.store(id: "s_lidl", name: "Lidl"),
                TestFixtures.store(id: "s_aldi", name: "Aldi"),
                TestFixtures.store(id: "s_continente", name: "Continente"),
            ]
        )
        return (db, ItemStoreXrefDao(writer: db.queue))
    }

    // MARK: - setStoresForItem

    func testSetStoresForItemAddsNewXrefsOnFirstCall() async throws {
        let (db, dao) = try setup()
        try await dao.setStoresForItem(itemId: "i1", storeIds: ["s_lidl", "s_aldi"], userId: "u1", now: 1_000)

        let xrefs = try await dao.findForItem(itemId: "i1")
        XCTAssertEqual(Set(xrefs.map(\.storeId)), ["s_lidl", "s_aldi"])
        for x in xrefs {
            XCTAssertTrue(x.isNeeded)
            XCTAssertEqual(x.userId, "u1")
            XCTAssertEqual(x.createdAt, 1_000)
        }
        _ = db
    }

    func testSetStoresForItemTombstonesRemovedAndUpsertsAdded() async throws {
        let (_, dao) = try setup()
        try await dao.setStoresForItem(itemId: "i1", storeIds: ["s_lidl", "s_aldi"], userId: "u1", now: 1_000)
        try await dao.setStoresForItem(itemId: "i1", storeIds: ["s_lidl", "s_continente"], userId: "u1", now: 2_000)

        let xrefs = try await dao.findForItem(itemId: "i1")
        XCTAssertEqual(Set(xrefs.map(\.storeId)), ["s_lidl", "s_continente"])
    }

    func testSetStoresForItemPreservesExistingXrefStateOnUntouchedRows() async throws {
        let (_, dao) = try setup()
        try await dao.setStoresForItem(itemId: "i1", storeIds: ["s_lidl", "s_aldi"], userId: "u1", now: 1_000)
        // Mark Lidl purchased at t=1500.
        try await dao.markPurchasedAtStore(userId: "u1", itemId: "i1", storeId: "s_lidl", now: 1_500)

        // Re-save with same set of stores — should not flip Lidl back to needed.
        try await dao.setStoresForItem(itemId: "i1", storeIds: ["s_lidl", "s_aldi"], userId: "u1", now: 2_000)

        let lidl = try await dao.findForItem(itemId: "i1").first { $0.storeId == "s_lidl" }
        XCTAssertNotNil(lidl)
        XCTAssertFalse(lidl?.isNeeded ?? true, "Lidl xref must remain purchased after a no-op set")
        XCTAssertEqual(lidl?.lastPurchasedAt, 1_500)
    }

    // MARK: - cross-store cascade

    func testMarkPurchasedAcrossAllStoresCascadesToEveryLiveXref() async throws {
        let (_, dao) = try setup()
        try await dao.setStoresForItem(itemId: "i1", storeIds: ["s_lidl", "s_aldi", "s_continente"], userId: "u1", now: 1_000)

        try await dao.markPurchasedAcrossAllStores(userId: "u1", itemId: "i1", now: 5_000)

        let xrefs = try await dao.findForItem(itemId: "i1")
        XCTAssertEqual(xrefs.count, 3)
        for x in xrefs {
            XCTAssertFalse(x.isNeeded, "Cascade must flip every live xref to !isNeeded")
            XCTAssertEqual(x.lastPurchasedAt, 5_000)
        }
    }

    func testRestorePurchaseAcrossAllStoresRollsBackOnlyTheCascadedRows() async throws {
        let (_, dao) = try setup()
        try await dao.setStoresForItem(itemId: "i1", storeIds: ["s_lidl", "s_aldi"], userId: "u1", now: 1_000)

        // Earlier unrelated purchase at Aldi at t=2000.
        try await dao.markPurchasedAtStore(userId: "u1", itemId: "i1", storeId: "s_aldi", now: 2_000)
        // Re-need Aldi, then later cascade-purchase at t=5000.
        try await dao.markNeededAtStore(userId: "u1", itemId: "i1", storeId: "s_aldi", now: 3_000)
        try await dao.markPurchasedAcrossAllStores(userId: "u1", itemId: "i1", now: 5_000)

        // Undo the t=5000 cascade.
        try await dao.restorePurchaseAcrossAllStores(userId: "u1", itemId: "i1", lastPurchasedAt: 5_000, now: 6_000)

        let xrefs = try await dao.findForItem(itemId: "i1")
        XCTAssertEqual(xrefs.count, 2)
        for x in xrefs {
            XCTAssertTrue(x.isNeeded, "Both xrefs should be needed again")
            XCTAssertNil(x.lastPurchasedAt, "lastPurchasedAt should be cleared by the undo")
        }
    }

    func testRestorePurchaseAcrossAllStoresIsNoOpForUnrelatedTimestamp() async throws {
        let (_, dao) = try setup()
        try await dao.setStoresForItem(itemId: "i1", storeIds: ["s_lidl"], userId: "u1", now: 1_000)
        try await dao.markPurchasedAcrossAllStores(userId: "u1", itemId: "i1", now: 5_000)

        try await dao.restorePurchaseAcrossAllStores(userId: "u1", itemId: "i1", lastPurchasedAt: 9_999, now: 6_000)

        let lidl = try await dao.findForItem(itemId: "i1").first
        XCTAssertNotNil(lidl)
        XCTAssertFalse(lidl?.isNeeded ?? true, "Wrong-timestamp undo must not restore the row")
        XCTAssertEqual(lidl?.lastPurchasedAt, 5_000)
    }

    // MARK: - per-store flip

    func testMarkNeededAtStoreDoesNotClearLastPurchasedAt() async throws {
        let (_, dao) = try setup()
        try await dao.setStoresForItem(itemId: "i1", storeIds: ["s_lidl"], userId: "u1", now: 1_000)
        try await dao.markPurchasedAtStore(userId: "u1", itemId: "i1", storeId: "s_lidl", now: 2_000)

        try await dao.markNeededAtStore(userId: "u1", itemId: "i1", storeId: "s_lidl", now: 3_000)

        let lidl = try await dao.findForItem(itemId: "i1").first
        XCTAssertEqual(lidl?.isNeeded, true)
        XCTAssertEqual(lidl?.lastPurchasedAt, 2_000, "Re-needing keeps the prior purchase timestamp — the purchase still happened in history")
    }

    // MARK: - cascade tombstones

    func testSoftDeleteForItemTombstonesAllXrefs() async throws {
        let (_, dao) = try setup()
        try await dao.setStoresForItem(itemId: "i1", storeIds: ["s_lidl", "s_aldi"], userId: "u1", now: 1_000)
        try await dao.softDeleteForItem(userId: "u1", itemId: "i1", now: 2_000)

        let live = try await dao.findForItem(itemId: "i1")
        XCTAssertEqual(live.count, 0)
    }

    func testRestoreCascadeForItemFiltersByExactDeletedAt() async throws {
        let (db, dao) = try setup()
        try await dao.setStoresForItem(itemId: "i1", storeIds: ["s_lidl", "s_aldi"], userId: "u1", now: 1_000)
        try await dao.softDeleteForItem(userId: "u1", itemId: "i1", now: 2_000)
        // Tombstone again at a different timestamp (e.g. an unrelated cascade).
        try await db.queue.write { conn in
            try conn.execute(sql: """
                INSERT INTO items (id, name, isNeeded, userId, createdAt, updatedAt, deletedAt)
                VALUES ('i2', 'Bread', 1, 'u1', 0, 0, 0)
                """)
            try conn.execute(sql: """
                INSERT INTO item_store_xref (itemId, storeId, userId, createdAt, updatedAt, deletedAt)
                VALUES ('i2', 's_lidl', 'u1', 0, 0, 9999)
                """)
        }

        // Restore only the i1 cascade (deletedAt = 2000).
        try await dao.restoreCascadeForItem(userId: "u1", itemId: "i1", deletedAt: 2_000, now: 3_000)

        let i1Live = try await dao.findForItem(itemId: "i1")
        XCTAssertEqual(i1Live.count, 2)
        let i2Live = try await dao.findForItem(itemId: "i2")
        XCTAssertEqual(i2Live.count, 0, "Unrelated tombstone with different deletedAt must not be restored")
    }
}
