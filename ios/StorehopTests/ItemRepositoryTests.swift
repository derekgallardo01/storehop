import XCTest
import GRDB
@testable import Storehop

/// Covers the orchestration logic in ItemRepository — multi-DAO transactions,
/// cross-store cascade, snapshot-precise undo, ownership invariants.
final class ItemRepositoryTests: XCTestCase {

    private struct Setup {
        let db: StorehopDatabase
        let repo: ItemRepository
        let session: LocalOnlyUserSessionProvider
        let clock: MutableClock
    }

    private func setup(uid: String = "u1") throws -> Setup {
        let db = try StorehopDatabase.inMemoryForTests()
        let session = LocalOnlyUserSessionProvider(uid: uid)
        let clock = MutableClock(nowMs: 1_000)
        let writer = db.queue
        let repo = ItemRepository(
            writer: writer,
            itemDao: ItemDao(writer: writer),
            xrefDao: ItemStoreXrefDao(writer: writer),
            scoDao: StoreCategoryOrderDao(writer: writer),
            purchaseDao: PurchaseRecordDao(writer: writer),
            session: session,
            householdSession: LocalOnlyHouseholdSessionProvider(initialHouseholdId: uid),
            clock: clock,
            ids: SequenceIdGenerator()
        )
        try db.seed(
            stores: [
                TestFixtures.store(id: "s_lidl", name: "Lidl", userId: uid),
                TestFixtures.store(id: "s_aldi", name: "Aldi", userId: uid),
            ],
            categories: [
                TestFixtures.category(id: "c_dairy", name: "Dairy", userId: uid),
            ]
        )
        return Setup(db: db, repo: repo, session: session, clock: clock)
    }

    // MARK: - addItem

    func testAddItemCreatesItemPlusXrefsPlusScoRowsInOneTransaction() async throws {
        let s = try setup()
        let id = try await s.repo.addItem(
            name: "Mozzarella",
            categoryId: "c_dairy",
            storeIds: ["s_lidl", "s_aldi"],
            quantity: nil,
            notes: nil,
            brand: nil,
            imageUrl: nil,
            isStaple: false,
            isPriority: false
        )

        try await s.db.queue.read { conn in
            // Item row exists, with the trimmed fields.
            let item = try Item.fetchOne(conn, sql: "SELECT * FROM items WHERE id = ?", arguments: [id])
            XCTAssertNotNil(item)
            XCTAssertEqual(item?.name, "Mozzarella")
            XCTAssertEqual(item?.userId, "u1")
            XCTAssertTrue(item?.isNeeded ?? false)

            // Xrefs cover both stores.
            let xrefs = try ItemStoreXref.fetchAll(conn, sql: "SELECT * FROM item_store_xref WHERE itemId = ? AND deletedAt IS NULL", arguments: [id])
            XCTAssertEqual(Set(xrefs.map(\.storeId)), ["s_lidl", "s_aldi"])

            // SCO rows exist for the category at each tagged store.
            let scoCount = try Int.fetchOne(conn, sql: """
                SELECT COUNT(*) FROM store_category_order
                WHERE categoryId = 'c_dairy' AND deletedAt IS NULL
                """) ?? -1
            XCTAssertEqual(scoCount, 2, "appendIfMissing should create one SCO row per tagged store")
        }
    }

    func testAddItemWithNoCategorySkipsSCO() async throws {
        let s = try setup()
        try await s.repo.addItem(
            name: "Mystery",
            categoryId: nil,
            storeIds: ["s_lidl"],
            quantity: nil,
            notes: nil,
            brand: nil,
            imageUrl: nil,
            isStaple: false,
            isPriority: false
        )

        let scoCount = try await s.db.queue.read { conn in
            try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM store_category_order") ?? -1
        }
        XCTAssertEqual(scoCount, 0, "No category means no SCO rows")
    }

    func testAddItemTrimsWhitespaceAndConvertsEmptyStringsToNil() async throws {
        let s = try setup()
        let id = try await s.repo.addItem(
            name: "  Bread  ",
            categoryId: nil,
            storeIds: ["s_lidl"],
            quantity: "  ",
            notes: "",
            brand: "   Padaria   ",
            imageUrl: nil,
            isStaple: false,
            isPriority: false
        )

        let item = try await s.db.queue.read { conn in
            try Item.fetchOne(conn, sql: "SELECT * FROM items WHERE id = ?", arguments: [id])
        }
        XCTAssertEqual(item?.name, "Bread")
        XCTAssertEqual(item?.brand, "Padaria")
        XCTAssertNil(item?.quantity, "Whitespace-only quantity should become nil")
        XCTAssertNil(item?.notes, "Empty notes should become nil")
    }

    // MARK: - updateItem

    func testUpdateItemPreservesIsNeededAndCreatedAt() async throws {
        let s = try setup()
        s.clock.now = 1_000
        let id = try await s.repo.addItem(
            name: "Mozz", categoryId: "c_dairy", storeIds: ["s_lidl"],
            quantity: nil, notes: nil, brand: nil, imageUrl: nil,
            isStaple: false, isPriority: false
        )
        // Mark purchased so isNeeded flips to false.
        s.clock.now = 2_000
        _ = try await s.repo.markPurchasedAtStore(itemId: id, storeId: "s_lidl")

        s.clock.now = 3_000
        try await s.repo.updateItem(
            id: id, name: "Mozzarella", categoryId: "c_dairy",
            storeIds: ["s_lidl"], quantity: "2", notes: nil, brand: nil,
            imageUrl: nil, isStaple: false, isPriority: false
        )

        let item = try await s.db.queue.read { conn in
            try Item.fetchOne(conn, sql: "SELECT * FROM items WHERE id = ?", arguments: [id])
        }
        XCTAssertEqual(item?.name, "Mozzarella")
        XCTAssertEqual(item?.quantity, "2")
        XCTAssertEqual(item?.createdAt, 1_000, "createdAt must be preserved across updates")
        XCTAssertEqual(item?.updatedAt, 3_000)
        // Item-level isNeeded is vestigial post-v5; the per-store xref state matters.
    }

    // MARK: - softDelete cascade

    func testSoftDeleteCascadesToXrefsAndPurchaseRecords() async throws {
        let s = try setup()
        s.clock.now = 1_000
        let id = try await s.repo.addItem(
            name: "Bread", categoryId: nil, storeIds: ["s_lidl", "s_aldi"],
            quantity: nil, notes: nil, brand: nil, imageUrl: nil,
            isStaple: false, isPriority: false
        )
        s.clock.now = 2_000
        _ = try await s.repo.markPurchasedAtStore(itemId: id, storeId: "s_lidl")

        s.clock.now = 3_000
        try await s.repo.softDelete(id: id)

        try await s.db.queue.read { conn in
            let liveXrefs = try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM item_store_xref WHERE itemId = ? AND deletedAt IS NULL", arguments: [id]) ?? -1
            XCTAssertEqual(liveXrefs, 0, "All xrefs cascade-tombstoned")
            let livePurchases = try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM purchase_records WHERE itemId = ? AND deletedAt IS NULL", arguments: [id]) ?? -1
            XCTAssertEqual(livePurchases, 0, "Purchase records cascade-tombstoned")
            let item = try Item.fetchOne(conn, sql: "SELECT * FROM items WHERE id = ?", arguments: [id])
            XCTAssertNotNil(item?.deletedAt)
        }
    }

    func testUndoSoftDeleteRestoresAllCascadedRowsAtExactDeletedAt() async throws {
        let s = try setup()
        s.clock.now = 1_000
        let id = try await s.repo.addItem(
            name: "Bread", categoryId: nil, storeIds: ["s_lidl", "s_aldi"],
            quantity: nil, notes: nil, brand: nil, imageUrl: nil,
            isStaple: false, isPriority: false
        )
        s.clock.now = 5_000
        try await s.repo.softDelete(id: id)
        s.clock.now = 6_000
        try await s.repo.undoSoftDelete(id: id)

        try await s.db.queue.read { conn in
            let liveXrefs = try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM item_store_xref WHERE itemId = ? AND deletedAt IS NULL", arguments: [id]) ?? -1
            XCTAssertEqual(liveXrefs, 2, "Both xrefs restored")
            let item = try Item.fetchOne(conn, sql: "SELECT * FROM items WHERE id = ?", arguments: [id])
            XCTAssertNil(item?.deletedAt)
        }
    }

    // MARK: - markPurchasedAtStore + undoPurchase

    func testMarkPurchasedAtStoreCascadesAcrossAllStoresAndReturnsSnapshotTimestamp() async throws {
        let s = try setup()
        s.clock.now = 1_000
        let id = try await s.repo.addItem(
            name: "Mozzarella", categoryId: "c_dairy", storeIds: ["s_lidl", "s_aldi"],
            quantity: nil, notes: nil, brand: nil, imageUrl: nil,
            isStaple: false, isPriority: false
        )

        s.clock.now = 5_000
        let snapshot = try await s.repo.markPurchasedAtStore(itemId: id, storeId: "s_lidl")
        XCTAssertEqual(snapshot, 5_000)

        try await s.db.queue.read { conn in
            let xrefs = try ItemStoreXref.fetchAll(conn, sql: "SELECT * FROM item_store_xref WHERE itemId = ?", arguments: [id])
            XCTAssertEqual(xrefs.count, 2)
            for x in xrefs {
                XCTAssertFalse(x.isNeeded, "Cascade flips every xref to !isNeeded")
                XCTAssertEqual(x.lastPurchasedAt, 5_000)
            }
            // Exactly one PurchaseRecord, attributed to the store the user shopped at.
            let records = try PurchaseRecord.fetchAll(conn, sql: "SELECT * FROM purchase_records WHERE itemId = ?", arguments: [id])
            XCTAssertEqual(records.count, 1)
            XCTAssertEqual(records.first?.storeId, "s_lidl")
            XCTAssertEqual(records.first?.purchasedAt, 5_000)
        }
    }

    func testUndoPurchaseRestoresAllXrefsAndSoftDeletesMatchingRecord() async throws {
        let s = try setup()
        s.clock.now = 1_000
        let id = try await s.repo.addItem(
            name: "Mozzarella", categoryId: nil, storeIds: ["s_lidl", "s_aldi"],
            quantity: nil, notes: nil, brand: nil, imageUrl: nil,
            isStaple: false, isPriority: false
        )
        s.clock.now = 5_000
        let snapshot = try await s.repo.markPurchasedAtStore(itemId: id, storeId: "s_lidl")

        s.clock.now = 6_000
        try await s.repo.undoPurchase(itemId: id, snapshotTime: try XCTUnwrap(snapshot))

        try await s.db.queue.read { conn in
            let xrefs = try ItemStoreXref.fetchAll(conn, sql: "SELECT * FROM item_store_xref WHERE itemId = ?", arguments: [id])
            for x in xrefs {
                XCTAssertTrue(x.isNeeded, "Undo restores isNeeded=1 across all stores")
                XCTAssertNil(x.lastPurchasedAt, "Undo clears lastPurchasedAt")
            }
            let liveRecords = try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM purchase_records WHERE itemId = ? AND deletedAt IS NULL", arguments: [id]) ?? -1
            XCTAssertEqual(liveRecords, 0, "Purchase record soft-deleted by snapshot match")
        }
    }

    // MARK: - v0.8.1 bulkTagStoresForItems

    func testBulkTagStoresForItemsUnionsStoreIdsIntoEverySelectedItem() async throws {
        let s = try setup()
        let item1 = try await s.repo.addItem(
            name: "Toilet Paper", categoryId: nil, storeIds: ["s_lidl"],
            quantity: nil, notes: nil, brand: nil, imageUrl: nil,
            isStaple: false, isPriority: false
        )
        let item2 = try await s.repo.addItem(
            name: "Milk", categoryId: nil, storeIds: [],
            quantity: nil, notes: nil, brand: nil, imageUrl: nil,
            isStaple: false, isPriority: false
        )

        try await s.repo.bulkTagStoresForItems(
            itemIds: [item1, item2],
            storeIdsToAdd: ["s_lidl", "s_aldi"]
        )

        try await s.db.queue.read { conn in
            let item1Stores = try ItemStoreXref.fetchAll(conn, sql: "SELECT * FROM item_store_xref WHERE itemId = ? AND deletedAt IS NULL", arguments: [item1]).map(\.storeId)
            let item2Stores = try ItemStoreXref.fetchAll(conn, sql: "SELECT * FROM item_store_xref WHERE itemId = ? AND deletedAt IS NULL", arguments: [item2]).map(\.storeId)
            // Item1 already had lidl; union adds aldi without dropping it.
            XCTAssertEqual(Set(item1Stores), ["s_lidl", "s_aldi"])
            // Item2 had none; gets both.
            XCTAssertEqual(Set(item2Stores), ["s_lidl", "s_aldi"])
        }
    }

    func testBulkTagStoresForItemsIsIdempotent() async throws {
        let s = try setup()
        let itemId = try await s.repo.addItem(
            name: "TP", categoryId: nil, storeIds: ["s_lidl"],
            quantity: nil, notes: nil, brand: nil, imageUrl: nil,
            isStaple: false, isPriority: false
        )

        // Apply twice with the same args.
        try await s.repo.bulkTagStoresForItems(itemIds: [itemId], storeIdsToAdd: ["s_lidl"])
        try await s.repo.bulkTagStoresForItems(itemIds: [itemId], storeIdsToAdd: ["s_lidl"])

        try await s.db.queue.read { conn in
            // Still exactly one alive xref to lidl; upsert keyed on the
            // (item, store) PK can't duplicate.
            let xrefs = try ItemStoreXref.fetchAll(conn, sql: "SELECT * FROM item_store_xref WHERE itemId = ? AND deletedAt IS NULL", arguments: [itemId])
            XCTAssertEqual(xrefs.count, 1)
            XCTAssertEqual(xrefs.first?.storeId, "s_lidl")
        }
    }

    func testBulkTagStoresForItemsResurrectsTombstonedXref() async throws {
        let s = try setup()
        s.clock.now = 1_000
        let itemId = try await s.repo.addItem(
            name: "TP", categoryId: nil, storeIds: ["s_lidl"],
            quantity: nil, notes: nil, brand: nil, imageUrl: nil,
            isStaple: false, isPriority: false
        )
        s.clock.now = 2_000
        try await ItemStoreXrefDao(writer: s.db.queue).softDelete(
            householdId: "u1", itemId: itemId, storeId: "s_lidl", now: 2_000
        )

        s.clock.now = 3_000
        try await s.repo.bulkTagStoresForItems(itemIds: [itemId], storeIdsToAdd: ["s_lidl"])

        try await s.db.queue.read { conn in
            let alive = try ItemStoreXref.fetchAll(conn, sql: "SELECT * FROM item_store_xref WHERE itemId = ? AND deletedAt IS NULL", arguments: [itemId])
            XCTAssertEqual(alive.count, 1)
            XCTAssertEqual(alive.first?.storeId, "s_lidl")
            XCTAssertNil(alive.first?.deletedAt)
        }
    }

    func testBulkTagStoresForItemsWithEmptyInputsIsNoOp() async throws {
        let s = try setup()
        let itemId = try await s.repo.addItem(
            name: "TP", categoryId: nil, storeIds: ["s_lidl"],
            quantity: nil, notes: nil, brand: nil, imageUrl: nil,
            isStaple: false, isPriority: false
        )

        try await s.repo.bulkTagStoresForItems(itemIds: [], storeIdsToAdd: ["s_lidl"])
        try await s.repo.bulkTagStoresForItems(itemIds: [itemId], storeIdsToAdd: [])

        try await s.db.queue.read { conn in
            let xrefs = try ItemStoreXref.fetchAll(conn, sql: "SELECT * FROM item_store_xref WHERE itemId = ? AND deletedAt IS NULL", arguments: [itemId])
            XCTAssertEqual(xrefs.map(\.storeId), ["s_lidl"])
        }
    }

    // MARK: - Ownership

    // MARK: - v0.9.0 master-list filter (one-off stores)
    //
    // `ItemWithCategoryAndStores.fetchAll` is what `ItemDao.observeAll`
    // delegates to. The master Items list relies on this query to hide
    // items whose alive xrefs ALL point at one-off stores (one-off
    // couch, hardware shelves, etc.) so the master list stays a
    // grocery-routine signal. Mirrors Android's
    // `ItemRepositoryImplTest` filter cases.

    func testFetchAllHidesItemTaggedOnlyToOneOffStores() async throws {
        let s = try setup()
        // Reseed: add a one-off store on top of the default Lidl + Aldi.
        try s.db.seed(
            items: [TestFixtures.item(id: "i_couch", name: "Couch", userId: "u1")],
            stores: [TestFixtures.store(id: "s_hardware", name: "Hardware", userId: "u1", isOneOff: true)],
            xrefs: [TestFixtures.xref(itemId: "i_couch", storeId: "s_hardware")]
        )
        let rows = try await s.db.queue.read { conn in
            try ItemWithCategoryAndStores.fetchAll(conn, householdId: "u1")
        }
        XCTAssertEqual(rows.map(\.item.id), [],
                       "Item tagged only to a one-off store should NOT appear in the master Items list")
    }

    func testFetchAllShowsItemTaggedToMixOfOneOffAndRegularStores() async throws {
        let s = try setup()
        try s.db.seed(
            items: [TestFixtures.item(id: "i_brackets", name: "Brackets", userId: "u1")],
            stores: [TestFixtures.store(id: "s_hardware", name: "Hardware", userId: "u1", isOneOff: true)],
            xrefs: [
                TestFixtures.xref(itemId: "i_brackets", storeId: "s_hardware"),
                TestFixtures.xref(itemId: "i_brackets", storeId: "s_lidl"),  // regular store keeps it visible
            ]
        )
        let rows = try await s.db.queue.read { conn in
            try ItemWithCategoryAndStores.fetchAll(conn, householdId: "u1")
        }
        XCTAssertEqual(rows.map(\.item.id), ["i_brackets"],
                       "Mixed-tagging keeps the item visible (regular xref wins)")
    }

    func testFetchAllShowsItemWithNoAliveXrefs() async throws {
        let s = try setup()
        // Item with zero xrefs at all — common for fresh CSV imports or
        // items the user just created without picking any stores yet. The
        // filter only hides items whose alive xrefs EXIST and are ALL
        // one-off; untagged items always stay visible.
        try s.db.seed(
            items: [TestFixtures.item(id: "i_milk", name: "Milk", userId: "u1")]
        )
        let rows = try await s.db.queue.read { conn in
            try ItemWithCategoryAndStores.fetchAll(conn, householdId: "u1")
        }
        XCTAssertEqual(rows.map(\.item.id), ["i_milk"],
                       "Untagged items must stay visible — the filter only hides one-off-only items")
    }

    func testFetchAllShowsItemAfterFlippingOneOffStoreBackToRegular() async throws {
        let s = try setup()
        // Start: item tagged only to one-off "Hardware" → hidden.
        try s.db.seed(
            items: [TestFixtures.item(id: "i_lamp", name: "Lamp", userId: "u1")],
            stores: [TestFixtures.store(id: "s_hardware", name: "Hardware", userId: "u1", isOneOff: true)],
            xrefs: [TestFixtures.xref(itemId: "i_lamp", storeId: "s_hardware")]
        )
        let beforeFlip = try await s.db.queue.read { conn in
            try ItemWithCategoryAndStores.fetchAll(conn, householdId: "u1")
        }
        XCTAssertEqual(beforeFlip.map(\.item.id), [], "Hidden while only xref is one-off")

        // Flip the store back to regular — the filter is dynamic, item
        // should reappear without touching the item itself.
        try await s.db.queue.write { conn in
            try conn.execute(sql: "UPDATE stores SET isOneOff = 0 WHERE id = ?", arguments: ["s_hardware"])
        }
        let afterFlip = try await s.db.queue.read { conn in
            try ItemWithCategoryAndStores.fetchAll(conn, householdId: "u1")
        }
        XCTAssertEqual(afterFlip.map(\.item.id), ["i_lamp"],
                       "Flipping the store back to regular re-exposes the item — filter is dynamic")
    }

    func testWritesThrowWhenNotSignedIn() async throws {
        let db = try StorehopDatabase.inMemoryForTests()
        let writer = db.queue
        let repo = ItemRepository(
            writer: writer,
            itemDao: ItemDao(writer: writer),
            xrefDao: ItemStoreXrefDao(writer: writer),
            scoDao: StoreCategoryOrderDao(writer: writer),
            purchaseDao: PurchaseRecordDao(writer: writer),
            session: NoSessionProvider(),
            householdSession: LocalOnlyHouseholdSessionProvider(initialHouseholdId: nil),
            clock: SystemClock(),
            ids: UuidV4Generator()
        )

        do {
            _ = try await repo.addItem(
                name: "X", categoryId: nil, storeIds: [],
                quantity: nil, notes: nil, brand: nil, imageUrl: nil,
                isStaple: false, isPriority: false
            )
            XCTFail("Should throw NotSignedInError")
        } catch is NotSignedInError {
            // Expected
        } catch {
            XCTFail("Expected NotSignedInError, got \(error)")
        }
    }

    // MARK: - v0.9 Buy Today (#5b) + quick-add staple default (#5a)

    private func fetchItem(_ s: Setup, _ id: String) async throws -> Item? {
        try await s.db.queue.read { conn in
            try Item.fetchOne(conn, sql: "SELECT * FROM items WHERE id = ?", arguments: [id])
        }
    }

    func testMarkPurchasedAtStoreClearsIsBuyToday() async throws {
        let s = try setup()
        let id = try await s.repo.addItem(
            name: "Advil", categoryId: nil, storeIds: ["s_lidl"],
            quantity: nil, notes: nil, brand: nil, imageUrl: nil,
            isStaple: false, isPriority: false, isBuyToday: true
        )
        let before = try await fetchItem(s, id)?.isBuyToday
        XCTAssertEqual(before, true)

        _ = try await s.repo.markPurchasedAtStore(itemId: id, storeId: "s_lidl")

        let after = try await fetchItem(s, id)?.isBuyToday
        XCTAssertEqual(after, false, "Buying the item clears its Buy Today flag")
    }

    func testSetBuyTodayTogglesTheFlag() async throws {
        let s = try setup()
        let id = try await s.repo.addItem(
            name: "Advil", categoryId: nil, storeIds: ["s_lidl"],
            quantity: nil, notes: nil, brand: nil, imageUrl: nil,
            isStaple: false, isPriority: false
        )
        let initial = try await fetchItem(s, id)?.isBuyToday
        XCTAssertEqual(initial, false)

        try await s.repo.setBuyToday(itemId: id, value: true)
        let on = try await fetchItem(s, id)?.isBuyToday
        XCTAssertEqual(on, true)

        try await s.repo.setBuyToday(itemId: id, value: false)
        let off = try await fetchItem(s, id)?.isBuyToday
        XCTAssertEqual(off, false)
    }

    func testQuickAddDefaultsStapleTrueAtRegularStore() async throws {
        let s = try setup()
        let id = try await s.repo.addItemFromQuickAdd(name: "Bananas", storeId: "s_lidl")
        let staple = try await fetchItem(s, id)?.isStaple
        XCTAssertEqual(staple, true)
    }

    func testQuickAddDefaultsStapleFalseAtOneOffStore() async throws {
        let s = try setup()
        try s.db.seed(
            stores: [TestFixtures.store(id: "s_ikea", name: "IKEA", userId: "u1", isOneOff: true)]
        )
        let id = try await s.repo.addItemFromQuickAdd(name: "Lamp", storeId: "s_ikea")
        let staple = try await fetchItem(s, id)?.isStaple
        XCTAssertEqual(staple, false)
    }
}

// MARK: - Test doubles

/// Test clock with a settable instant. Tests advance `now` between
/// operations so timestamp-keyed undo paths can be verified.
final class MutableClock: Clock, @unchecked Sendable {
    private let lock = NSLock()
    private var current: Int64

    init(nowMs: Int64) { self.current = nowMs }

    var now: Int64 {
        get { lock.lock(); defer { lock.unlock() }; return current }
        set { lock.lock(); defer { lock.unlock() }; current = newValue }
    }

    func nowMs() -> Int64 { now }
}

/// Deterministic id generator for tests — yields ids in order so test
/// assertions can pin specific values when needed.
final class SequenceIdGenerator: IdGenerator, @unchecked Sendable {
    private var counter = 0
    private let lock = NSLock()
    func newId() -> String {
        lock.lock(); defer { lock.unlock() }
        counter += 1
        return "test-id-\(counter)"
    }
}

/// Always returns nil for currentUserId — used to test the
/// NotSignedInError throw path.
final class NoSessionProvider: UserSessionProvider, @unchecked Sendable {
    var currentUserId: String? { get async { nil } }
    var userIdStream: AsyncStream<String?> {
        AsyncStream { continuation in
            continuation.yield(nil)
        }
    }
}
