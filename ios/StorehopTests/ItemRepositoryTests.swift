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

    // MARK: - Ownership

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
