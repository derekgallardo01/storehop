import XCTest
import GRDB
@testable import Storehop

@MainActor
final class ShopAtStoreViewModelTests: XCTestCase {

    private struct Setup {
        let db: StorehopDatabase
        let prefs: any UserPreferencesRepository
        let itemRepository: ItemRepository
        let viewModel: ShopAtStoreViewModel
    }

    private func makeSetup(
        uid: String = "u1",
        storeId: String = "s_lidl",
        showPurchased: Bool = true
    ) async throws -> Setup {
        let db = try StorehopDatabase.inMemoryForTests()
        try db.seed(
            stores: [TestFixtures.store(id: storeId, name: "Lidl", userId: uid)],
            categories: [TestFixtures.category(id: "c_dairy", name: "Dairy", userId: uid)]
        )
        let session = LocalOnlyUserSessionProvider(uid: uid)
        let householdSession = LocalOnlyHouseholdSessionProvider(initialHouseholdId: uid)
        let clock = MutableClock(nowMs: 1_000)
        let writer = db.queue
        let repos = makeRepositories(writer: writer, session: session, householdSession: householdSession, clock: clock)
        // Fresh UserDefaults suite per test so toggle state doesn't leak.
        let suite = UserDefaults(suiteName: "test-\(UUID().uuidString)")!
        let prefs = LiveUserPreferencesRepository(defaults: suite)
        prefs.setShowPurchased(showPurchased)
        let vm = ShopAtStoreViewModel(
            storeId: storeId,
            shoppingRepository: repos.shopping,
            itemRepository: repos.item,
            storeRepository: repos.store,
            preferencesRepository: prefs,
            session: session,
            sessionTracker: ShoppingSessionTracker(clock: clock)
        )
        vm.bind()
        // Let the binder spin up its initial subscription.
        try await Task.sleep(nanoseconds: 50_000_000)
        return Setup(db: db, prefs: prefs, itemRepository: repos.item, viewModel: vm)
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
                pendingSync: true, isNeeded: true, lastPurchasedAt: nil,
                householdId: "u1"
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

    // MARK: - visibility toggles

    func testShowPurchasedFalseHidesPurchasedNonStaples() async throws {
        let s = try await makeSetup(showPurchased: false)
        s.viewModel.quickAdd(name: "Milk")  // needed -> visible
        s.viewModel.quickAdd(name: "Bread") // will be checked off -> hidden
        try await waitForCondition {
            s.viewModel.sections.flatMap(\.rows).count >= 2
        }
        let bread = s.viewModel.sections.flatMap(\.rows).first { $0.itemName == "Bread" }!
        s.viewModel.togglePurchased(row: bread)
        try await waitForCondition {
            !s.viewModel.sections.flatMap(\.rows).contains { $0.itemName == "Bread" }
        }
        let visible = s.viewModel.sections.flatMap(\.rows).map(\.itemName)
        XCTAssertEqual(visible, ["Milk"])
    }

    func testShowPurchasedFalseAlsoHidesPurchasedStaples() async throws {
        // The single toggle should hide every checked-off row regardless of
        // staple status -- "checked off" is one user-facing concept.
        let s = try await makeSetup(showPurchased: false)
        _ = try await s.itemRepository.addItem(
            name: "Eggs",
            categoryId: nil,
            storeIds: ["s_lidl"],
            quantity: nil,
            notes: nil,
            brand: nil,
            imageUrl: nil,
            isStaple: true,
            isPriority: false
        )
        try await waitForCondition {
            s.viewModel.sections.flatMap(\.rows).contains { $0.itemName == "Eggs" }
        }
        let eggs = s.viewModel.sections.flatMap(\.rows).first { $0.itemName == "Eggs" }!
        // Needed staple is visible.
        XCTAssertTrue(s.viewModel.sections.flatMap(\.rows).contains { $0.itemName == "Eggs" })
        // Check it off -> purchased staple, should also be hidden under the
        // single toggle.
        s.viewModel.togglePurchased(row: eggs)
        try await waitForCondition {
            !s.viewModel.sections.flatMap(\.rows).contains { $0.itemName == "Eggs" }
        }
    }

    func testToggleUpdatesLiveWhenSetterCalled() async throws {
        let s = try await makeSetup(showPurchased: true)
        s.viewModel.quickAdd(name: "Bread")
        try await waitForCondition {
            s.viewModel.sections.flatMap(\.rows).contains { $0.itemName == "Bread" }
        }
        let bread = s.viewModel.sections.flatMap(\.rows).first { $0.itemName == "Bread" }!
        s.viewModel.togglePurchased(row: bread)
        try await waitForCondition {
            s.viewModel.sections.flatMap(\.rows).first { $0.itemName == "Bread" }?.isNeeded == false
        }
        // Bread is purchased and visible (showPurchased=true).
        XCTAssertTrue(s.viewModel.sections.flatMap(\.rows).contains { $0.itemName == "Bread" })
        // Flip the toggle -> Bread should disappear.
        s.viewModel.setShowPurchased(false)
        try await waitForCondition {
            !s.viewModel.sections.flatMap(\.rows).contains { $0.itemName == "Bread" }
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

    // MARK: - QuickAdd autocomplete

    func testSubmitQuickAddTextDedupesWhenNameMatchesExisting() async throws {
        // Master library already has "Milk". Typing "Milk" in the QuickAdd
        // bar of a different store should NOT create a duplicate -- the
        // existing item gets re-tagged. (Mike's reported v0.5.6 bug.)
        let s = try await makeSetup()
        let firstId = try await s.itemRepository.addItem(
            name: "Milk",
            categoryId: nil,
            storeIds: [],  // master only, not tagged anywhere yet
            quantity: nil,
            notes: nil,
            brand: nil,
            imageUrl: nil,
            isStaple: false,
            isPriority: false
        )

        s.viewModel.quickAddInput = "Milk"
        s.viewModel.submitQuickAddText()
        try await waitForCondition {
            s.viewModel.sections.flatMap(\.rows).contains { $0.itemName == "Milk" }
        }
        // Items table still has exactly one "Milk" entry.
        let count = try await s.db.queue.read { conn in
            try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM items WHERE name = ? COLLATE NOCASE AND deletedAt IS NULL", arguments: ["Milk"]) ?? -1
        }
        XCTAssertEqual(count, 1, "addItemFromQuickAdd must not duplicate existing names")
        // The Milk on the shopping list is the SAME item we created above.
        let row = s.viewModel.sections.flatMap(\.rows).first { $0.itemName == "Milk" }!
        XCTAssertEqual(row.itemId, firstId)
        // Input was cleared after submit.
        try await waitForCondition { s.viewModel.quickAddInput.isEmpty }
    }

    func testQuickAddSuggestionsFiltersByNameSubstring() async throws {
        let s = try await makeSetup()
        _ = try await s.itemRepository.addItem(name: "Milk", categoryId: nil, storeIds: [], quantity: nil, notes: nil, brand: nil, imageUrl: nil, isStaple: false, isPriority: false)
        _ = try await s.itemRepository.addItem(name: "Almond Milk", categoryId: nil, storeIds: [], quantity: nil, notes: nil, brand: nil, imageUrl: nil, isStaple: false, isPriority: false)
        _ = try await s.itemRepository.addItem(name: "Eggs", categoryId: nil, storeIds: [], quantity: nil, notes: nil, brand: nil, imageUrl: nil, isStaple: false, isPriority: false)

        // Set quickAddInput FIRST so the eventual masterItems delivery
        // triggers a refreshQuickAddSuggestions with a non-empty needle.
        // (Previously the test waited for suggestions to appear before
        // setting the needle, which was impossible — suggestions stay
        // empty until input is non-empty.)
        s.viewModel.quickAddInput = "MIL"
        // Generous timeout for the CI runner — the macos-15 simulator is
        // slower than local at propagating GRDB ValueObservation updates,
        // and the test got flaky at the default 1s. 5s is well within
        // total test budget and gives the observation plenty of slack.
        try await waitForCondition(timeout: 5.0) {
            !s.viewModel.quickAddSuggestions.isEmpty
        }
        let names = s.viewModel.quickAddSuggestions.map(\.name)
        // Both milks match (case-insensitive substring); prefix-on-name
        // ranks "Milk" before "Almond Milk".
        XCTAssertEqual(names, ["Milk", "Almond Milk"])
    }

    func testPickExistingItemTagsToStoreWithoutDuplicating() async throws {
        let s = try await makeSetup()
        let milkId = try await s.itemRepository.addItem(
            name: "Milk", categoryId: nil, storeIds: [], quantity: nil,
            notes: nil, brand: nil, imageUrl: nil, isStaple: false, isPriority: false
        )

        s.viewModel.pickExistingItem(itemId: milkId)
        try await waitForCondition {
            s.viewModel.sections.flatMap(\.rows).contains { $0.itemName == "Milk" }
        }
        // Items table still has exactly one Milk.
        let count = try await s.db.queue.read { conn in
            try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM items WHERE deletedAt IS NULL", arguments: []) ?? -1
        }
        XCTAssertEqual(count, 1)
    }

    // MARK: - Helpers

    private func makeRepositories(writer: any DatabaseWriter, session: any UserSessionProvider, householdSession: any HouseholdSessionProvider, clock: any Clock) -> (
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
            purchaseDao: purchaseDao, session: session, householdSession: householdSession,
            clock: clock, ids: SequenceIdGenerator()
        )
        let store = StoreRepository(
            writer: writer, storeDao: storeDao, xrefDao: xrefDao, scoDao: scoDao,
            session: session, householdSession: householdSession, clock: clock, ids: SequenceIdGenerator()
        )
        let shopping = ShoppingRepository(shoppingDao: shoppingDao, storeDao: storeDao, session: session)
        return (shopping, item, store)
    }
}

// MARK: - Test shims

/// Backward-compat shim for tests written against the old `quickAdd(name:)`
/// API. The production VM now hoists input into `quickAddInput` and exposes
/// `submitQuickAddText()` for the IME-Done path; this helper composes the
/// two so existing test cases keep working with their original wording.
@MainActor
extension ShopAtStoreViewModel {
    func quickAdd(name: String) {
        quickAddInput = name
        submitQuickAddText()
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
