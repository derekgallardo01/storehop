import XCTest
import GRDB
@testable import Storehop

final class StoreRepositoryTests: XCTestCase {

    private struct Setup {
        let db: StorehopDatabase
        let repo: StoreRepository
        let clock: MutableClock
    }

    private func setup() throws -> Setup {
        let db = try StorehopDatabase.inMemoryForTests()
        let session = LocalOnlyUserSessionProvider(uid: "u1")
        let clock = MutableClock(nowMs: 1_000)
        let writer = db.queue
        let repo = StoreRepository(
            writer: writer,
            storeDao: StoreDao(writer: writer),
            xrefDao: ItemStoreXrefDao(writer: writer),
            scoDao: StoreCategoryOrderDao(writer: writer),
            session: session,
            householdSession: LocalOnlyHouseholdSessionProvider(initialHouseholdId: "u1"),
            clock: clock,
            ids: SequenceIdGenerator()
        )
        return Setup(db: db, repo: repo, clock: clock)
    }

    func testAddStoreInsertsAtNextDisplayOrder() async throws {
        let s = try setup()
        let id1 = try await s.repo.addStore(name: "Lidl")
        s.clock.now = 2_000
        let id2 = try await s.repo.addStore(name: "Aldi")

        try await s.db.queue.read { conn in
            let lidl = try Store.fetchOne(conn, sql: "SELECT * FROM stores WHERE id = ?", arguments: [id1])
            XCTAssertEqual(lidl?.displayOrder, 0)
            let aldi = try Store.fetchOne(conn, sql: "SELECT * FROM stores WHERE id = ?", arguments: [id2])
            XCTAssertEqual(aldi?.displayOrder, 1)
        }
    }

    func testAddStoreThrowsOnLiveDuplicate() async throws {
        let s = try setup()
        _ = try await s.repo.addStore(name: "Lidl")
        do {
            _ = try await s.repo.addStore(name: "Lidl")
            XCTFail("Should throw duplicate error")
        } catch StoreRepositoryError.duplicateName(let name) {
            XCTAssertEqual(name, "Lidl")
        }
    }

    func testAddStoreThrowsOnEmptyName() async throws {
        let s = try setup()
        do {
            _ = try await s.repo.addStore(name: "  ")
            XCTFail("Should throw emptyName")
        } catch StoreRepositoryError.emptyName {
            // Expected
        }
    }

    func testAddStoreResurrectsTombstonedRowKeepingOriginalIdAndDisplayOrder() async throws {
        let s = try setup()
        let originalId = try await s.repo.addStore(name: "Lidl")
        s.clock.now = 2_000
        try await s.repo.softDelete(id: originalId)

        s.clock.now = 3_000
        let resurrectedId = try await s.repo.addStore(name: "Lidl")
        XCTAssertEqual(resurrectedId, originalId, "Re-add of same name resurrects the original row")

        let store = try await s.db.queue.read { conn in
            try Store.fetchOne(conn, sql: "SELECT * FROM stores WHERE id = ?", arguments: [originalId])
        }
        XCTAssertNil(store?.deletedAt, "deletedAt cleared")
        XCTAssertEqual(store?.displayOrder, 0, "Original displayOrder preserved on resurrection")
        XCTAssertTrue(store?.pendingSync ?? false)
    }

    func testSoftDeleteCascadesToXrefsAndScoRows() async throws {
        let s = try setup()
        let storeId = try await s.repo.addStore(name: "Lidl")
        try s.db.seed(
            items: [TestFixtures.item(id: "i1")],
            categories: [TestFixtures.category(id: "c1")],
            xrefs: [TestFixtures.xref(itemId: "i1", storeId: storeId)],
            scoOrders: [TestFixtures.sco(storeId: storeId, categoryId: "c1")]
        )
        s.clock.now = 5_000
        try await s.repo.softDelete(id: storeId)

        try await s.db.queue.read { conn in
            let liveXrefs = try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM item_store_xref WHERE storeId = ? AND deletedAt IS NULL", arguments: [storeId]) ?? -1
            XCTAssertEqual(liveXrefs, 0)
            let liveSco = try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM store_category_order WHERE storeId = ? AND deletedAt IS NULL", arguments: [storeId]) ?? -1
            XCTAssertEqual(liveSco, 0)
            let store = try Store.fetchOne(conn, sql: "SELECT * FROM stores WHERE id = ?", arguments: [storeId])
            XCTAssertEqual(store?.deletedAt, 5_000)
        }
    }

    func testUndoSoftDeleteRestoresAllCascadedRows() async throws {
        let s = try setup()
        let storeId = try await s.repo.addStore(name: "Lidl")
        try s.db.seed(
            items: [TestFixtures.item(id: "i1")],
            xrefs: [TestFixtures.xref(itemId: "i1", storeId: storeId)]
        )
        s.clock.now = 5_000
        try await s.repo.softDelete(id: storeId)
        s.clock.now = 6_000
        try await s.repo.undoSoftDelete(id: storeId)

        try await s.db.queue.read { conn in
            let liveXrefs = try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM item_store_xref WHERE storeId = ? AND deletedAt IS NULL", arguments: [storeId]) ?? -1
            XCTAssertEqual(liveXrefs, 1, "Cascaded xref restored")
            let store = try Store.fetchOne(conn, sql: "SELECT * FROM stores WHERE id = ?", arguments: [storeId])
            XCTAssertNil(store?.deletedAt)
        }
    }

    func testReorderStoresAtomicallyUpdatesAllDisplayOrders() async throws {
        let s = try setup()
        let a = try await s.repo.addStore(name: "Aldi")
        let b = try await s.repo.addStore(name: "Lidl")
        let c = try await s.repo.addStore(name: "Pingo Doce")

        try await s.repo.reorderStores(orderedIds: [c, a, b])

        let names = try await s.db.queue.read { conn in
            try String.fetchAll(conn, sql: "SELECT name FROM stores ORDER BY displayOrder")
        }
        XCTAssertEqual(names, ["Pingo Doce", "Aldi", "Lidl"])
    }

    // MARK: - rename (v6 tombstone fix)

    func testRenameAllowsReusingNameOfTombstonedStore() async throws {
        let s = try setup()
        let oldId = try await s.repo.addStore(name: "Pets Plus")
        try await s.repo.softDelete(id: oldId)

        let pet = try await s.repo.addStore(name: "Pet")
        // Pre-v6 this throws because the UNIQUE(userId, name) index counts
        // the tombstoned "Pets Plus" row. After v6 the tombstone doesn't
        // block reuse — repo's alive-only collision check passes.
        try await s.repo.rename(id: pet, name: "Pets Plus")

        let live = try await s.db.queue.read { conn in
            try Store.fetchAll(conn, sql: """
                SELECT * FROM stores
                WHERE name = 'Pets Plus' AND deletedAt IS NULL
                """)
        }
        XCTAssertEqual(live.count, 1)
        XCTAssertEqual(live.first?.id, pet)
    }

    func testRenameRejectsCollisionWithLiveStore() async throws {
        let s = try setup()
        _ = try await s.repo.addStore(name: "Aldi")
        let lidlId = try await s.repo.addStore(name: "Lidl")

        do {
            try await s.repo.rename(id: lidlId, name: "Aldi")
            XCTFail("Expected duplicateName error against the live row")
        } catch StoreRepositoryError.duplicateName(let n) {
            XCTAssertEqual(n, "Aldi")
        }
    }

    func testRenameAllowsCaseOnlyChangeOfOwnName() async throws {
        let s = try setup()
        let id = try await s.repo.addStore(name: "Lidl")
        try await s.repo.rename(id: id, name: "LIDL")

        let store = try await s.db.queue.read { conn in
            try Store.fetchOne(conn, sql: "SELECT * FROM stores WHERE id = ?", arguments: [id])
        }
        XCTAssertEqual(store?.name, "LIDL")
    }
}
