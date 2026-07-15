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
        try await dao.setStoresForItem(itemId: "i1", storeIds: ["s_lidl", "s_aldi"], householdId: "u1", userId: "u1", now: 1_000)

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
        try await dao.setStoresForItem(itemId: "i1", storeIds: ["s_lidl", "s_aldi"], householdId: "u1", userId: "u1", now: 1_000)
        try await dao.setStoresForItem(itemId: "i1", storeIds: ["s_lidl", "s_continente"], householdId: "u1", userId: "u1", now: 2_000)

        let xrefs = try await dao.findForItem(itemId: "i1")
        XCTAssertEqual(Set(xrefs.map(\.storeId)), ["s_lidl", "s_continente"])
    }

    func testSetStoresForItemUnStrandsRetainedStoreWhenNeededElsewhere() async throws {
        // v0.9 (#3): Lidl stranded at !isNeeded while Aldi is still needed.
        // Re-saving with both stores selected must converge Lidl back to
        // needed so a tagged store never silently drops off the list.
        let (_, dao) = try setup()
        try await dao.setStoresForItem(itemId: "i1", storeIds: ["s_lidl", "s_aldi"], householdId: "u1", userId: "u1", now: 1_000)
        try await dao.markPurchasedAtStore(householdId: "u1", itemId: "i1", storeId: "s_lidl", now: 1_500)

        try await dao.setStoresForItem(itemId: "i1", storeIds: ["s_lidl", "s_aldi"], householdId: "u1", userId: "u1", now: 2_000)

        let lidl = try await dao.findForItem(itemId: "i1").first { $0.storeId == "s_lidl" }
        XCTAssertNotNil(lidl)
        XCTAssertTrue(lidl?.isNeeded ?? false, "Lidl must be converged back to needed (item is needed at Aldi)")
    }

    func testSetStoresForItemLeavesFullyPurchasedItemPurchased() async throws {
        // Editing a bought-everywhere item must NOT resurrect it onto lists.
        let (_, dao) = try setup()
        try await dao.setStoresForItem(itemId: "i1", storeIds: ["s_lidl", "s_aldi"], householdId: "u1", userId: "u1", now: 1_000)
        try await dao.markPurchasedAcrossAllStores(householdId: "u1", itemId: "i1", now: 1_500)

        try await dao.setStoresForItem(itemId: "i1", storeIds: ["s_lidl", "s_aldi"], householdId: "u1", userId: "u1", now: 2_000)

        let xrefs = try await dao.findForItem(itemId: "i1")
        XCTAssertTrue(xrefs.allSatisfy { !$0.isNeeded }, "Fully-purchased item stays purchased on re-save")
    }

    // MARK: - v0.9 Force-Sync repair (#3)

    func testRepairStrandedNeededLinksConvergesNeededItemsAndSkipsPurchased() async throws {
        let (_, dao) = try setup()
        // i1 needed at Lidl, stranded at Aldi -> both should end needed.
        try await dao.setStoresForItem(itemId: "i1", storeIds: ["s_lidl", "s_aldi"], householdId: "u1", userId: "u1", now: 1_000)
        try await dao.markPurchasedAtStore(householdId: "u1", itemId: "i1", storeId: "s_aldi", now: 1_500)

        let repaired = try await dao.repairStrandedNeededLinks(householdId: "u1", now: 9_000)

        XCTAssertEqual(repaired, 1, "Only the stranded Aldi row should be repaired")
        let xrefs = try await dao.findForItem(itemId: "i1")
        XCTAssertTrue(xrefs.allSatisfy { $0.isNeeded })

        // Idempotent: a second pass repairs nothing.
        let again = try await dao.repairStrandedNeededLinks(householdId: "u1", now: 9_100)
        XCTAssertEqual(again, 0)
    }

    // MARK: - cross-store cascade

    func testMarkPurchasedAcrossAllStoresCascadesToEveryLiveXref() async throws {
        let (_, dao) = try setup()
        try await dao.setStoresForItem(itemId: "i1", storeIds: ["s_lidl", "s_aldi", "s_continente"], householdId: "u1", userId: "u1", now: 1_000)

        try await dao.markPurchasedAcrossAllStores(householdId: "u1", itemId: "i1", now: 5_000)

        let xrefs = try await dao.findForItem(itemId: "i1")
        XCTAssertEqual(xrefs.count, 3)
        for x in xrefs {
            XCTAssertFalse(x.isNeeded, "Cascade must flip every live xref to !isNeeded")
            XCTAssertEqual(x.lastPurchasedAt, 5_000)
        }
    }

    func testRestorePurchaseAcrossAllStoresRollsBackOnlyTheCascadedRows() async throws {
        let (_, dao) = try setup()
        try await dao.setStoresForItem(itemId: "i1", storeIds: ["s_lidl", "s_aldi"], householdId: "u1", userId: "u1", now: 1_000)

        // Earlier unrelated purchase at Aldi at t=2000.
        try await dao.markPurchasedAtStore(householdId: "u1", itemId: "i1", storeId: "s_aldi", now: 2_000)
        // Re-need Aldi, then later cascade-purchase at t=5000.
        try await dao.markNeededAtStore(householdId: "u1", itemId: "i1", storeId: "s_aldi", now: 3_000)
        try await dao.markPurchasedAcrossAllStores(householdId: "u1", itemId: "i1", now: 5_000)

        // Undo the t=5000 cascade.
        try await dao.restorePurchaseAcrossAllStores(householdId: "u1", itemId: "i1", lastPurchasedAt: 5_000, now: 6_000)

        let xrefs = try await dao.findForItem(itemId: "i1")
        XCTAssertEqual(xrefs.count, 2)
        for x in xrefs {
            XCTAssertTrue(x.isNeeded, "Both xrefs should be needed again")
            XCTAssertNil(x.lastPurchasedAt, "lastPurchasedAt should be cleared by the undo")
        }
    }

    func testRestorePurchaseAcrossAllStoresIsNoOpForUnrelatedTimestamp() async throws {
        let (_, dao) = try setup()
        try await dao.setStoresForItem(itemId: "i1", storeIds: ["s_lidl"], householdId: "u1", userId: "u1", now: 1_000)
        try await dao.markPurchasedAcrossAllStores(householdId: "u1", itemId: "i1", now: 5_000)

        try await dao.restorePurchaseAcrossAllStores(householdId: "u1", itemId: "i1", lastPurchasedAt: 9_999, now: 6_000)

        let lidl = try await dao.findForItem(itemId: "i1").first
        XCTAssertNotNil(lidl)
        XCTAssertFalse(lidl?.isNeeded ?? true, "Wrong-timestamp undo must not restore the row")
        XCTAssertEqual(lidl?.lastPurchasedAt, 5_000)
    }

    // MARK: - per-store flip

    func testMarkNeededAtStoreDoesNotClearLastPurchasedAt() async throws {
        let (_, dao) = try setup()
        try await dao.setStoresForItem(itemId: "i1", storeIds: ["s_lidl"], householdId: "u1", userId: "u1", now: 1_000)
        try await dao.markPurchasedAtStore(householdId: "u1", itemId: "i1", storeId: "s_lidl", now: 2_000)

        try await dao.markNeededAtStore(householdId: "u1", itemId: "i1", storeId: "s_lidl", now: 3_000)

        let lidl = try await dao.findForItem(itemId: "i1").first
        XCTAssertEqual(lidl?.isNeeded, true)
        XCTAssertEqual(lidl?.lastPurchasedAt, 2_000, "Re-needing keeps the prior purchase timestamp — the purchase still happened in history")
    }

    // MARK: - cascade tombstones

    func testSoftDeleteForItemTombstonesAllXrefs() async throws {
        let (_, dao) = try setup()
        try await dao.setStoresForItem(itemId: "i1", storeIds: ["s_lidl", "s_aldi"], householdId: "u1", userId: "u1", now: 1_000)
        try await dao.softDeleteForItem(householdId: "u1", itemId: "i1", now: 2_000)

        let live = try await dao.findForItem(itemId: "i1")
        XCTAssertEqual(live.count, 0)
    }

    func testRestoreCascadeForItemFiltersByExactDeletedAt() async throws {
        let (db, dao) = try setup()
        try await dao.setStoresForItem(itemId: "i1", storeIds: ["s_lidl", "s_aldi"], householdId: "u1", userId: "u1", now: 1_000)
        try await dao.softDeleteForItem(householdId: "u1", itemId: "i1", now: 2_000)
        // Tombstone again at a different timestamp (e.g. an unrelated cascade).
        try await db.queue.write { conn in
            try conn.execute(sql: """
                INSERT INTO items (id, name, isNeeded, userId, createdAt, updatedAt, deletedAt, householdId)
                VALUES ('i2', 'Bread', 1, 'u1', 0, 0, 0, 'u1')
                """)
            try conn.execute(sql: """
                INSERT INTO item_store_xref (itemId, storeId, userId, createdAt, updatedAt, deletedAt, householdId)
                VALUES ('i2', 's_lidl', 'u1', 0, 0, 9999, 'u1')
                """)
        }

        // Restore only the i1 cascade (deletedAt = 2000).
        try await dao.restoreCascadeForItem(householdId: "u1", itemId: "i1", deletedAt: 2_000, now: 3_000)

        let i1Live = try await dao.findForItem(itemId: "i1")
        XCTAssertEqual(i1Live.count, 2)
        let i2Live = try await dao.findForItem(itemId: "i2")
        XCTAssertEqual(i2Live.count, 0, "Unrelated tombstone with different deletedAt must not be restored")
    }
}
