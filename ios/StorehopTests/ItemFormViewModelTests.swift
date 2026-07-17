import XCTest
import UIKit
@testable import Storehop

@MainActor
final class ItemFormViewModelTests: XCTestCase {

    private struct Setup {
        let db: StorehopDatabase
        let viewModel: ItemFormViewModel
        let undoEventBus: UndoEventBus
    }

    private func makeSetup(itemId: String? = nil, uid: String = "u1") async throws -> Setup {
        let db = try StorehopDatabase.inMemoryForTests()
        try db.seed(
            stores: [
                TestFixtures.store(id: "s_lidl", name: "Lidl", userId: uid),
                TestFixtures.store(id: "s_aldi", name: "Aldi", userId: uid),
            ],
            categories: [
                TestFixtures.category(id: "c_dairy", name: "Dairy", userId: uid),
            ]
        )
        let session = LocalOnlyUserSessionProvider(uid: uid)
        let clock = MutableClock(nowMs: 1_000)
        let writer = db.queue
        let householdSession = LocalOnlyHouseholdSessionProvider(initialHouseholdId: uid)
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
        let categoryRepo = CategoryRepository(
            writer: writer,
            categoryDao: CategoryDao(writer: writer),
            itemDao: ItemDao(writer: writer),
            scoDao: StoreCategoryOrderDao(writer: writer),
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
        let undoBus = UndoEventBus()
        let vm = ItemFormViewModel(
            itemId: itemId,
            itemRepository: itemRepo,
            categoryRepository: categoryRepo,
            storeRepository: storeRepo,
            imageUploader: NoOpImageUploader(),
            undoEventBus: undoBus,
            session: session
        )
        vm.bind()
        try await Task.sleep(nanoseconds: 50_000_000)
        return Setup(db: db, viewModel: vm, undoEventBus: undoBus)
    }

    func testValidateRejectsEmptyName() async throws {
        let s = try await makeSetup()
        s.viewModel.name = "   "
        s.viewModel.submit()
        XCTAssertTrue(s.viewModel.nameError)
        XCTAssertFalse(s.viewModel.saved)
    }

    func testSubmitAddSavesItemTaggedToSelectedStores() async throws {
        let s = try await makeSetup()
        s.viewModel.name = "Mozzarella"
        s.viewModel.brand = "Galbani"
        s.viewModel.categoryId = "c_dairy"
        s.viewModel.toggleStore("s_lidl")
        s.viewModel.toggleStore("s_aldi")

        s.viewModel.submit()
        try await waitForCondition { s.viewModel.saved }

        let count = try await s.db.queue.read { conn in
            try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM items WHERE name = 'Mozzarella'") ?? 0
        }
        XCTAssertEqual(count, 1)

        let xrefCount = try await s.db.queue.read { conn in
            try Int.fetchOne(conn, sql: """
                SELECT COUNT(*) FROM item_store_xref isx
                INNER JOIN items i ON i.id = isx.itemId
                WHERE i.name = 'Mozzarella' AND isx.deletedAt IS NULL
                """) ?? 0
        }
        XCTAssertEqual(xrefCount, 2, "Item should be tagged to both selected stores")
    }

    func testSubmitWithLocalImageRunsUploaderAndPatchesUrl() async throws {
        let s = try await makeSetup()
        s.viewModel.name = "Bread"
        s.viewModel.toggleStore("s_lidl")
        s.viewModel.pickImage(UIImage())  // empty image — NoOpImageUploader returns a fake URL

        s.viewModel.submit()
        try await waitForCondition(timeout: 2.0) { s.viewModel.saved }

        let storedUrl = try await s.db.queue.read { conn in
            try String.fetchOne(conn, sql: "SELECT imageUrl FROM items WHERE name = 'Bread'")
        }
        XCTAssertNotNil(storedUrl ?? nil)
        XCTAssertTrue(storedUrl?.contains("storehop/items/") ?? false)
    }

    func testEditModeLoadsExistingItemFields() async throws {
        let setupAdd = try await makeSetup()
        setupAdd.viewModel.name = "Milk"
        setupAdd.viewModel.brand = "Mimosa"
        setupAdd.viewModel.toggleStore("s_lidl")
        setupAdd.viewModel.submit()
        try await waitForCondition { setupAdd.viewModel.saved }

        // Look up the saved item id by name.
        let id = try await setupAdd.db.queue.read { conn in
            try String.fetchOne(conn, sql: "SELECT id FROM items WHERE name = 'Milk'")
        } ?? ""
        XCTAssertFalse(id.isEmpty)

        // Build a second VM in edit mode against the SAME database.
        let session = LocalOnlyUserSessionProvider(uid: "u1")
        let householdSession = LocalOnlyHouseholdSessionProvider(initialHouseholdId: "u1")
        let clock = MutableClock(nowMs: 1_000)
        let writer = setupAdd.db.queue
        let itemRepo = ItemRepository(
            writer: writer,
            itemDao: ItemDao(writer: writer),
            xrefDao: ItemStoreXrefDao(writer: writer),
            scoDao: StoreCategoryOrderDao(writer: writer),
            purchaseDao: PurchaseRecordDao(writer: writer),
            session: session, householdSession: householdSession, clock: clock, ids: SequenceIdGenerator()
        )
        let editVm = ItemFormViewModel(
            itemId: id,
            itemRepository: itemRepo,
            categoryRepository: CategoryRepository(
                writer: writer, categoryDao: CategoryDao(writer: writer),
                itemDao: ItemDao(writer: writer), scoDao: StoreCategoryOrderDao(writer: writer),
                session: session, householdSession: householdSession, clock: clock, ids: SequenceIdGenerator()
            ),
            storeRepository: StoreRepository(
                writer: writer, storeDao: StoreDao(writer: writer),
                xrefDao: ItemStoreXrefDao(writer: writer), scoDao: StoreCategoryOrderDao(writer: writer),
                session: session, householdSession: householdSession, clock: clock, ids: SequenceIdGenerator()
            ),
            imageUploader: NoOpImageUploader(),
            undoEventBus: UndoEventBus(),
            session: session
        )
        editVm.bind()
        try await waitForCondition(timeout: 1.0) { !editVm.isLoading }

        XCTAssertEqual(editVm.name, "Milk")
        XCTAssertEqual(editVm.brand, "Mimosa")
        XCTAssertEqual(editVm.storeIds, ["s_lidl"])
    }

    func testDeleteEmitsUndoEventAndSetsDeletedFlag() async throws {
        // Add an item first.
        let s = try await makeSetup()
        s.viewModel.name = "Bread"
        s.viewModel.toggleStore("s_lidl")
        s.viewModel.submit()
        try await waitForCondition { s.viewModel.saved }

        let id = try await s.db.queue.read { conn in
            try String.fetchOne(conn, sql: "SELECT id FROM items WHERE name = 'Bread'")
        } ?? ""

        // Build an edit-mode VM and listen for the undo event.
        let session = LocalOnlyUserSessionProvider(uid: "u1")
        let householdSession = LocalOnlyHouseholdSessionProvider(initialHouseholdId: "u1")
        let clock = MutableClock(nowMs: 1_000)
        let writer = s.db.queue
        let undoBus = UndoEventBus()

        var receivedEvent: UndoEvent?
        let listenerTask = Task {
            for await event in await undoBus.events() {
                receivedEvent = event
                break
            }
        }

        let editVm = ItemFormViewModel(
            itemId: id,
            itemRepository: ItemRepository(
                writer: writer, itemDao: ItemDao(writer: writer),
                xrefDao: ItemStoreXrefDao(writer: writer),
                scoDao: StoreCategoryOrderDao(writer: writer),
                purchaseDao: PurchaseRecordDao(writer: writer),
                session: session, householdSession: householdSession, clock: clock, ids: SequenceIdGenerator()
            ),
            categoryRepository: CategoryRepository(
                writer: writer, categoryDao: CategoryDao(writer: writer),
                itemDao: ItemDao(writer: writer), scoDao: StoreCategoryOrderDao(writer: writer),
                session: session, householdSession: householdSession, clock: clock, ids: SequenceIdGenerator()
            ),
            storeRepository: StoreRepository(
                writer: writer, storeDao: StoreDao(writer: writer),
                xrefDao: ItemStoreXrefDao(writer: writer), scoDao: StoreCategoryOrderDao(writer: writer),
                session: session, householdSession: householdSession, clock: clock, ids: SequenceIdGenerator()
            ),
            imageUploader: NoOpImageUploader(),
            undoEventBus: undoBus,
            session: session
        )
        editVm.bind()
        try await waitForCondition(timeout: 1.0) { !editVm.isLoading }

        editVm.delete()
        try await waitForCondition(timeout: 1.0) { editVm.deleted }

        // Allow the bus to deliver.
        try await Task.sleep(nanoseconds: 50_000_000)
        listenerTask.cancel()

        XCTAssertNotNil(receivedEvent)
        if case .itemDeleted(let eventId, let name) = receivedEvent {
            XCTAssertEqual(eventId, id)
            XCTAssertEqual(name, "Bread")
        } else {
            XCTFail("Expected .itemDeleted event")
        }
    }

    // MARK: - v0.6.1: inline "+ New category" from the item edit screen

    func testAddCategoryOnSuccessAutoSelectsTheNewId() async throws {
        let s = try await makeSetup()
        // No category selected to start.
        XCTAssertNil(s.viewModel.categoryId)

        let result = await s.viewModel.addCategory(name: "  Pet supplies  ")
        XCTAssertNil(result, "Expected nil on success, got: \(result ?? "")")
        // VM auto-selected the just-created id.
        XCTAssertNotNil(s.viewModel.categoryId)

        // The category is persisted with the trimmed name.
        let count = try await s.db.queue.read { conn in
            try Int.fetchOne(
                conn,
                sql: "SELECT COUNT(*) FROM categories WHERE name = 'Pet supplies' AND deletedAt IS NULL"
            ) ?? 0
        }
        XCTAssertEqual(count, 1)
    }

    func testAddCategoryWithBlankNameReturnsErrorAndDoesNotPersist() async throws {
        let s = try await makeSetup()
        let result = await s.viewModel.addCategory(name: "    ")
        XCTAssertNotNil(result)
        XCTAssertNil(s.viewModel.categoryId)

        let count = try await s.db.queue.read { conn in
            try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM categories WHERE userId = 'u1'") ?? 0
        }
        // Only the seeded "Dairy" should exist; no new row added.
        XCTAssertEqual(count, 1)
    }

    func testAddCategoryWithDuplicateNameReturnsErrorAndPreservesExistingSelection() async throws {
        let s = try await makeSetup()
        // Pre-select the seeded "Dairy" so we can confirm the failure path
        // doesn't wipe the existing selection on the form.
        s.viewModel.categoryId = "c_dairy"

        // "Dairy" already exists alive — addCategory should throw duplicateName.
        let result = await s.viewModel.addCategory(name: "Dairy")
        XCTAssertNotNil(result)
        XCTAssertEqual(s.viewModel.categoryId, "c_dairy")
    }

    // MARK: - v0.9.0 allPickedStoresAreOneOff
    //
    // Drives the staple + priority toggle visibility in the form: those
    // concepts don't combine with "one-off purchase" semantically, so the
    // form hides them when every picked store is one-off. Mirrors
    // Android's `ItemFormViewModelTest.allPickedStoresAreOneOff` cases.

    func testAllPickedStoresAreOneOffIsFalseWhenNothingPicked() async throws {
        let s = try await makeOneOffSetup()
        XCTAssertEqual(s.viewModel.storeIds, [], "Sanity: no pre-selection")
        XCTAssertFalse(s.viewModel.allPickedStoresAreOneOff,
                       "Empty selection should keep toggles visible — false")
    }

    func testAllPickedStoresAreOneOffIsTrueWhenEveryPickedStoreIsOneOff() async throws {
        let s = try await makeOneOffSetup()
        s.viewModel.storeIds = ["s_hardware", "s_online"]  // both are one-off
        XCTAssertTrue(s.viewModel.allPickedStoresAreOneOff,
                      "Both picked stores have isOneOff=true → hide toggles")
    }

    func testAllPickedStoresAreOneOffIsFalseWhenAtLeastOnePickedStoreIsRegular() async throws {
        let s = try await makeOneOffSetup()
        s.viewModel.storeIds = ["s_lidl", "s_hardware"]  // mixed
        XCTAssertFalse(s.viewModel.allPickedStoresAreOneOff,
                       "Mixed regular + one-off picks should keep toggles visible — false")
    }

    // MARK: - v0.9.1 staple default

    func testSubmitAddDefaultsStapleTrue() async throws {
        // New items default to "Always on the list" — most grocery adds are
        // recurring buys (Mike's v0.9.1 report). Mirrors Android's updated
        // ItemFormViewModelTest save assertion.
        let s = try await makeSetup()
        XCTAssertTrue(s.viewModel.isStaple, "Form must open with staple pre-checked")

        s.viewModel.name = "Mozzarella"
        s.viewModel.toggleStore("s_lidl")
        s.viewModel.submit()
        try await waitForCondition { s.viewModel.saved }

        let isStaple = try await s.db.queue.read { conn in
            try Bool.fetchOne(conn, sql: "SELECT isStaple FROM items WHERE name = 'Mozzarella'")
        }
        XCTAssertEqual(isStaple, true)
    }

    func testSubmitCoercesStapleFalseWhenAllPickedStoresAreOneOff() async throws {
        // The staple toggle is hidden for one-off-only picks, so whatever
        // value it held must not leak into the save — submit() coerces it
        // back to false. A one-off couch isn't a recurring buy.
        let s = try await makeOneOffSetup()
        s.viewModel.name = "Couch"
        s.viewModel.storeIds = ["s_hardware", "s_online"]  // both one-off
        XCTAssertTrue(s.viewModel.isStaple, "Sanity: default still true underneath the hidden toggle")

        s.viewModel.submit()
        try await waitForCondition { s.viewModel.saved }

        let isStaple = try await s.db.queue.read { conn in
            try Bool.fetchOne(conn, sql: "SELECT isStaple FROM items WHERE name = 'Couch'")
        }
        XCTAssertEqual(isStaple, false)
    }

    /// Seeds two regular stores (`s_lidl`, `s_aldi`) plus two one-off stores
    /// (`s_hardware`, `s_online`) so the three `allPickedStoresAreOneOff`
    /// cases above can exercise empty / all-one-off / mixed selections.
    private func makeOneOffSetup(uid: String = "u1") async throws -> Setup {
        let db = try StorehopDatabase.inMemoryForTests()
        try db.seed(
            stores: [
                TestFixtures.store(id: "s_lidl",     name: "Lidl",     userId: uid),
                TestFixtures.store(id: "s_aldi",     name: "Aldi",     userId: uid),
                TestFixtures.store(id: "s_hardware", name: "Hardware", userId: uid, isOneOff: true),
                TestFixtures.store(id: "s_online",   name: "Online",   userId: uid, isOneOff: true),
            ],
            categories: [
                TestFixtures.category(id: "c_dairy", name: "Dairy", userId: uid),
            ]
        )
        let session = LocalOnlyUserSessionProvider(uid: uid)
        let clock = MutableClock(nowMs: 1_000)
        let writer = db.queue
        let householdSession = LocalOnlyHouseholdSessionProvider(initialHouseholdId: uid)
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
        let categoryRepo = CategoryRepository(
            writer: writer,
            categoryDao: CategoryDao(writer: writer),
            itemDao: ItemDao(writer: writer),
            scoDao: StoreCategoryOrderDao(writer: writer),
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
        let undoBus = UndoEventBus()
        let vm = ItemFormViewModel(
            itemId: nil,
            itemRepository: itemRepo,
            categoryRepository: categoryRepo,
            storeRepository: storeRepo,
            imageUploader: NoOpImageUploader(),
            undoEventBus: undoBus,
            session: session
        )
        vm.bind()
        // Wait for the stores ValueObservation to surface all four seeded
        // rows into vm.stores so `allPickedStoresAreOneOff` can resolve
        // picked ids against the live list.
        try await Task.sleep(nanoseconds: 100_000_000)
        return Setup(db: db, viewModel: vm, undoEventBus: undoBus)
    }
}
