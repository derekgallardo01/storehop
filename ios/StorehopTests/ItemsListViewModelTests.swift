import XCTest
import GRDB
@testable import Storehop

@MainActor
final class ItemsListViewModelTests: XCTestCase {

    private struct Setup {
        let db: StorehopDatabase
        let prefs: any UserPreferencesRepository
        let itemRepository: ItemRepository
        let viewModel: ItemsListViewModel
    }

    private func makeSetup(
        uid: String = "u1",
        showOnlyNeeded: Bool = false,
        sortMode: SortMode = .alphabetic
    ) async throws -> Setup {
        let db = try StorehopDatabase.inMemoryForTests()
        try db.seed(
            stores: [
                TestFixtures.store(id: "s_lidl", name: "Lidl", userId: uid),
                TestFixtures.store(id: "s_aldi", name: "Aldi", userId: uid),
            ],
            categories: [
                TestFixtures.category(id: "c_dairy", name: "Dairy", userId: uid),
                TestFixtures.category(id: "c_produce", name: "Produce", userId: uid),
            ]
        )
        let session = LocalOnlyUserSessionProvider(uid: uid)
        let householdSession = LocalOnlyHouseholdSessionProvider(initialHouseholdId: uid)
        let clock = MutableClock(nowMs: 1_000)
        let writer = db.queue
        let itemRepo = ItemRepository(
            writer: writer,
            itemDao: ItemDao(writer: writer),
            xrefDao: ItemStoreXrefDao(writer: writer),
            scoDao: StoreCategoryOrderDao(writer: writer),
            purchaseDao: PurchaseRecordDao(writer: writer),
            session: session,
            householdSession: householdSession,
            clock: clock,
            ids: SequenceIdGenerator()
        )
        let storeRepo = StoreRepository(
            writer: writer,
            storeDao: StoreDao(writer: writer),
            xrefDao: ItemStoreXrefDao(writer: writer),
            scoDao: StoreCategoryOrderDao(writer: writer),
            session: session,
            householdSession: householdSession,
            clock: clock,
            ids: SequenceIdGenerator()
        )
        let suite = UserDefaults(suiteName: "test-\(UUID().uuidString)")!
        let prefs = LiveUserPreferencesRepository(defaults: suite)
        prefs.setItemsListShowOnlyNeeded(showOnlyNeeded)
        prefs.setItemsListSortMode(sortMode)

        let vm = ItemsListViewModel(
            itemRepository: itemRepo,
            storeRepository: storeRepo,
            undoEventBus: UndoEventBus(),
            preferencesRepository: prefs,
            session: session,
            householdSession: householdSession
        )
        vm.bind()
        try await Task.sleep(nanoseconds: 50_000_000)
        return Setup(db: db, prefs: prefs, itemRepository: itemRepo, viewModel: vm)
    }

    func testShowOnlyNeededFiltersToNeededAcrossAllStoresInAlphabeticMode() async throws {
        let s = try await makeSetup(showOnlyNeeded: false, sortMode: .alphabetic)
        // Milk needed at Lidl, Bread needed at Aldi, Eggs not needed anywhere.
        _ = try await s.itemRepository.addItem(
            name: "Milk", categoryId: "c_dairy", storeIds: ["s_lidl"],
            quantity: nil, notes: nil, isNeeded: true, brand: nil, imageUrl: nil,
            isStaple: false, isPriority: false
        )
        _ = try await s.itemRepository.addItem(
            name: "Bread", categoryId: nil, storeIds: ["s_aldi"],
            quantity: nil, notes: nil, isNeeded: true, brand: nil, imageUrl: nil,
            isStaple: false, isPriority: false
        )
        let eggsId = try await s.itemRepository.addItem(
            name: "Eggs", categoryId: "c_dairy", storeIds: ["s_lidl"],
            quantity: nil, notes: nil, isNeeded: true, brand: nil, imageUrl: nil,
            isStaple: false, isPriority: false
        )
        try await s.itemRepository.markPurchasedAcrossAllStoresNoRecord(itemId: eggsId)

        try await waitForCondition {
            s.viewModel.items.count == 3 && s.viewModel.neededItemIds.count == 2
        }
        XCTAssertEqual(s.viewModel.items.map(\.item.name), ["Bread", "Eggs", "Milk"])

        // Enable showOnlyNeeded -> only Bread and Milk remain.
        s.viewModel.setShowOnlyNeeded(true)
        try await waitForCondition {
            s.viewModel.showOnlyNeeded && s.viewModel.items.count == 2
        }
        XCTAssertEqual(s.viewModel.items.map(\.item.name), ["Bread", "Milk"])

        // Disable showOnlyNeeded -> all 3 items return.
        s.viewModel.setShowOnlyNeeded(false)
        try await waitForCondition {
            !s.viewModel.showOnlyNeeded && s.viewModel.items.count == 3
        }
        XCTAssertEqual(s.viewModel.items.map(\.item.name), ["Bread", "Eggs", "Milk"])
    }

    func testShowOnlyNeededFiltersSectionsInCategoryModeAndUpdatesWhenToggled() async throws {
        let s = try await makeSetup(showOnlyNeeded: true, sortMode: .category)
        let milkId = try await s.itemRepository.addItem(
            name: "Milk", categoryId: "c_dairy", storeIds: ["s_lidl"],
            quantity: nil, notes: nil, isNeeded: true, brand: nil, imageUrl: nil,
            isStaple: false, isPriority: false
        )
        let applesId = try await s.itemRepository.addItem(
            name: "Apples", categoryId: "c_produce", storeIds: ["s_aldi"],
            quantity: nil, notes: nil, isNeeded: true, brand: nil, imageUrl: nil,
            isStaple: false, isPriority: false
        )
        try await s.itemRepository.markPurchasedAcrossAllStoresNoRecord(itemId: applesId)

        try await waitForCondition {
            s.viewModel.sections.count == 1
        }
        XCTAssertEqual(s.viewModel.sections.map(\.categoryName), ["Dairy"])
        XCTAssertEqual(s.viewModel.sections.first?.rows.map(\.item.name), ["Milk"])

        // Tapping minus on Milk while showOnlyNeeded is true immediately removes it.
        s.viewModel.toggleNeededAtAllStores(itemId: milkId, currentlyNeeded: true)
        try await waitForCondition {
            s.viewModel.sections.isEmpty
        }
    }

    func testShowOnlyNeededPrunesHiddenItemsFromSelection() async throws {
        let s = try await makeSetup(showOnlyNeeded: false, sortMode: .alphabetic)
        let milkId = try await s.itemRepository.addItem(
            name: "Milk", categoryId: nil, storeIds: ["s_lidl"],
            quantity: nil, notes: nil, isNeeded: true, brand: nil, imageUrl: nil,
            isStaple: false, isPriority: false
        )
        let eggsId = try await s.itemRepository.addItem(
            name: "Eggs", categoryId: nil, storeIds: [],
            quantity: nil, notes: nil, isNeeded: false, brand: nil, imageUrl: nil,
            isStaple: false, isPriority: false
        )
        try await waitForCondition {
            s.viewModel.items.count == 2 && s.viewModel.neededItemIds == [milkId]
        }

        s.viewModel.toggleSelection(milkId)
        s.viewModel.toggleSelection(eggsId)
        XCTAssertEqual(s.viewModel.selectedItemIds, [milkId, eggsId])

        s.viewModel.setShowOnlyNeeded(true)
        try await waitForCondition {
            s.viewModel.showOnlyNeeded && s.viewModel.selectedItemIds == [milkId]
        }
    }
}
