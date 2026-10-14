import XCTest
import GRDB
@testable import Storehop

final class PurchaseRecordDaoTests: XCTestCase {

    private func setup() throws -> (StorehopDatabase, PurchaseRecordDao) {
        let db = try StorehopDatabase.inMemoryForTests()
        try db.seed(
            items: [TestFixtures.item(id: "i1", name: "Mozzarella")]
        )
        return (db, PurchaseRecordDao(writer: db.queue))
    }

    func testInsertAbortsOnDuplicatePrimaryKey() async throws {
        let (_, dao) = try setup()
        let record = PurchaseRecord(
            id: "p1", itemId: "i1", storeId: nil,
            purchasedAt: 1_000, userId: "u1",
            createdAt: 0, updatedAt: 0, deletedAt: nil, pendingSync: true,
            householdId: "u1"
        )
        try await dao.insert(record)
        do {
            try await dao.insert(record)
            XCTFail("Expected duplicate-PK insert to throw — UUIDs don't collide normally")
        } catch {
            // Expected.
        }
    }

    func testSoftDeleteForItemAtTimeOnlyDeletesMatchingTimestamp() async throws {
        let (db, dao) = try setup()
        for (id, ts) in [("p1", Int64(1_000)), ("p2", Int64(2_000)), ("p3", Int64(3_000))] {
            try await dao.insert(PurchaseRecord(
                id: id, itemId: "i1", storeId: nil,
                purchasedAt: ts, userId: "u1",
                createdAt: 0, updatedAt: 0, deletedAt: nil, pendingSync: true,
                householdId: "u1"
            ))
        }

        try await dao.softDeleteForItemAtTime(userId: "u1", itemId: "i1", purchasedAt: 2_000, now: 9_000)

        let live = try await db.queue.read { conn in
            try PurchaseRecord.fetchAll(conn, sql: """
                SELECT * FROM purchase_records
                WHERE itemId = ? AND deletedAt IS NULL
                ORDER BY purchasedAt
                """, arguments: ["i1"])
        }
        XCTAssertEqual(live.map(\.id), ["p1", "p3"])
    }

    func testRestoreCascadeForItemFiltersByExactDeletedAt() async throws {
        let (db, dao) = try setup()
        try await dao.insert(PurchaseRecord(
            id: "p1", itemId: "i1", storeId: nil,
            purchasedAt: 1_000, userId: "u1",
            createdAt: 0, updatedAt: 0, deletedAt: nil, pendingSync: true,
            householdId: "u1"
        ))
        try await dao.softDeleteForItem(householdId: "u1", itemId: "i1", now: 5_000)
        try await dao.restoreCascadeForItem(householdId: "u1", itemId: "i1", deletedAt: 5_000, now: 6_000)

        let live = try await db.queue.read { conn in
            try PurchaseRecord.fetchAll(conn, sql: "SELECT * FROM purchase_records WHERE deletedAt IS NULL")
        }
        XCTAssertEqual(live.count, 1)
        XCTAssertEqual(live.first?.id, "p1")
    }
}

@MainActor
final class StatisticsViewModelTests: XCTestCase {

    private struct Setup {
        let db: StorehopDatabase
        let viewModel: StatisticsViewModel
    }

    private func makeSetup(
        uid: String = "u1",
        householdId: String? = nil,
        stores: [Store] = [],
        categories: [Storehop.Category] = [],
        items: [Item] = []
    ) async throws -> Setup {
        let hid = householdId ?? uid
        let db = try StorehopDatabase.inMemoryForTests()
        try db.seed(
            items: items,
            stores: stores,
            categories: categories
        )
        let session = LocalOnlyUserSessionProvider(uid: uid)
        let householdSession = LocalOnlyHouseholdSessionProvider(initialHouseholdId: hid)
        let clock = MutableClock(nowMs: 1_000_000_000)
        let writer = db.queue

        let storeDao = StoreDao(writer: writer)
        let xrefDao = ItemStoreXrefDao(writer: writer)
        let scoDao = StoreCategoryOrderDao(writer: writer)
        let categoryDao = CategoryDao(writer: writer)
        let itemDao = ItemDao(writer: writer)
        let purchaseDao = PurchaseRecordDao(writer: writer)

        let purchases = PurchaseHistoryRepository(
            purchaseDao: purchaseDao,
            session: session,
            clock: clock
        )
        let storeRepo = StoreRepository(
            writer: writer,
            storeDao: storeDao,
            xrefDao: xrefDao,
            scoDao: scoDao,
            session: session,
            householdSession: householdSession,
            clock: clock,
            ids: SequenceIdGenerator()
        )
        let categoryRepo = CategoryRepository(
            writer: writer,
            categoryDao: categoryDao,
            itemDao: itemDao,
            scoDao: scoDao,
            session: session,
            householdSession: householdSession,
            clock: clock,
            ids: SequenceIdGenerator()
        )
        let itemRepo = ItemRepository(
            writer: writer,
            itemDao: itemDao,
            xrefDao: xrefDao,
            scoDao: scoDao,
            purchaseDao: purchaseDao,
            session: session,
            householdSession: householdSession,
            clock: clock,
            ids: SequenceIdGenerator()
        )

        let vm = StatisticsViewModel(
            purchases: purchases,
            itemRepository: itemRepo,
            storeRepository: storeRepo,
            categoryRepository: categoryRepo,
            session: session,
            householdSession: householdSession,
            clock: clock
        )
        vm.bind()
        try await Task.sleep(nanoseconds: 50_000_000)
        return Setup(db: db, viewModel: vm)
    }

    func testSharedHouseholdLoadsLibraryCountsFromHousehold() async throws {
        let s = try await makeSetup(
            uid: "u_member",
            householdId: "hid_shared",
            stores: [
                TestFixtures.store(id: "s1", name: "Aldi", userId: "hid_shared"),
            ],
            categories: [
                TestFixtures.category(id: "c1", name: "Dairy", userId: "hid_shared"),
            ],
            items: [
                TestFixtures.item(id: "i1", name: "Milk", categoryId: "c1", userId: "hid_shared", isStaple: true, isPriority: true),
                TestFixtures.item(id: "i2", name: "Cheese", categoryId: "c1", userId: "hid_shared", isStaple: false, isPriority: false),
            ]
        )

        try await waitForCondition {
            if case .ready(let data) = s.viewModel.state {
                return data.totalItems == 2
            }
            return false
        }

        guard case .ready(let data) = s.viewModel.state else {
            XCTFail("Expected .ready state")
            return
        }

        XCTAssertEqual(data.totalItems, 2)
        XCTAssertEqual(data.stapleItems, 1)
        XCTAssertEqual(data.priorityItems, 1)
        XCTAssertEqual(data.totalStores, 1)
        XCTAssertEqual(data.totalCategories, 1)
    }
}

