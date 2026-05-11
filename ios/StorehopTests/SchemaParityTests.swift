import XCTest
import GRDB
@testable import Storehop

/// Pins the iOS schema to byte-shape parity with Android's Room dump.
/// Currently pinned to schema v8 (the v0.7.0 multi-user release that
/// added a `householdId` column to all six entity tables + a new
/// `household_members` table). Source of truth is
/// `app/schemas/com.storehop.app.data.db.StorehopDatabase/8.json`.
///
/// If Android adds, removes, or renames a column without a matching iOS
/// migration, one of these assertions fails. When Android ships v9,
/// append a v9 migration in `Migrations.swift` and update the
/// corresponding expected dictionaries.
final class SchemaParityTests: XCTestCase {

    private struct ColumnSpec: Equatable {
        let name: String
        let type: String
        let notNull: Bool
        let defaultValue: String?
        let pkPosition: Int  // 0 = not part of PK
    }

    func testItemsTableMatchesAndroidSchemaV8() throws {
        try assertColumns(
            table: "items",
            expected: [
                ColumnSpec(name: "id",              type: "TEXT",    notNull: true,  defaultValue: nil, pkPosition: 1),
                ColumnSpec(name: "name",            type: "TEXT",    notNull: true,  defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "categoryId",      type: "TEXT",    notNull: false, defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "notes",           type: "TEXT",    notNull: false, defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "quantity",        type: "TEXT",    notNull: false, defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "isNeeded",        type: "INTEGER", notNull: true,  defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "lastPurchasedAt", type: "INTEGER", notNull: false, defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "userId",          type: "TEXT",    notNull: true,  defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "createdAt",       type: "INTEGER", notNull: true,  defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "updatedAt",       type: "INTEGER", notNull: true,  defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "deletedAt",       type: "INTEGER", notNull: false, defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "pendingSync",     type: "INTEGER", notNull: true,  defaultValue: "1", pkPosition: 0),
                ColumnSpec(name: "brand",           type: "TEXT",    notNull: false, defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "imageUrl",        type: "TEXT",    notNull: false, defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "isStaple",        type: "INTEGER", notNull: true,  defaultValue: "0", pkPosition: 0),
                ColumnSpec(name: "isPriority",      type: "INTEGER", notNull: true,  defaultValue: "0", pkPosition: 0),
                ColumnSpec(name: "householdId",     type: "TEXT",    notNull: true,  defaultValue: "''", pkPosition: 0),
            ]
        )
    }

    func testCategoriesTableMatchesAndroidSchemaV8() throws {
        try assertColumns(
            table: "categories",
            expected: [
                ColumnSpec(name: "id",           type: "TEXT",    notNull: true,  defaultValue: nil, pkPosition: 1),
                ColumnSpec(name: "name",         type: "TEXT",    notNull: true,  defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "nameKey",      type: "TEXT",    notNull: false, defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "icon",         type: "TEXT",    notNull: false, defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "isArchived",   type: "INTEGER", notNull: true,  defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "isSeeded",     type: "INTEGER", notNull: true,  defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "userId",       type: "TEXT",    notNull: true,  defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "createdAt",    type: "INTEGER", notNull: true,  defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "updatedAt",    type: "INTEGER", notNull: true,  defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "deletedAt",    type: "INTEGER", notNull: false, defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "pendingSync",  type: "INTEGER", notNull: true,  defaultValue: "1", pkPosition: 0),
                ColumnSpec(name: "displayOrder", type: "INTEGER", notNull: true,  defaultValue: "0", pkPosition: 0),
                ColumnSpec(name: "householdId",  type: "TEXT",    notNull: true,  defaultValue: "''", pkPosition: 0),
            ]
        )
    }

    func testStoresTableMatchesAndroidSchemaV8() throws {
        try assertColumns(
            table: "stores",
            expected: [
                ColumnSpec(name: "id",           type: "TEXT",    notNull: true,  defaultValue: nil, pkPosition: 1),
                ColumnSpec(name: "name",         type: "TEXT",    notNull: true,  defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "colorArgb",    type: "INTEGER", notNull: false, defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "isArchived",   type: "INTEGER", notNull: true,  defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "isSeeded",     type: "INTEGER", notNull: true,  defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "userId",       type: "TEXT",    notNull: true,  defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "createdAt",    type: "INTEGER", notNull: true,  defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "updatedAt",    type: "INTEGER", notNull: true,  defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "deletedAt",    type: "INTEGER", notNull: false, defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "pendingSync",  type: "INTEGER", notNull: true,  defaultValue: "1", pkPosition: 0),
                ColumnSpec(name: "displayOrder", type: "INTEGER", notNull: true,  defaultValue: "0", pkPosition: 0),
                ColumnSpec(name: "householdId",  type: "TEXT",    notNull: true,  defaultValue: "''", pkPosition: 0),
            ]
        )
    }

    func testItemStoreXrefTableMatchesAndroidSchemaV8() throws {
        try assertColumns(
            table: "item_store_xref",
            expected: [
                ColumnSpec(name: "itemId",          type: "TEXT",    notNull: true,  defaultValue: nil, pkPosition: 1),
                ColumnSpec(name: "storeId",         type: "TEXT",    notNull: true,  defaultValue: nil, pkPosition: 2),
                ColumnSpec(name: "userId",          type: "TEXT",    notNull: true,  defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "createdAt",       type: "INTEGER", notNull: true,  defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "updatedAt",       type: "INTEGER", notNull: true,  defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "deletedAt",       type: "INTEGER", notNull: false, defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "pendingSync",     type: "INTEGER", notNull: true,  defaultValue: "1", pkPosition: 0),
                ColumnSpec(name: "isNeeded",        type: "INTEGER", notNull: true,  defaultValue: "1", pkPosition: 0),
                ColumnSpec(name: "lastPurchasedAt", type: "INTEGER", notNull: false, defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "householdId",     type: "TEXT",    notNull: true,  defaultValue: "''", pkPosition: 0),
            ]
        )
    }

    func testStoreCategoryOrderTableMatchesAndroidSchemaV8() throws {
        try assertColumns(
            table: "store_category_order",
            expected: [
                ColumnSpec(name: "storeId",      type: "TEXT",    notNull: true,  defaultValue: nil, pkPosition: 1),
                ColumnSpec(name: "categoryId",   type: "TEXT",    notNull: true,  defaultValue: nil, pkPosition: 2),
                ColumnSpec(name: "displayOrder", type: "INTEGER", notNull: true,  defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "isSeeded",     type: "INTEGER", notNull: true,  defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "userId",       type: "TEXT",    notNull: true,  defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "createdAt",    type: "INTEGER", notNull: true,  defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "updatedAt",    type: "INTEGER", notNull: true,  defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "deletedAt",    type: "INTEGER", notNull: false, defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "pendingSync",  type: "INTEGER", notNull: true,  defaultValue: "1", pkPosition: 0),
                ColumnSpec(name: "householdId",  type: "TEXT",    notNull: true,  defaultValue: "''", pkPosition: 0),
            ]
        )
    }

    func testPurchaseRecordsTableMatchesAndroidSchemaV8() throws {
        try assertColumns(
            table: "purchase_records",
            expected: [
                ColumnSpec(name: "id",          type: "TEXT",    notNull: true,  defaultValue: nil, pkPosition: 1),
                ColumnSpec(name: "itemId",      type: "TEXT",    notNull: true,  defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "storeId",     type: "TEXT",    notNull: false, defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "purchasedAt", type: "INTEGER", notNull: true,  defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "userId",      type: "TEXT",    notNull: true,  defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "createdAt",   type: "INTEGER", notNull: true,  defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "updatedAt",   type: "INTEGER", notNull: true,  defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "deletedAt",   type: "INTEGER", notNull: false, defaultValue: nil, pkPosition: 0),
                ColumnSpec(name: "pendingSync", type: "INTEGER", notNull: true,  defaultValue: "1", pkPosition: 0),
                ColumnSpec(name: "householdId", type: "TEXT",    notNull: true,  defaultValue: "''", pkPosition: 0),
            ]
        )
    }

    // MARK: - Helper

    private func assertColumns(
        table: String,
        expected: [ColumnSpec],
        file: StaticString = #filePath,
        line: UInt = #line
    ) throws {
        let database = try StorehopDatabase.inMemoryForTests()
        let observed: [ColumnSpec] = try database.queue.read { db in
            try Row.fetchAll(db, sql: "PRAGMA table_info(\(table))").map { row in
                let name: String = row["name"]
                let type: String = row["type"]
                let notNull: Int = row["notnull"]
                let dflt: String? = row["dflt_value"]
                let pk: Int = row["pk"]
                return ColumnSpec(
                    name: name,
                    type: type,
                    notNull: notNull == 1,
                    defaultValue: dflt,
                    pkPosition: pk
                )
            }
        }
        XCTAssertEqual(
            observed,
            expected,
            "Schema drift on \(table): see comment in SchemaParityTests.swift",
            file: file,
            line: line
        )
    }
}
