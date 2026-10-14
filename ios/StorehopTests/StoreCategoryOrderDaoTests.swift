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
        try await dao.appendIfMissing(storeId: "s1", categoryId: "c3", householdId: "u1", userId: "u1", now: 1_000)

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

@MainActor
final class EditAisleOrderViewModelTests: XCTestCase {

    private struct Setup {
        let db: StorehopDatabase
        let viewModel: EditAisleOrderViewModel
        let scoRepository: StoreCategoryOrderRepository
    }

    private func makeSetup(
        uid: String = "u1",
        householdId: String? = nil,
        storeId: String = "s_aldi"
    ) async throws -> Setup {
        let hid = householdId ?? uid
        let db = try StorehopDatabase.inMemoryForTests()
        try db.seed(
            stores: [
                TestFixtures.store(id: storeId, name: "Aldi", userId: hid),
            ],
            categories: [
                TestFixtures.category(id: "c_produce", name: "Produce", userId: hid),
                TestFixtures.category(id: "c_dairy", name: "Dairy", userId: hid),
            ],
            scoOrders: [
                TestFixtures.sco(storeId: storeId, categoryId: "c_dairy", displayOrder: 0),
                TestFixtures.sco(storeId: storeId, categoryId: "c_produce", displayOrder: 1),
            ]
        )
        let session = LocalOnlyUserSessionProvider(uid: uid)
        let householdSession = LocalOnlyHouseholdSessionProvider(initialHouseholdId: hid)
        let clock = MutableClock(nowMs: 1_000)
        let writer = db.queue

        let storeDao = StoreDao(writer: writer)
        let xrefDao = ItemStoreXrefDao(writer: writer)
        let scoDao = StoreCategoryOrderDao(writer: writer)
        let categoryDao = CategoryDao(writer: writer)
        let itemDao = ItemDao(writer: writer)

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
        let scoRepo = StoreCategoryOrderRepository(
            scoDao: scoDao,
            session: session,
            householdSession: householdSession,
            clock: clock
        )

        let vm = EditAisleOrderViewModel(
            storeId: storeId,
            scoRepository: scoRepo,
            storeRepository: storeRepo,
            categoryRepository: categoryRepo,
            session: session,
            householdSession: householdSession
        )
        vm.bind()
        try await Task.sleep(nanoseconds: 50_000_000)
        return Setup(db: db, viewModel: vm, scoRepository: scoRepo)
    }

    func testSharedHouseholdLoadsStoreAndOrderedCategories() async throws {
        let s = try await makeSetup(uid: "u_member", householdId: "hid_shared")
        try await waitForCondition {
            s.viewModel.store != nil && s.viewModel.orderedCategories.count == 2
        }
        XCTAssertEqual(s.viewModel.store?.name, "Aldi")
        XCTAssertEqual(s.viewModel.orderedCategories.map(\.id), ["c_dairy", "c_produce"])
    }

    func testCommitOrderUpdatesDisplayOrder() async throws {
        let s = try await makeSetup()
        try await waitForCondition { s.viewModel.orderedCategories.count == 2 }

        s.viewModel.commitOrder(["c_produce", "c_dairy"])
        try await waitForCondition {
            s.viewModel.orderedCategories.map(\.id) == ["c_produce", "c_dairy"]
        }
    }
}

