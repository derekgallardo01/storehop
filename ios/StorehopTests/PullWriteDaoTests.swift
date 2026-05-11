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
        try await dao.replaceAllForUid(items: [item], categories: [category], stores: [store], xrefs: [], scoOrders: [], purchaseRecords: [])
        try await dao.replaceAllForUid(items: [item], categories: [category], stores: [store], xrefs: [], scoOrders: [], purchaseRecords: [])

        try await db.queue.read { conn in
            XCTAssertEqual(try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM items"), 1)
            XCTAssertEqual(try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM categories"), 1)
            XCTAssertEqual(try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM stores"), 1)
        }
    }

    func testRePullWithUpdatedFieldsOverwritesLocal() async throws {
        let db = try StorehopDatabase.inMemoryForTests()
        let dao = PullWriteDao(writer: db.queue)

        let v1 = TestFixtures.store(id: "s1", name: "Lidl")
        try await dao.replaceAllForUid(items: [], categories: [], stores: [v1], xrefs: [], scoOrders: [], purchaseRecords: [])

        var v2 = v1
        v2.name = "Lidl Center"
        v2.updatedAt = 2_000
        try await dao.replaceAllForUid(items: [], categories: [], stores: [v2], xrefs: [], scoOrders: [], purchaseRecords: [])

        let stored = try await db.queue.read { conn in
            try Store.fetchOne(conn, sql: "SELECT * FROM stores WHERE id = 's1'")
        }
        XCTAssertEqual(stored?.name, "Lidl Center")
        XCTAssertEqual(stored?.updatedAt, 2_000)
    }
}
