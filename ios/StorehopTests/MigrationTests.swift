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

    func testMigratorAppliesAllMigrationsOnFreshDatabase() throws {
        let queue = try DatabaseQueue()
        try Migrations.migrator().migrate(queue)
        let applied = try queue.read { db in
            try Migrations.migrator().appliedMigrations(db)
        }
        XCTAssertEqual(applied, [
            "v5_initial",
            "v6_drop_unique_name_indexes",
            "v7_categories_display_order",
            "v8_household_scope",
            "v9_stores_one_off",
        ])
    }

    func testAllSevenTablesExistAfterMigration() throws {
        let database = try makeDatabase()
        let tables = try database.queue.read { db in
            try String.fetchAll(db, sql: """
                SELECT name FROM sqlite_master
                WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'grdb_%'
                ORDER BY name
                """)
        }
        // v0.7.0 adds household_members → seven tables total.
        XCTAssertEqual(Set(tables), [
            "categories",
            "items",
            "item_store_xref",
            "household_members",
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

    func testStoresAllowsDuplicateUserIdNamePostV6() throws {
        // Schema v6 dropped the UNIQUE constraint on (userId, name). Two
        // rows with the same name must coexist at the schema level so a
        // tombstoned row doesn't block renaming a live row to the same
        // name. The repository layer enforces "no two LIVE rows" via an
        // alive-only collision check on rename / addStore.
        let database = try makeDatabase()
        try database.queue.write { db in
            try db.execute(sql: """
                INSERT INTO stores (id, name, isArchived, isSeeded, userId, createdAt, updatedAt)
                VALUES ('a', 'Lidl', 0, 0, 'u1', 0, 0)
                """)
            XCTAssertNoThrow(try db.execute(sql: """
                INSERT INTO stores (id, name, isArchived, isSeeded, userId, createdAt, updatedAt)
                VALUES ('b', 'Lidl', 0, 0, 'u1', 0, 0)
                """))
        }
    }

    func testCategoriesAllowsDuplicateUserIdNamePostV6() throws {
        let database = try makeDatabase()
        try database.queue.write { db in
            try db.execute(sql: """
                INSERT INTO categories (id, name, isArchived, isSeeded, userId, createdAt, updatedAt)
                VALUES ('a', 'Produce', 0, 0, 'u1', 0, 0)
                """)
            XCTAssertNoThrow(try db.execute(sql: """
                INSERT INTO categories (id, name, isArchived, isSeeded, userId, createdAt, updatedAt)
                VALUES ('b', 'Produce', 0, 0, 'u1', 0, 0)
                """))
        }
    }

    // MARK: - v9_stores_one_off (one-off stores)

    func testV9AddsIsOneOffColumnToStoresWithDefaultZero() throws {
        let database = try makeDatabase()
        try database.queue.read { db in
            let cols = try Row.fetchAll(db, sql: "PRAGMA table_info(stores)")
            let isOneOff = cols.first { ($0["name"] as String?) == "isOneOff" }
            XCTAssertNotNil(isOneOff, "v9_stores_one_off must add the `isOneOff` column")
            let type: String? = isOneOff?["type"]
            XCTAssertEqual(type, "INTEGER", "isOneOff must be INTEGER (Bool maps to INTEGER in SQLite)")
            let notNull: Int? = isOneOff?["notnull"]
            XCTAssertEqual(notNull, 1, "isOneOff must be NOT NULL")
            let defaultValue: String? = isOneOff?["dflt_value"]
            XCTAssertEqual(defaultValue, "0",
                           "isOneOff must default to 0 — pre-v0.9 rows backfill as regular stores")
        }
    }

    func testV9CreatesCompositeIndexOnStoresHouseholdIdIsOneOff() throws {
        // The EXISTS subquery in `ItemWithCategoryAndStores.fetchAll`
        // joins `item_store_xref` → `stores` filtered by `householdId`
        // and `isOneOff = 0`. The composite index keeps that scan
        // sargable as the household's store count grows.
        let database = try makeDatabase()
        try database.queue.read { db in
            let rows = try Row.fetchAll(db, sql: "PRAGMA index_list(stores)")
            let entry = rows.first { ($0["name"] as String?) == "index_stores_householdId_isOneOff" }
            XCTAssertNotNil(entry, "v9_stores_one_off must create index_stores_householdId_isOneOff")
            let unique: Int? = entry?["unique"]
            XCTAssertEqual(unique, 0,
                           "The composite (householdId, isOneOff) index must be non-unique")

            let info = try Row.fetchAll(
                db,
                sql: "PRAGMA index_info(index_stores_householdId_isOneOff)"
            )
            let cols = info.compactMap { $0["name"] as String? }
            XCTAssertEqual(cols, ["householdId", "isOneOff"],
                           "Composite index columns must be (householdId, isOneOff) in that order")
        }
    }

    func testV6IndexesAreNonUnique() throws {
        let database = try makeDatabase()
        try database.queue.read { db in
            for (table, expectedIndexName) in [
                ("categories", "index_categories_userId_name"),
                ("stores", "index_stores_userId_name"),
            ] {
                let rows = try Row.fetchAll(db, sql: "PRAGMA index_list(\(table))")
                let entry = rows.first { ($0["name"] as String?) == expectedIndexName }
                XCTAssertNotNil(entry, "Index \(expectedIndexName) must exist on \(table)")
                let unique: Int? = entry?["unique"]
                XCTAssertEqual(unique, 0,
                               "Post-v6 the \(table)(userId, name) index must be non-unique")
            }
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

    // MARK: - v7 → v8 backfill (household scope)

    /// Mirrors Android `MigrationTest.v7 to v8 backfills householdId ...`.
    /// Pins the v0.7.0 scaffolding migration: alive rows get
    /// `householdId = userId`, tombstones stay at the empty-string default,
    /// and the new `household_members` table comes up empty.
    func testV7ToV8BackfillsHouseholdIdEqualsUserIdOnAliveRows() throws {
        let queue = try DatabaseQueue()
        let migrator = Migrations.migrator()
        try migrator.migrate(queue, upTo: "v7_categories_display_order")

        // Seed v7-schema rows directly (no householdId column yet).
        try queue.write { db in
            try db.execute(sql: """
                INSERT INTO categories
                    (id, name, isArchived, isSeeded, userId, createdAt, updatedAt, pendingSync, displayOrder)
                VALUES ('c1', 'Produce', 0, 0, 'u1', 1, 1, 0, 0)
                """)
            try db.execute(sql: """
                INSERT INTO stores
                    (id, name, isArchived, isSeeded, userId, createdAt, updatedAt, pendingSync, displayOrder)
                VALUES ('s1', 'Lidl', 0, 0, 'u1', 1, 1, 0, 0)
                """)
            try db.execute(sql: """
                INSERT INTO items
                    (id, name, isNeeded, userId, createdAt, updatedAt, pendingSync, isStaple, isPriority)
                VALUES ('i1', 'Bread', 1, 'u1', 1, 1, 0, 0, 0)
                """)
            // Dead row — should stay at the empty-string default after v8.
            try db.execute(sql: """
                INSERT INTO items
                    (id, name, isNeeded, userId, createdAt, updatedAt, deletedAt, pendingSync, isStaple, isPriority)
                VALUES ('i_dead', 'Old', 1, 'u1', 1, 1, 999, 0, 0, 0)
                """)
        }

        // Apply v8.
        try migrator.migrate(queue)

        try queue.read { db in
            // Alive item: householdId = userId, pendingSync bumped so the
            // new column ships to Firestore on the next push.
            let aliveItem = try Row.fetchOne(
                db,
                sql: "SELECT householdId, pendingSync FROM items WHERE id = 'i1'"
            )
            XCTAssertEqual(aliveItem?["householdId"] as String?, "u1")
            XCTAssertEqual(aliveItem?["pendingSync"] as Int?, 1)

            let aliveStore = try Row.fetchOne(
                db,
                sql: "SELECT householdId FROM stores WHERE id = 's1'"
            )
            XCTAssertEqual(aliveStore?["householdId"] as String?, "u1")

            let aliveCat = try Row.fetchOne(
                db,
                sql: "SELECT householdId FROM categories WHERE id = 'c1'"
            )
            XCTAssertEqual(aliveCat?["householdId"] as String?, "u1")

            // Tombstone left at empty-string default (the WHERE clause in
            // the backfill skips deletedAt-NOT-NULL rows).
            let deadItem = try Row.fetchOne(
                db,
                sql: "SELECT householdId FROM items WHERE id = 'i_dead'"
            )
            XCTAssertEqual(deadItem?["householdId"] as String?, "")

            // New household_members table exists and is empty — bootstrap
            // happens at first launch in `FirebaseAuthSessionProvider`,
            // not inside the migration.
            let memberCount = try Int.fetchOne(
                db,
                sql: "SELECT COUNT(*) FROM household_members"
            ) ?? -1
            XCTAssertEqual(memberCount, 0)
        }
    }

    /// Pins that v8 lands `householdId` on every household-owned table —
    /// not just items/stores/categories. A regression that skipped one of
    /// xref/sco/purchase_records would leak access scope (Mike's purchases
    /// visible to Amanda) once Amanda joins via invite.
    func testV8AddsHouseholdIdToAllSixEntityTables() throws {
        let queue = try DatabaseQueue()
        try Migrations.migrator().migrate(queue)
        try queue.read { db in
            for table in [
                "items", "stores", "categories",
                "item_store_xref", "store_category_order", "purchase_records",
            ] {
                let rows = try Row.fetchAll(db, sql: "PRAGMA table_info(\(table))")
                let names = rows.compactMap { $0["name"] as String? }
                XCTAssertTrue(
                    names.contains("householdId"),
                    "\(table) missing householdId column after v8"
                )
            }
        }
    }

    /// Pins the index on `(householdId)` for sargable WHERE-by-household
    /// queries. Without it, every list query degrades to a table scan once
    /// households share a userId namespace.
    func testV8CreatesHouseholdIdIndexesOnAllSixTables() throws {
        let queue = try DatabaseQueue()
        try Migrations.migrator().migrate(queue)
        try queue.read { db in
            for table in [
                "items", "stores", "categories",
                "item_store_xref", "store_category_order", "purchase_records",
            ] {
                let rows = try Row.fetchAll(db, sql: "PRAGMA index_list(\(table))")
                let names = rows.compactMap { $0["name"] as String? }
                XCTAssertTrue(
                    names.contains("index_\(table)_householdId"),
                    "\(table) missing householdId index after v8"
                )
            }
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
