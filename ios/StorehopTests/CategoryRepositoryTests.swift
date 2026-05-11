import XCTest
import GRDB
@testable import Storehop

final class CategoryRepositoryTests: XCTestCase {

    private struct Setup {
        let db: StorehopDatabase
        let repo: CategoryRepository
        let clock: MutableClock
    }

    private func setup() throws -> Setup {
        let db = try StorehopDatabase.inMemoryForTests()
        let session = LocalOnlyUserSessionProvider(uid: "u1")
        let clock = MutableClock(nowMs: 1_000)
        let writer = db.queue
        let repo = CategoryRepository(
            writer: writer,
            categoryDao: CategoryDao(writer: writer),
            itemDao: ItemDao(writer: writer),
            scoDao: StoreCategoryOrderDao(writer: writer),
            session: session,
            householdSession: LocalOnlyHouseholdSessionProvider(initialHouseholdId: "u1"),
            clock: clock,
            ids: SequenceIdGenerator()
        )
        return Setup(db: db, repo: repo, clock: clock)
    }

    func testSoftDeleteCascadeClearsItemCategoryReferencesAndScoRows() async throws {
        let s = try setup()
        let categoryId = try await s.repo.addCategory(name: "Dairy")
        try s.db.seed(
            items: [TestFixtures.item(id: "i1", categoryId: categoryId)],
            stores: [TestFixtures.store(id: "s1")],
            scoOrders: [TestFixtures.sco(storeId: "s1", categoryId: categoryId)]
        )

        s.clock.now = 5_000
        try await s.repo.softDelete(id: categoryId)

        try await s.db.queue.read { conn in
            // Items pointing at the category have their categoryId cleared.
            let item = try Item.fetchOne(conn, sql: "SELECT * FROM items WHERE id = 'i1'")
            XCTAssertNil(item?.categoryId, "Item categoryId set to NULL by cascade")
            XCTAssertEqual(item?.updatedAt, 5_000, "Item updatedAt stamped to deletedAt for symmetric undo")

            // SCO rows for the category are cascaded to tombstones.
            let liveSco = try Int.fetchOne(conn, sql: """
                SELECT COUNT(*) FROM store_category_order
                WHERE categoryId = ? AND deletedAt IS NULL
                """, arguments: [categoryId]) ?? -1
            XCTAssertEqual(liveSco, 0)

            // Category itself is tombstoned.
            let cat = try Category.fetchOne(conn, sql: "SELECT * FROM categories WHERE id = ?", arguments: [categoryId])
            XCTAssertEqual(cat?.deletedAt, 5_000)
        }
    }

    func testUndoSoftDeleteRestoresItemCategoryReferencesByExactClearedAt() async throws {
        let s = try setup()
        let categoryId = try await s.repo.addCategory(name: "Dairy")
        try s.db.seed(
            items: [TestFixtures.item(id: "i1", categoryId: categoryId)]
        )
        s.clock.now = 5_000
        try await s.repo.softDelete(id: categoryId)
        s.clock.now = 6_000
        try await s.repo.undoSoftDelete(id: categoryId)

        try await s.db.queue.read { conn in
            let item = try Item.fetchOne(conn, sql: "SELECT * FROM items WHERE id = 'i1'")
            XCTAssertEqual(item?.categoryId, categoryId, "Item categoryId restored")
            let cat = try Category.fetchOne(conn, sql: "SELECT * FROM categories WHERE id = ?", arguments: [categoryId])
            XCTAssertNil(cat?.deletedAt)
        }
    }

    func testAddCategoryResurrectsTombstonedSameNameRow() async throws {
        let s = try setup()
        let originalId = try await s.repo.addCategory(name: "Dairy", icon: "Eco")
        s.clock.now = 5_000
        try await s.repo.softDelete(id: originalId)

        s.clock.now = 6_000
        let resurrected = try await s.repo.addCategory(name: "Dairy", icon: "EggAlt")
        XCTAssertEqual(resurrected, originalId)

        let cat = try await s.db.queue.read { conn in
            try Category.fetchOne(conn, sql: "SELECT * FROM categories WHERE id = ?", arguments: [originalId])
        }
        XCTAssertNil(cat?.deletedAt)
        XCTAssertEqual(cat?.icon, "EggAlt", "Resurrection takes the new icon")
    }

    func testAddCategoryThrowsOnEmptyName() async throws {
        let s = try setup()
        do {
            _ = try await s.repo.addCategory(name: " ")
            XCTFail("Should throw emptyName")
        } catch CategoryRepositoryError.emptyName {
            // Expected
        }
    }

    func testAddCategoryThrowsOnLiveDuplicate() async throws {
        let s = try setup()
        _ = try await s.repo.addCategory(name: "Dairy")
        do {
            _ = try await s.repo.addCategory(name: "Dairy")
            XCTFail("Should throw duplicateName")
        } catch CategoryRepositoryError.duplicateName(let name) {
            XCTAssertEqual(name, "Dairy")
        }
    }

    // MARK: - rename (v6 tombstone fix)

    func testRenameAllowsReusingNameOfTombstonedCategory() async throws {
        let s = try setup()
        let oldId = try await s.repo.addCategory(name: "Pets Plus")
        try await s.repo.softDelete(id: oldId)

        let pet = try await s.repo.addCategory(name: "Pet")
        // Mike's bug: pre-v6 the UNIQUE index counted the tombstoned
        // "Pets Plus" row, so renaming "Pet" → "Pets Plus" failed. After
        // v6 the tombstone doesn't block reuse.
        try await s.repo.rename(id: pet, name: "Pets Plus")

        let live = try await s.db.queue.read { conn in
            try Category.fetchAll(conn, sql: """
                SELECT * FROM categories
                WHERE name = 'Pets Plus' AND deletedAt IS NULL
                """)
        }
        XCTAssertEqual(live.count, 1)
        XCTAssertEqual(live.first?.id, pet)
    }

    func testRenameRejectsCollisionWithLiveCategory() async throws {
        let s = try setup()
        _ = try await s.repo.addCategory(name: "Bakery")
        let dairyId = try await s.repo.addCategory(name: "Dairy")

        do {
            try await s.repo.rename(id: dairyId, name: "Bakery")
            XCTFail("Expected duplicateName error against the live row")
        } catch CategoryRepositoryError.duplicateName(let n) {
            XCTAssertEqual(n, "Bakery")
        }
    }

    func testRenameAllowsCaseOnlyChangeOfOwnName() async throws {
        let s = try setup()
        let id = try await s.repo.addCategory(name: "Dairy")
        try await s.repo.rename(id: id, name: "DAIRY")

        let cat = try await s.db.queue.read { conn in
            try Category.fetchOne(conn, sql: "SELECT * FROM categories WHERE id = ?", arguments: [id])
        }
        XCTAssertEqual(cat?.name, "DAIRY")
    }
}
