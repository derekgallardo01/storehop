import XCTest
import GRDB
@testable import Storehop

final class StoreDaoTests: XCTestCase {

    private func setup() throws -> (StorehopDatabase, StoreDao) {
        let db = try StorehopDatabase.inMemoryForTests()
        return (db, StoreDao(writer: db.queue))
    }

    func testNextDisplayOrderReturnsZeroOnEmptyTable() async throws {
        let (_, dao) = try setup()
        let order = try await dao.nextDisplayOrder(userId: "u1")
        XCTAssertEqual(order, 0)
    }

    func testNextDisplayOrderReturnsMaxPlusOne() async throws {
        let (db, dao) = try setup()
        try db.seed(stores: [
            TestFixtures.store(id: "s1", displayOrder: 3),
            TestFixtures.store(id: "s2", name: "Aldi", displayOrder: 7),
        ])
        let order = try await dao.nextDisplayOrder(userId: "u1")
        XCTAssertEqual(order, 8)
    }

    func testFindAnyByNameSurfacesTombstonedRowsForResurrection() async throws {
        let (db, dao) = try setup()
        try db.seed(stores: [TestFixtures.store(id: "s1", name: "Lidl")])
        try await dao.softDelete(userId: "u1", id: "s1", now: 1_000)

        let live = try await dao.findByName(userId: "u1", name: "Lidl")
        XCTAssertNil(live, "findByName must filter out tombstones")
        let any = try await dao.findAnyByName(userId: "u1", name: "Lidl")
        XCTAssertNotNil(any, "findAnyByName must surface the tombstoned row for revival")
        XCTAssertNotNil(any?.deletedAt)
    }

    func testRestoreFromTombstoneClearsDeletedAtAndFlagsPendingSync() async throws {
        let (db, dao) = try setup()
        try db.seed(stores: [TestFixtures.store(id: "s1")])
        try await dao.softDelete(userId: "u1", id: "s1", now: 1_000)
        try await dao.restoreFromTombstone(userId: "u1", id: "s1", now: 2_000)

        let store = try await dao.findById(userId: "u1", id: "s1")
        XCTAssertNotNil(store)
        XCTAssertNil(store?.deletedAt)
        XCTAssertTrue(store?.pendingSync ?? false)
        XCTAssertEqual(store?.updatedAt, 2_000)
    }

    func testObserveAllExcludesArchivedByDefault() async throws {
        let (db, _) = try setup()
        try db.seed(stores: [
            TestFixtures.store(id: "s1", name: "Lidl", isArchived: false),
            TestFixtures.store(id: "s2", name: "Closed", isArchived: true),
        ])
        let visible = try await db.queue.read { conn in
            try Store.fetchAll(conn, sql: """
                SELECT * FROM stores
                WHERE userId = ? AND deletedAt IS NULL AND (? = 1 OR isArchived = 0)
                ORDER BY displayOrder, name COLLATE NOCASE
                """, arguments: ["u1", 0])
        }
        XCTAssertEqual(visible.map(\.id), ["s1"])
    }

    func testObserveAllIncludesArchivedWhenRequested() async throws {
        let (db, _) = try setup()
        try db.seed(stores: [
            TestFixtures.store(id: "s1", name: "Lidl", isArchived: false),
            TestFixtures.store(id: "s2", name: "Closed", isArchived: true),
        ])
        let allVisible = try await db.queue.read { conn in
            try Store.fetchAll(conn, sql: """
                SELECT * FROM stores
                WHERE userId = ? AND deletedAt IS NULL AND (? = 1 OR isArchived = 0)
                ORDER BY displayOrder, name COLLATE NOCASE
                """, arguments: ["u1", 1])
        }
        XCTAssertEqual(allVisible.count, 2)
    }
}
