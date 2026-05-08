import XCTest
import GRDB
@testable import Storehop

/// Verifies the Phase 1 migration produces the expected schema. Mirrors the
/// shape pinned in `app/schemas/com.storehop.app.data.db.StorehopDatabase/5.json`
/// (Android Room's auto-generated schema dump).
final class MigrationTests: XCTestCase {

    private func makeDatabase() throws -> StorehopDatabase {
        try StorehopDatabase.inMemoryForTests()
    }

    func testMigratorAppliesV5InitialOnFreshDatabase() throws {
        let queue = try DatabaseQueue()
        try Migrations.migrator().migrate(queue)
        let applied = try queue.read { db in
            try Migrations.migrator().appliedMigrations(db)
        }
        XCTAssertEqual(applied, ["v5_initial"])
    }

    func testAllSixTablesExistAfterMigration() throws {
        let database = try makeDatabase()
        let tables = try database.queue.read { db in
            try String.fetchAll(db, sql: """
                SELECT name FROM sqlite_master
                WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'grdb_%'
                ORDER BY name
                """)
        }
        XCTAssertEqual(Set(tables), [
            "categories",
            "items",
            "item_store_xref",
            "purchase_records",
            "store_category_order",
            "stores",
        ])
    }

    func testItemsHasSetNullForeignKeyToCategories() throws {
        let database = try makeDatabase()
        let rows = try database.queue.read { db in
            try Row.fetchAll(db, sql: "PRAGMA foreign_key_list(items)")
        }
        XCTAssertEqual(rows.count, 1)
        let table: String = rows[0]["table"]
        let onDelete: String = rows[0]["on_delete"]
        XCTAssertEqual(table, "categories")
        XCTAssertEqual(onDelete, "SET NULL")
    }

    func testItemStoreXrefHasCascadingForeignKeys() throws {
        let database = try makeDatabase()
        let descriptors: Set<String> = try database.queue.read { db in
            let rows = try Row.fetchAll(db, sql: "PRAGMA foreign_key_list(item_store_xref)")
            return Set(rows.map { row in
                let table: String = row["table"]
                let onDelete: String = row["on_delete"]
                return "\(table) ON DELETE \(onDelete)"
            })
        }
        XCTAssertEqual(descriptors, [
            "items ON DELETE CASCADE",
            "stores ON DELETE CASCADE",
        ])
    }

    func testItemStoreXrefHasCompositePrimaryKey() throws {
        try assertCompositePrimaryKey(
            table: "item_store_xref",
            equals: ["itemId", "storeId"]
        )
    }

    func testStoreCategoryOrderHasCompositePrimaryKey() throws {
        try assertCompositePrimaryKey(
            table: "store_category_order",
            equals: ["storeId", "categoryId"]
        )
    }

    func testStoresHasUniqueIndexOnUserIdName() throws {
        let database = try makeDatabase()
        try database.queue.write { db in
            try db.execute(sql: """
                INSERT INTO stores (id, name, isArchived, isSeeded, userId, createdAt, updatedAt)
                VALUES ('a', 'Lidl', 0, 0, 'u1', 0, 0)
                """)
            XCTAssertThrowsError(try db.execute(sql: """
                INSERT INTO stores (id, name, isArchived, isSeeded, userId, createdAt, updatedAt)
                VALUES ('b', 'Lidl', 0, 0, 'u1', 0, 0)
                """))
        }
    }

    func testCategoriesHasUniqueIndexOnUserIdName() throws {
        let database = try makeDatabase()
        try database.queue.write { db in
            try db.execute(sql: """
                INSERT INTO categories (id, name, isArchived, isSeeded, userId, createdAt, updatedAt)
                VALUES ('a', 'Produce', 0, 0, 'u1', 0, 0)
                """)
            XCTAssertThrowsError(try db.execute(sql: """
                INSERT INTO categories (id, name, isArchived, isSeeded, userId, createdAt, updatedAt)
                VALUES ('b', 'Produce', 0, 0, 'u1', 0, 0)
                """))
        }
    }

    func testForeignKeysAreEnforced() throws {
        let database = try makeDatabase()
        try database.queue.read { db in
            let pragma = try Int.fetchOne(db, sql: "PRAGMA foreign_keys") ?? 0
            XCTAssertEqual(pragma, 1, "Foreign keys must be enforced")
        }
    }

    func testItemsCategoryFkSetsNullOnDelete() throws {
        let database = try makeDatabase()
        try database.queue.write { db in
            try db.execute(sql: """
                INSERT INTO categories (id, name, isArchived, isSeeded, userId, createdAt, updatedAt)
                VALUES ('cat_x', 'Cat X', 0, 0, 'u1', 0, 0)
                """)
            try db.execute(sql: """
                INSERT INTO items (id, name, categoryId, isNeeded, userId, createdAt, updatedAt)
                VALUES ('it_x', 'Apple', 'cat_x', 1, 'u1', 0, 0)
                """)
            try db.execute(sql: "DELETE FROM categories WHERE id = 'cat_x'")
            let row = try Row.fetchOne(db, sql: "SELECT categoryId FROM items WHERE id = 'it_x'")
            XCTAssertNotNil(row, "Item row should still exist after FK SET NULL")
            let categoryId: String? = row?["categoryId"]
            XCTAssertNil(categoryId, "items.categoryId should be NULL after parent category hard-delete")
        }
    }

    func testItemStoreXrefCascadesWhenItemHardDeleted() throws {
        let database = try makeDatabase()
        try database.queue.write { db in
            try db.execute(sql: """
                INSERT INTO stores (id, name, isArchived, isSeeded, userId, createdAt, updatedAt)
                VALUES ('s1', 'Lidl', 0, 0, 'u1', 0, 0)
                """)
            try db.execute(sql: """
                INSERT INTO items (id, name, isNeeded, userId, createdAt, updatedAt)
                VALUES ('i1', 'Bread', 1, 'u1', 0, 0)
                """)
            try db.execute(sql: """
                INSERT INTO item_store_xref (itemId, storeId, userId, createdAt, updatedAt)
                VALUES ('i1', 's1', 'u1', 0, 0)
                """)
            try db.execute(sql: "DELETE FROM items WHERE id = 'i1'")
            let count = try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM item_store_xref") ?? -1
            XCTAssertEqual(count, 0, "Hard-delete of item should CASCADE to xref rows")
        }
    }

    // MARK: - Helpers

    private func assertCompositePrimaryKey(
        table: String,
        equals expected: [String],
        file: StaticString = #filePath,
        line: UInt = #line
    ) throws {
        let database = try makeDatabase()
        let pkColumns: [String] = try database.queue.read { db in
            let rows = try Row.fetchAll(db, sql: "PRAGMA table_info(\(table))")
            return rows
                .compactMap { row -> (Int, String)? in
                    let pk: Int = row["pk"]
                    guard pk > 0 else { return nil }
                    let name: String = row["name"]
                    return (pk, name)
                }
                .sorted { $0.0 < $1.0 }
                .map { $0.1 }
        }
        XCTAssertEqual(pkColumns, expected, file: file, line: line)
    }
}
