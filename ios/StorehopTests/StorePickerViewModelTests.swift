import XCTest
import GRDB
@testable import Storehop

/// Covers the Store Picker's cross-store banner assembly — v0.9.1 "Buy
/// Today!" plus its contrast with the critical banner's one-off filter.
/// Mirrors Android's `StorePickerViewModelTest` buy-today cases.
@MainActor
final class StorePickerViewModelTests: XCTestCase {

    private struct Setup {
        let db: StorehopDatabase
        let itemRepository: ItemRepository
        let viewModel: StorePickerViewModel
    }

    private func makeSetup(
        uid: String = "u1",
        stores: [Store]
    ) async throws -> Setup {
        let db = try StorehopDatabase.inMemoryForTests()
        try db.seed(stores: stores)
        let session = LocalOnlyUserSessionProvider(uid: uid)
        let householdSession = LocalOnlyHouseholdSessionProvider(initialHouseholdId: uid)
        let clock = MutableClock(nowMs: 1_000)
        let writer = db.queue

        let storeDao = StoreDao(writer: writer)
        let xrefDao = ItemStoreXrefDao(writer: writer)
        let scoDao = StoreCategoryOrderDao(writer: writer)
        let itemRepository = ItemRepository(
            writer: writer, itemDao: ItemDao(writer: writer), xrefDao: xrefDao,
            scoDao: scoDao, purchaseDao: PurchaseRecordDao(writer: writer),
            session: session, householdSession: householdSession,
            clock: clock, ids: SequenceIdGenerator()
        )
        let storeRepository = StoreRepository(
            writer: writer, storeDao: storeDao, xrefDao: xrefDao, scoDao: scoDao,
            session: session, householdSession: householdSession,
            clock: clock, ids: SequenceIdGenerator()
        )
        let shoppingRepository = ShoppingRepository(
            shoppingDao: ShoppingDao(writer: writer),
            storeDao: storeDao,
            session: session
        )

        let vm = StorePickerViewModel(
            storeRepository: storeRepository,
            shoppingRepository: shoppingRepository,
            session: session,
            sessionTracker: ShoppingSessionTracker(clock: clock)
        )
        vm.bind()
        try await Task.sleep(nanoseconds: 50_000_000)
        return Setup(db: db, itemRepository: itemRepository, viewModel: vm)
    }

    private func addItem(
        _ s: Setup,
        name: String,
        storeIds: Set<String>,
        isPriority: Bool = false,
        isBuyToday: Bool = false
    ) async throws {
        _ = try await s.itemRepository.addItem(
            name: name, categoryId: nil, storeIds: storeIds,
            quantity: nil, notes: nil, brand: nil, imageUrl: nil,
            isStaple: false, isPriority: isPriority, isBuyToday: isBuyToday
        )
    }

    // MARK: - v0.9.1 Buy Today banner

    func testBuyTodayBannerStateIsNilWhenNothingIsFlagged() async throws {
        let s = try await makeSetup(stores: [
            TestFixtures.store(id: "s_lidl", name: "Lidl"),
        ])
        // A critical item alone must not light up the Buy Today banner.
        try await addItem(s, name: "Milk", storeIds: ["s_lidl"], isPriority: true)

        try await waitForCondition { s.viewModel.criticalBannerState != nil }
        XCTAssertNil(s.viewModel.buyTodayBannerState,
                     "No buy-today flags anywhere -> banner hidden")
    }

    func testBuyTodayBannerStateAggregatesFlaggedItemsAndPicksTopStore() async throws {
        let s = try await makeSetup(stores: [
            TestFixtures.store(id: "s_aldi", name: "Aldi", displayOrder: 0),
            TestFixtures.store(id: "s_lidl", name: "Lidl", displayOrder: 1),
        ])
        try await addItem(s, name: "Advil", storeIds: ["s_aldi"], isBuyToday: true)
        try await addItem(s, name: "Dog food", storeIds: ["s_lidl"], isBuyToday: true)
        try await addItem(s, name: "Batteries", storeIds: ["s_lidl"], isBuyToday: true)

        try await waitForCondition { s.viewModel.buyTodayBannerState?.totalCount == 3 }
        let state = s.viewModel.buyTodayBannerState!
        XCTAssertEqual(state.topStoreName, "Lidl")
        XCTAssertEqual(state.topStoreCount, 2)
        XCTAssertFalse(state.singleStore)
    }

    func testBuyTodayBannerStateIncludesOneOffStoresUnlikeCriticalBanner() async throws {
        // A one-off pet store with a "buy today" item must still surface here,
        // even though the critical banner deliberately skips one-off stores.
        let s = try await makeSetup(stores: [
            TestFixtures.store(id: "s_pet", name: "Pet Store", isOneOff: true),
        ])
        try await addItem(s, name: "Dog food", storeIds: ["s_pet"], isPriority: true, isBuyToday: true)

        try await waitForCondition { s.viewModel.buyTodayBannerState?.totalCount == 1 }
        XCTAssertNil(s.viewModel.criticalBannerState,
                     "Critical banner excludes the one-off store -> nil")
    }

    // MARK: - critical banner "All at" (parity catch-up with Android's
    // critical_banner_all_at — the subtitle shows in the single-store
    // case too, naming the store)

    func testCriticalBannerStateExposesSingleStoreNameForAllAtSubtitle() async throws {
        let s = try await makeSetup(stores: [
            TestFixtures.store(id: "s_lidl", name: "Lidl", displayOrder: 0),
            TestFixtures.store(id: "s_aldi", name: "Aldi", displayOrder: 1),
        ])
        try await addItem(s, name: "Coffee", storeIds: ["s_lidl"], isPriority: true)

        try await waitForCondition { s.viewModel.criticalBannerState?.totalCount == 1 }
        let state = s.viewModel.criticalBannerState!
        XCTAssertTrue(state.singleStore)
        XCTAssertEqual(state.topStoreName, "Lidl")
    }

    func testBuyTodayBannerStateExposesSingleStoreNameForAllAtSubtitle() async throws {
        // When everything due today is at one store, the banner renders the
        // "All at <store>" routing line — singleStore + topStoreName drive it.
        let s = try await makeSetup(stores: [
            TestFixtures.store(id: "s_lidl", name: "Lidl", displayOrder: 0),
            TestFixtures.store(id: "s_aldi", name: "Aldi", displayOrder: 1),
        ])
        try await addItem(s, name: "Advil", storeIds: ["s_lidl"], isBuyToday: true)
        try await addItem(s, name: "Gum", storeIds: ["s_lidl"], isBuyToday: true)

        try await waitForCondition { s.viewModel.buyTodayBannerState?.totalCount == 2 }
        let state = s.viewModel.buyTodayBannerState!
        XCTAssertTrue(state.singleStore)
        XCTAssertEqual(state.topStoreName, "Lidl")
    }
}
