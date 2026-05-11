import XCTest
import GRDB
@testable import Storehop

/// Verifies the pull-side contract: peek correctness, parallel fetch,
/// atomic write through PullWriteDao, error mapping.
final class FirestorePullCoordinatorTests: XCTestCase {

    private struct Setup {
        let db: StorehopDatabase
        let firestore: RecordingFirestoreClient
        let coordinator: FirestorePullCoordinator
    }

    private func makeSetup() throws -> Setup {
        let db = try StorehopDatabase.inMemoryForTests()
        let firestore = RecordingFirestoreClient()
        let coordinator = FirestorePullCoordinator(
            firestore: firestore,
            pullWriteDao: PullWriteDao(writer: db.queue)
        )
        return Setup(db: db, firestore: firestore, coordinator: coordinator)
    }

    // MARK: - peek

    func testPeekReturnsTrueWhenStoresCollectionHasAnyDocument() async throws {
        let s = try makeSetup()
        try await s.firestore.stub(
            [makeStoreDto(id: "s1", userId: "uid_alice")],
            at: "users/uid_alice/stores"
        )
        let result = try await s.coordinator.peek(householdId: "uid_alice")
        XCTAssertTrue(result)
    }

    func testPeekReturnsFalseWhenStoresCollectionIsEmpty() async throws {
        let s = try makeSetup()
        let result = try await s.coordinator.peek(householdId: "uid_alice")
        XCTAssertFalse(result)
    }

    // MARK: - pullForUid

    func testPullForUidWritesAllSixCollectionsThroughPullWriteDao() async throws {
        let s = try makeSetup()
        let uid = "uid_alice"

        try await s.firestore.stub(
            [makeCategoryDto(id: "c1", userId: uid)],
            at: "users/\(uid)/categories"
        )
        try await s.firestore.stub(
            [makeStoreDto(id: "s1", userId: uid)],
            at: "users/\(uid)/stores"
        )
        try await s.firestore.stub(
            [makeItemDto(id: "i1", userId: uid, categoryId: "c1")],
            at: "users/\(uid)/items"
        )
        try await s.firestore.stub(
            [makeXrefDto(itemId: "i1", storeId: "s1", userId: uid)],
            at: "users/\(uid)/item_store_xref"
        )
        try await s.firestore.stub(
            [makeScoDto(storeId: "s1", categoryId: "c1", userId: uid)],
            at: "users/\(uid)/store_category_order"
        )
        try await s.firestore.stub(
            [makePurchaseDto(id: "p1", itemId: "i1", userId: uid)],
            at: "users/\(uid)/purchase_records"
        )

        let result = await s.coordinator.pullForHousehold(uid)
        XCTAssertEqual(result, .success)

        try await s.db.queue.read { conn in
            XCTAssertEqual(try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM categories"), 1)
            XCTAssertEqual(try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM stores"), 1)
            XCTAssertEqual(try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM items"), 1)
            XCTAssertEqual(try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM item_store_xref"), 1)
            XCTAssertEqual(try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM store_category_order"), 1)
            XCTAssertEqual(try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM purchase_records"), 1)
        }
    }

    func testPulledRowsCarryPendingSyncFalse() async throws {
        let s = try makeSetup()
        let uid = "uid_alice"
        try await s.firestore.stub(
            [makeStoreDto(id: "s1", userId: uid)],
            at: "users/\(uid)/stores"
        )

        _ = await s.coordinator.pullForHousehold(uid)

        let pending = try await s.db.queue.read { conn in
            try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM stores WHERE pendingSync = 1") ?? -1
        }
        XCTAssertEqual(pending, 0, "Pulled rows must NOT be flagged for re-push")
    }

    func testEmptyCloudPullSucceedsWithNoLocalRows() async throws {
        let s = try makeSetup()
        let result = await s.coordinator.pullForHousehold("uid_alice")
        XCTAssertEqual(result, .success)

        let storeCount = try await s.db.queue.read { conn in
            try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM stores") ?? -1
        }
        XCTAssertEqual(storeCount, 0)
    }

    func testPullFailureLeavesLocalDatabaseUntouched() async throws {
        let s = try makeSetup()
        // Pre-populate local with a row that would conflict with cloud.
        try s.db.seed(stores: [TestFixtures.store(id: "s_local", name: "Lidl", userId: "u1")])

        // Cloud has data that won't decode as StoreDto (missing required field).
        try await s.firestore.stubRaw(
            [Data("not valid json".utf8)],
            at: "users/uid_alice/stores"
        )

        let result = await s.coordinator.pullForHousehold("uid_alice")
        if case .failure = result {
            // Expected
        } else {
            XCTFail("Expected pull failure on bad JSON; got \(result)")
        }

        // Local row untouched.
        let lidl = try await s.db.queue.read { conn in
            try Store.fetchOne(conn, sql: "SELECT * FROM stores WHERE id = 's_local'")
        }
        XCTAssertNotNil(lidl)
    }

    // MARK: - Fixtures

    private func makeItemDto(id: String, userId: String, categoryId: String? = nil) -> ItemDto {
        ItemDto(
            id: id, name: "Item \(id)", categoryId: categoryId,
            notes: nil, quantity: nil, isNeeded: true, lastPurchasedAt: nil,
            userId: userId, createdAt: 1_000, updatedAt: 1_000, deletedAt: nil,
            brand: nil, imageUrl: nil, isStaple: false, isPriority: false
        )
    }

    private func makeCategoryDto(id: String, userId: String) -> CategoryDto {
        CategoryDto(
            id: id, name: "Cat \(id)", nameKey: nil, icon: nil,
            isArchived: false, isSeeded: false, userId: userId,
            createdAt: 1_000, updatedAt: 1_000, deletedAt: nil
        )
    }

    private func makeStoreDto(id: String, userId: String) -> StoreDto {
        StoreDto(
            id: id, name: "Store \(id)", colorArgb: nil,
            isArchived: false, isSeeded: false, userId: userId,
            createdAt: 1_000, updatedAt: 1_000, deletedAt: nil, displayOrder: 0
        )
    }

    private func makeXrefDto(itemId: String, storeId: String, userId: String) -> ItemStoreXrefDto {
        ItemStoreXrefDto(
            itemId: itemId, storeId: storeId, userId: userId,
            createdAt: 1_000, updatedAt: 1_000, deletedAt: nil,
            isNeeded: true, lastPurchasedAt: nil
        )
    }

    private func makeScoDto(storeId: String, categoryId: String, userId: String) -> StoreCategoryOrderDto {
        StoreCategoryOrderDto(
            storeId: storeId, categoryId: categoryId, displayOrder: 0,
            isSeeded: false, userId: userId,
            createdAt: 1_000, updatedAt: 1_000, deletedAt: nil
        )
    }

    private func makePurchaseDto(id: String, itemId: String, userId: String) -> PurchaseRecordDto {
        PurchaseRecordDto(
            id: id, itemId: itemId, storeId: nil, purchasedAt: 1_500,
            userId: userId, createdAt: 1_500, updatedAt: 1_500, deletedAt: nil
        )
    }
}

// `stubRaw` lives on RecordingFirestoreClient itself (see SyncEngineTests.swift).
