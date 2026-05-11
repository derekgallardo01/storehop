import XCTest
import GRDB
@testable import Storehop

final class StoreCategoryOrderDaoTests: XCTestCase {

    private func setup() throws -> (StorehopDatabase, StoreCategoryOrderDao) {
        let db = try StorehopDatabase.inMemoryForTests()
        try db.seed(
            stores: [TestFixtures.store(id: "s1")],
            categories: [
                TestFixtures.category(id: "c1", name: "Produce"),
                TestFixtures.category(id: "c2", name: "Bakery"),
                TestFixtures.category(id: "c3", name: "User Custom"),
            ]
        )
        return (db, StoreCategoryOrderDao(writer: db.queue))
    }

    func testAppendIfMissingCreatesRowAtNextDisplayOrder() async throws {
        let (_, dao) = try setup()
        try await dao.appendIfMissing(storeId: "s1", categoryId: "c1", householdId: "u1", userId: "u1", now: 1_000)
        try await dao.appendIfMissing(storeId: "s1", categoryId: "c2", householdId: "u1", userId: "u1", now: 1_000)
        try await dao.appendIfMissing(storeId: "s1", categoryId: "c3", userId: "u1", now: 1_000)

        let rows = try await dao.findForStore(storeId: "s1").sorted { $0.displayOrder < $1.displayOrder }
        XCTAssertEqual(rows.map(\.categoryId), ["c1", "c2", "c3"])
        XCTAssertEqual(rows.map(\.displayOrder), [0, 1, 2])
    }

    func testAppendIfMissingIsIdempotentForExistingLiveRow() async throws {
        let (_, dao) = try setup()
        try await dao.appendIfMissing(storeId: "s1", categoryId: "c1", householdId: "u1", userId: "u1", now: 1_000)
        try await dao.appendIfMissing(storeId: "s1", categoryId: "c1", householdId: "u1", userId: "u1", now: 2_000)
        try await dao.appendIfMissing(storeId: "s1", categoryId: "c1", householdId: "u1", userId: "u1", now: 3_000)

        let rows = try await dao.findForStore(storeId: "s1")
        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(rows.first?.displayOrder, 0)
        XCTAssertEqual(rows.first?.createdAt, 1_000, "Original createdAt preserved")
    }

    func testAppendIfMissingResurrectsTombstonedRowAtTheBottom() async throws {
        let (_, dao) = try setup()
        try await dao.appendIfMissing(storeId: "s1", categoryId: "c1", householdId: "u1", userId: "u1", now: 1_000)
        try await dao.appendIfMissing(storeId: "s1", categoryId: "c2", householdId: "u1", userId: "u1", now: 1_000)
        try await dao.softDelete(storeId: "s1", categoryId: "c1", now: 2_000)

        try await dao.appendIfMissing(storeId: "s1", categoryId: "c1", householdId: "u1", userId: "u1", now: 3_000)

        let live = try await dao.findForStore(storeId: "s1").sorted { $0.displayOrder < $1.displayOrder }
        XCTAssertEqual(live.count, 2)
        // c2 should be at displayOrder 1 (kept), c1 revived at 2 (bottom).
        XCTAssertEqual(live.map(\.categoryId), ["c2", "c1"])
        XCTAssertEqual(live.last?.displayOrder, 2)
    }

    func testReplaceAllForStoreTombstonesMissingAndUpsertsIncoming() async throws {
        let (_, dao) = try setup()
        try await dao.appendIfMissing(storeId: "s1", categoryId: "c1", householdId: "u1", userId: "u1", now: 1_000)
        try await dao.appendIfMissing(storeId: "s1", categoryId: "c2", householdId: "u1", userId: "u1", now: 1_000)

        let newOrder: [StoreCategoryOrder] = [
            TestFixtures.sco(storeId: "s1", categoryId: "c2", displayOrder: 0, now: 2_000),
            TestFixtures.sco(storeId: "s1", categoryId: "c3", displayOrder: 1, now: 2_000),
        ]
        try await dao.replaceAllForStore(storeId: "s1", ordered: newOrder, now: 2_000)

        let live = try await dao.findForStore(storeId: "s1").sorted { $0.displayOrder < $1.displayOrder }
        XCTAssertEqual(live.map(\.categoryId), ["c2", "c3"], "c1 should be tombstoned, c3 inserted")
    }
}
