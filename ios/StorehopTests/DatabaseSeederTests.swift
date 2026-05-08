import XCTest
import GRDB
@testable import Storehop

/// Mirrors the Android `DatabaseSeederTest` 1:1 — same row counts, same
/// deterministic IDs, same Lidl aisle order. If a number drifts here, it has
/// drifted on Android too and the seed pack is no longer cross-platform.
final class DatabaseSeederTests: XCTestCase {

    private func seededDatabase() throws -> StorehopDatabase {
        let db = try StorehopDatabase.inMemoryForTests()
        try DatabaseSeeder().seedIfEmpty(db.queue)
        return db
    }

    func testSeedsFourteenStoresTwentyOneCategoriesAndAtLeast70StoreCategoryOrders() throws {
        let db = try seededDatabase()
        try db.queue.read { conn in
            XCTAssertEqual(try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM stores"), 14)
            XCTAssertEqual(try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM categories"), 21)
            let scoCount = try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM store_category_order") ?? 0
            XCTAssertGreaterThanOrEqual(scoCount, 70)
        }
    }

    func testSeededStoreIdsAreDeterministicAndStable() throws {
        let db = try seededDatabase()
        let ids = try db.queue.read { conn in
            try String.fetchAll(conn, sql: "SELECT id FROM stores ORDER BY id")
        }
        XCTAssertEqual(ids, [
            "store_aldi", "store_auchan", "store_continente", "store_flavers",
            "store_glovo", "store_leroy_merlin", "store_liberty_store", "store_lidl",
            "store_mega_store", "store_normal", "store_oriental_market",
            "store_pharmacia", "store_pingo_doce", "store_wells",
        ])
    }

    func testSeededCategoryIdsIncludeTheCoreSet() throws {
        let db = try seededDatabase()
        let ids = try db.queue.read { conn in
            try String.fetchAll(conn, sql: "SELECT id FROM categories")
        }
        let core: Set<String> = [
            "cat_produce", "cat_bakery", "cat_dairy_eggs", "cat_meat_fish",
            "cat_frozen", "cat_pantry", "cat_household", "cat_other",
        ]
        XCTAssertTrue(core.isSubset(of: Set(ids)))
    }

    func testEverySeededRowCarriesIsSeededAndLocalOnlyUserId() throws {
        let db = try seededDatabase()
        try db.queue.read { conn in
            let storeBranded = try Int.fetchOne(conn, sql: """
                SELECT COUNT(*) FROM stores
                WHERE isSeeded = 1 AND userId = 'local-only'
                """)
            XCTAssertEqual(storeBranded, 14)

            let categoryBranded = try Int.fetchOne(conn, sql: """
                SELECT COUNT(*) FROM categories
                WHERE isSeeded = 1 AND userId = 'local-only'
                """)
            XCTAssertEqual(categoryBranded, 21)

            let scoBranded = try Int.fetchOne(conn, sql: """
                SELECT COUNT(*) FROM store_category_order
                WHERE isSeeded = 1 AND userId = 'local-only'
                """) ?? 0
            XCTAssertGreaterThanOrEqual(scoBranded, 70)
        }
        XCTAssertEqual(DatabaseSeeder.localOnlyUserId, "local-only")
    }

    func testEverySeededRowCarriesTheFixedSeedTimestamp() throws {
        let db = try seededDatabase()
        try db.queue.read { conn in
            for table in ["stores", "categories", "store_category_order"] {
                let drift = try Int.fetchOne(conn, sql: """
                    SELECT COUNT(*) FROM \(table)
                    WHERE createdAt != \(DatabaseSeeder.seedTimestamp)
                       OR updatedAt != \(DatabaseSeeder.seedTimestamp)
                    """) ?? -1
                XCTAssertEqual(drift, 0, "Found rows in \(table) with non-seed timestamps")
            }
        }
    }

    func testLidlAisleOrderBeginsWithProduceAtDisplayOrderZero() throws {
        let db = try seededDatabase()
        let order = try db.queue.read { conn in
            try String.fetchAll(conn, sql: """
                SELECT categoryId FROM store_category_order
                WHERE storeId = 'store_lidl' AND deletedAt IS NULL
                ORDER BY displayOrder
                """)
        }
        XCTAssertEqual(order.first, "cat_produce")
        XCTAssertEqual(Array(order.prefix(4)), [
            "cat_produce", "cat_bakery", "cat_dairy_eggs", "cat_meat_fish",
        ])
    }

    func testStoreDisplayOrderMatchesSeedJsonAuthorOrder() throws {
        let db = try seededDatabase()
        let names = try db.queue.read { conn in
            try String.fetchAll(conn, sql: """
                SELECT name FROM stores
                ORDER BY displayOrder, id
                """)
        }
        // The displayOrder is assigned from the JSON list index, so this is
        // the order the seed pack was authored in.
        XCTAssertEqual(names.first, "Aldi")
        XCTAssertEqual(names.dropFirst(2).first, "Continente")
    }

    func testSeedingIsIdempotent() throws {
        let db = try StorehopDatabase.inMemoryForTests()
        try DatabaseSeeder().seedIfEmpty(db.queue)
        try DatabaseSeeder().seedIfEmpty(db.queue)
        try DatabaseSeeder().seedIfEmpty(db.queue)
        let counts: [Int] = try db.queue.read { conn in
            [
                try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM stores") ?? -1,
                try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM categories") ?? -1,
            ]
        }
        XCTAssertEqual(counts, [14, 21])
    }
}
