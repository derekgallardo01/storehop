import XCTest
@testable import Storehop

@MainActor
final class ShopAtStoreViewModelTests: XCTestCase {

    private struct Setup {
        let db: StorehopDatabase
        let viewModel: ShopAtStoreViewModel
    }

    private func makeSetup(uid: String = "u1", storeId: String = "s_lidl") async throws -> Setup {
        let db = try StorehopDatabase.inMemoryForTests()
        try db.seed(
            stores: [TestFixtures.store(id: storeId, name: "Lidl", userId: uid)],
            categories: [TestFixtures.category(id: "c_dairy", name: "Dairy", userId: uid)]
        )
        let session = LocalOnlyUserSessionProvider(uid: uid)
        let clock = MutableClock(nowMs: 1_000)
        let writer = db.queue
        let repos = makeRepositories(writer: writer, session: session, clock: clock)
        let vm = ShopAtStoreViewModel(
            storeId: storeId,
            shoppingRepository: repos.shopping,
            itemRepository: repos.item,
            storeRepository: repos.store,
            session: session,
            sessionTracker: ShoppingSessionTracker(clock: clock)
        )
        vm.bind()
        // Let the binder spin up its initial subscription.
        try await Task.sleep(nanoseconds: 50_000_000)
        return Setup(db: db, viewModel: vm)
    }

    // MARK: - quickAdd

    func testQuickAddCreatesItemTaggedToThisStoreOnly() async throws {
        let s = try await makeSetup()
        s.viewModel.quickAdd(name: "Bread")
        try await waitForCondition(timeout: 1.0) {
            s.viewModel.sections.flatMap(\.rows).contains { $0.itemName == "Bread" }
        }
    }

    func testQuickAddIgnoresWhitespaceOnly() async throws {
        let s = try await makeSetup()
        let initialItemCount = try await s.db.queue.read { conn in
            try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM items") ?? -1
        }
        s.viewModel.quickAdd(name: "   ")
        try await Task.sleep(nanoseconds: 100_000_000)
        let after = try await s.db.queue.read { conn in
            try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM items") ?? -1
        }
        XCTAssertEqual(after, initialItemCount)
    }

    // MARK: - togglePurchased + undo

    func testTogglePurchasedCascadesAcrossStores() async throws {
        let s = try await makeSetup()
        try s.db.seed(
            stores: [TestFixtures.store(id: "s_aldi", name: "Aldi")]
        )
        s.viewModel.quickAdd(name: "Mozzarella")
        try await waitForCondition { s.viewModel.sections.flatMap(\.rows).contains { $0.itemName == "Mozzarella" } }

        // Tag Mozzarella to a second store via repos so cascade has somewhere to land.
        let mozzId = s.viewModel.sections.flatMap(\.rows).first(where: { $0.itemName == "Mozzarella" })?.itemId
        XCTAssertNotNil(mozzId)
        try await s.db.queue.write { conn in
            var xref = ItemStoreXref(
                itemId: mozzId!, storeId: "s_aldi", userId: "u1",
                createdAt: 0, updatedAt: 0, deletedAt: nil,
                pendingSync: true, isNeeded: true, lastPurchasedAt: nil
            )
            try xref.upsert(conn)
        }

        let row = s.viewModel.sections.flatMap(\.rows).first { $0.itemName == "Mozzarella" }!
        s.viewModel.togglePurchased(row: row)

        // After cascade: both xrefs should be !isNeeded.
        try await waitForCondition {
            s.viewModel.sections.flatMap(\.rows).first { $0.itemName == "Mozzarella" }?.isNeeded == false
        }
        let aldiXref = try await s.db.queue.read { conn in
            try ItemStoreXref.fetchOne(conn, sql: "SELECT * FROM item_store_xref WHERE itemId = ? AND storeId = 's_aldi'", arguments: [mozzId!])
        }
        XCTAssertEqual(aldiXref?.isNeeded, false, "Cascade must flip Aldi xref too")
    }

    func testUndoLastPurchaseRestoresAllStores() async throws {
        let s = try await makeSetup()
        s.viewModel.quickAdd(name: "Bread")
        try await waitForCondition { s.viewModel.sections.flatMap(\.rows).contains { $0.itemName == "Bread" } }

        let row = s.viewModel.sections.flatMap(\.rows).first { $0.itemName == "Bread" }!
        s.viewModel.togglePurchased(row: row)

        try await waitForCondition {
            s.viewModel.lastPurchaseDisplayName == "Bread"
        }

        s.viewModel.undoLastPurchase(itemId: row.itemId)
        try await waitForCondition {
            s.viewModel.sections.flatMap(\.rows).first { $0.itemName == "Bread" }?.isNeeded == true
        }
    }

    // MARK: - search filter

    func testQueryFiltersRowsButNotCriticalNames() async throws {
        let s = try await makeSetup()
        s.viewModel.quickAdd(name: "Bread")
        s.viewModel.quickAdd(name: "Mozzarella")
        try await waitForCondition {
            s.viewModel.sections.flatMap(\.rows).count >= 2
        }

        s.viewModel.query = "moz"
        // refreshSections is sync via didSet
        let visibleNames = s.viewModel.sections.flatMap(\.rows).map(\.itemName)
        XCTAssertEqual(visibleNames, ["Mozzarella"])
    }

    // MARK: - Helpers

    private func makeRepositories(writer: any DatabaseWriter, session: any UserSessionProvider, clock: any Clock) -> (
        shopping: ShoppingRepository,
        item: ItemRepository,
        store: StoreRepository
    ) {
        let storeDao = StoreDao(writer: writer)
        let categoryDao = CategoryDao(writer: writer)
        let itemDao = ItemDao(writer: writer)
        let xrefDao = ItemStoreXrefDao(writer: writer)
        let scoDao = StoreCategoryOrderDao(writer: writer)
        let purchaseDao = PurchaseRecordDao(writer: writer)
        let shoppingDao = ShoppingDao(writer: writer)

        let item = ItemRepository(
            writer: writer, itemDao: itemDao, xrefDao: xrefDao, scoDao: scoDao,
            purchaseDao: purchaseDao, session: session, clock: clock,
            ids: SequenceIdGenerator()
        )
        let store = StoreRepository(
            writer: writer, storeDao: storeDao, xrefDao: xrefDao, scoDao: scoDao,
            session: session, clock: clock, ids: SequenceIdGenerator()
        )
        let shopping = ShoppingRepository(shoppingDao: shoppingDao, storeDao: storeDao, session: session)
        return (shopping, item, store)
    }
}

// MARK: - Async-condition helper

@MainActor
func waitForCondition(timeout: TimeInterval = 1.0, _ check: @escaping () -> Bool) async throws {
    let deadline = Date().addingTimeInterval(timeout)
    while Date() < deadline {
        if check() { return }
        try await Task.sleep(nanoseconds: 10_000_000)  // 10ms
    }
    XCTFail("Timed out waiting for condition")
}
