import XCTest
import GRDB
@testable import Storehop

/// Pins the contract for the pull-side batch writer:
///   - All entity types land in one transaction.
///   - FK violation rolls back the entire batch (not partial).
///   - Empty lists are no-op.
///   - Idempotent on re-pull (upserts overwrite).
final class PullWriteDaoTests: XCTestCase {

    func testReplaceAllForUidLandsAllSixEntityTypes() async throws {
        let db = try StorehopDatabase.inMemoryForTests()
        let dao = PullWriteDao(writer: db.queue)

        let category = TestFixtures.category(id: "c1")
        let store = TestFixtures.store(id: "s1")
        let item = TestFixtures.item(id: "i1", categoryId: "c1")
        let xref = TestFixtures.xref(itemId: "i1", storeId: "s1")
        let sco = TestFixtures.sco(storeId: "s1", categoryId: "c1")
        let purchase = PurchaseRecord(
            id: "p1", itemId: "i1", storeId: "s1",
            purchasedAt: 1_000, userId: "u1",
            createdAt: 0, updatedAt: 0, deletedAt: nil, pendingSync: false,
            householdId: "u1"
        )

        try await dao.replaceAllForUid(
            householdId: "u1",
            items: [item],
            categories: [category],
            stores: [store],
            xrefs: [xref],
            scoOrders: [sco],
            purchaseRecords: [purchase]
        )

        try await db.queue.read { conn in
            XCTAssertEqual(try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM items"), 1)
            XCTAssertEqual(try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM categories"), 1)
            XCTAssertEqual(try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM stores"), 1)
            XCTAssertEqual(try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM item_store_xref"), 1)
            XCTAssertEqual(try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM store_category_order"), 1)
            XCTAssertEqual(try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM purchase_records"), 1)
        }
    }

    func testEmptyListsAreNoOp() async throws {
        let db = try StorehopDatabase.inMemoryForTests()
        let dao = PullWriteDao(writer: db.queue)

        try await dao.replaceAllForUid(
            householdId: "u1",
            items: [], categories: [], stores: [], xrefs: [], scoOrders: [], purchaseRecords: []
        )

        try await db.queue.read { conn in
            for table in ["items", "categories", "stores", "item_store_xref", "store_category_order", "purchase_records"] {
                let n = try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM \(table)") ?? -1
                XCTAssertEqual(n, 0, "Empty pull should leave \(table) empty")
            }
        }
    }

    func testFkViolationRollsBackEntireBatch() async throws {
        let db = try StorehopDatabase.inMemoryForTests()
        let dao = PullWriteDao(writer: db.queue)

        // Item references a categoryId that's NOT in the categories list.
        // FK constraint fires at COMMIT, rolling back everything.
        let item = TestFixtures.item(id: "i1", categoryId: "c_missing")
        let store = TestFixtures.store(id: "s1")

        do {
            try await dao.replaceAllForUid(
                householdId: "u1",
                items: [item],
                categories: [],
                stores: [store],
                xrefs: [],
                scoOrders: [],
                purchaseRecords: []
            )
            XCTFail("Expected FK violation to throw")
        } catch {
            // Expected — FK violation.
        }

        try await db.queue.read { conn in
            XCTAssertEqual(try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM items"), 0,
                           "Item must NOT have landed when categories were missing")
            XCTAssertEqual(try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM stores"), 0,
                           "Store must also be rolled back, not partial-committed")
        }
    }

    func testIdempotentOnRePull() async throws {
        let db = try StorehopDatabase.inMemoryForTests()
        let dao = PullWriteDao(writer: db.queue)

        let category = TestFixtures.category(id: "c1")
        let store = TestFixtures.store(id: "s1", name: "Lidl")
        let item = TestFixtures.item(id: "i1", categoryId: "c1")

        // Pull twice — same data.
        try await dao.replaceAllForUid(householdId: "u1", items: [item], categories: [category], stores: [store], xrefs: [], scoOrders: [], purchaseRecords: [])
        try await dao.replaceAllForUid(householdId: "u1", items: [item], categories: [category], stores: [store], xrefs: [], scoOrders: [], purchaseRecords: [])

        try await db.queue.read { conn in
            XCTAssertEqual(try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM items"), 1)
            XCTAssertEqual(try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM categories"), 1)
            XCTAssertEqual(try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM stores"), 1)
        }
    }

    // MARK: - v0.8.0.4: pull-guard for pendingSync = 1 rows
    //
    // Mike reported the same bug on Android: unchecking a store on the
    // Items edit screen reverted because a pull landed before the push
    // shipped his soft-delete. PullWriteDao now filters cloud rows
    // whose primary key matches a local pendingSync = 1 row, preserving
    // the user's most-recent intent through a pull-before-push race.

    func testPendingSoftDeletedXrefIsPreservedWhenCloudSendsAliveCopy() async throws {
        let db = try StorehopDatabase.inMemoryForTests()
        let dao = PullWriteDao(writer: db.queue)
        let xrefDao = ItemStoreXrefDao(writer: db.queue)

        // Seed parents so FK constraints pass.
        try db.seed(
            items: [TestFixtures.item(id: "tp", userId: "u1")],
            stores: [TestFixtures.store(id: "aldi", userId: "u1")]
        )
        // Insert the xref alive, then "uncheck" it → soft-delete with
        // pendingSync = 1.
        try await xrefDao.setStoresForItem(
            itemId: "tp", storeIds: ["aldi"], householdId: "u1", userId: "u1", now: 100
        )
        try await xrefDao.setStoresForItem(
            itemId: "tp", storeIds: [], householdId: "u1", userId: "u1", now: 200
        )

        // Cloud row replays still-alive (push hadn't fired yet).
        let cloudXref = TestFixtures.xref(itemId: "tp", storeId: "aldi", userId: "u1")
        try await dao.replaceAllForUid(
            householdId: "u1",
            items: [], categories: [], stores: [],
            xrefs: [cloudXref],
            scoOrders: [], purchaseRecords: []
        )

        // Local row UNCHANGED — soft-delete preserved, pendingSync still set.
        try await db.queue.read { conn in
            let row = try Row.fetchOne(
                conn,
                sql: "SELECT deletedAt, pendingSync FROM item_store_xref WHERE itemId = 'tp' AND storeId = 'aldi'"
            )
            XCTAssertNotNil(row, "Soft-deleted xref row should still exist (tombstones aren't hard-deleted)")
            XCTAssertEqual(row?["deletedAt"] as Int64?, 200, "deletedAt must survive the pull")
            XCTAssertEqual(row?["pendingSync"] as Int?, 1, "pendingSync must stay set so the push still runs")
        }
    }

    func testPendingXrefIsPreservedWhileUnrelatedCloudXrefUpserts() async throws {
        let db = try StorehopDatabase.inMemoryForTests()
        let dao = PullWriteDao(writer: db.queue)
        let xrefDao = ItemStoreXrefDao(writer: db.queue)

        try db.seed(
            items: [TestFixtures.item(id: "tp", userId: "u1")],
            stores: [
                TestFixtures.store(id: "aldi", userId: "u1"),
                TestFixtures.store(id: "lidl", userId: "u1"),
            ]
        )
        // Pending soft-delete on Aldi.
        try await xrefDao.setStoresForItem(
            itemId: "tp", storeIds: ["aldi"], householdId: "u1", userId: "u1", now: 100
        )
        try await xrefDao.setStoresForItem(
            itemId: "tp", storeIds: [], householdId: "u1", userId: "u1", now: 200
        )

        try await dao.replaceAllForUid(
            householdId: "u1",
            items: [], categories: [], stores: [],
            xrefs: [
                TestFixtures.xref(itemId: "tp", storeId: "aldi", userId: "u1"),
                TestFixtures.xref(itemId: "tp", storeId: "lidl", userId: "u1"),
            ],
            scoOrders: [], purchaseRecords: []
        )

        try await db.queue.read { conn in
            let aldi = try Row.fetchOne(
                conn,
                sql: "SELECT deletedAt FROM item_store_xref WHERE itemId = 'tp' AND storeId = 'aldi'"
            )
            let lidl = try Row.fetchOne(
                conn,
                sql: "SELECT deletedAt FROM item_store_xref WHERE itemId = 'tp' AND storeId = 'lidl'"
            )
            // Aldi: pending soft-delete preserved.
            XCTAssertEqual(aldi?["deletedAt"] as Int64?, 200)
            // Lidl: cloud row landed.
            XCTAssertNil(lidl?["deletedAt"] as Int64?)
        }
    }

    func testNonPendingXrefIsOverwrittenByCloud() async throws {
        let db = try StorehopDatabase.inMemoryForTests()
        let dao = PullWriteDao(writer: db.queue)
        let xrefDao = ItemStoreXrefDao(writer: db.queue)

        try db.seed(
            items: [TestFixtures.item(id: "tp", userId: "u1")],
            stores: [TestFixtures.store(id: "aldi", userId: "u1")]
        )
        // Insert + mark pushed → pendingSync = 0.
        try await xrefDao.setStoresForItem(
            itemId: "tp", storeIds: ["aldi"], householdId: "u1", userId: "u1", now: 100
        )
        try await xrefDao.markPushed(householdId: "u1", itemId: "tp", storeId: "aldi")

        // Cloud row says the xref is now soft-deleted (Amanda deleted
        // on her device). Pull should apply since there's no local
        // pending edit to preserve.
        var cloudXref = TestFixtures.xref(itemId: "tp", storeId: "aldi", userId: "u1")
        cloudXref.deletedAt = 300
        cloudXref.updatedAt = 300
        try await dao.replaceAllForUid(
            householdId: "u1",
            items: [], categories: [], stores: [],
            xrefs: [cloudXref],
            scoOrders: [], purchaseRecords: []
        )

        try await db.queue.read { conn in
            let row = try Row.fetchOne(
                conn,
                sql: "SELECT deletedAt FROM item_store_xref WHERE itemId = 'tp' AND storeId = 'aldi'"
            )
            XCTAssertEqual(row?["deletedAt"] as Int64?, 300, "cloud's soft-delete should have landed")
        }
    }

    func testPendingItemIsPreservedWhenCloudSendsConflictingCopy() async throws {
        let db = try StorehopDatabase.inMemoryForTests()
        let dao = PullWriteDao(writer: db.queue)

        // Local item with a pending edit (renamed locally).
        var local = TestFixtures.item(id: "tp", userId: "u1", now: 200)
        local.name = "Toilet Paper (Mike-edited)"
        local.pendingSync = true
        try db.seed(items: [local])

        // Cloud sends the older name back. Pull-guard should skip.
        var cloud = TestFixtures.item(id: "tp", userId: "u1")
        cloud.name = "Toilet Paper"
        try await dao.replaceAllForUid(
            householdId: "u1",
            items: [cloud],
            categories: [], stores: [],
            xrefs: [], scoOrders: [], purchaseRecords: []
        )

        try await db.queue.read { conn in
            let row = try Row.fetchOne(
                conn,
                sql: "SELECT name, pendingSync FROM items WHERE id = 'tp'"
            )
            XCTAssertEqual(row?["name"] as String?, "Toilet Paper (Mike-edited)")
            XCTAssertEqual(row?["pendingSync"] as Int?, 1)
        }
    }

    func testRePullWithUpdatedFieldsOverwritesLocal() async throws {
        let db = try StorehopDatabase.inMemoryForTests()
        let dao = PullWriteDao(writer: db.queue)

        let v1 = TestFixtures.store(id: "s1", name: "Lidl")
        try await dao.replaceAllForUid(householdId: "u1", items: [], categories: [], stores: [v1], xrefs: [], scoOrders: [], purchaseRecords: [])

        var v2 = v1
        v2.name = "Lidl Center"
        v2.updatedAt = 2_000
        try await dao.replaceAllForUid(householdId: "u1", items: [], categories: [], stores: [v2], xrefs: [], scoOrders: [], purchaseRecords: [])

        let stored = try await db.queue.read { conn in
            try Store.fetchOne(conn, sql: "SELECT * FROM stores WHERE id = 's1'")
        }
        XCTAssertEqual(stored?.name, "Lidl Center")
        XCTAssertEqual(stored?.updatedAt, 2_000)
    }
}
