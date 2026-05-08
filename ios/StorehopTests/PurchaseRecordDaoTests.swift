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
            createdAt: 0, updatedAt: 0, deletedAt: nil, pendingSync: true
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
                createdAt: 0, updatedAt: 0, deletedAt: nil, pendingSync: true
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
            createdAt: 0, updatedAt: 0, deletedAt: nil, pendingSync: true
        ))
        try await dao.softDeleteForItem(userId: "u1", itemId: "i1", now: 5_000)
        try await dao.restoreCascadeForItem(userId: "u1", itemId: "i1", deletedAt: 5_000, now: 6_000)

        let live = try await db.queue.read { conn in
            try PurchaseRecord.fetchAll(conn, sql: "SELECT * FROM purchase_records WHERE deletedAt IS NULL")
        }
        XCTAssertEqual(live.count, 1)
        XCTAssertEqual(live.first?.id, "p1")
    }
}
