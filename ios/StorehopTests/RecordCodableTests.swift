import XCTest
import GRDB
@testable import Storehop

/// Verifies each record type can round-trip through the database. If a column
/// name drifts from the schema, the insert/fetch fails here before the DAO
/// layer ever sees it.
final class RecordCodableTests: XCTestCase {

    private func freshDatabase() throws -> StorehopDatabase {
        try StorehopDatabase.inMemoryForTests()
    }

    func testStoreInsertAndFetchRoundTrip() throws {
        let db = try freshDatabase()
        let original = Store(
            id: "s1",
            name: "Lidl",
            colorArgb: nil,
            isArchived: false,
            isSeeded: false,
            userId: "u1",
            createdAt: 1_000,
            updatedAt: 1_000,
            deletedAt: nil,
            pendingSync: true,
            displayOrder: 0,
            householdId: "u1"
        )
        try db.queue.write { conn in
            var copy = original
            try copy.insert(conn)
        }
        let fetched = try db.queue.read { conn in
            try Store.fetchOne(conn, key: ["id": "s1"])
        }
        XCTAssertEqual(fetched, original)
    }

    func testCategoryInsertAndFetchRoundTrip() throws {
        let db = try freshDatabase()
        let original = Category(
            id: "cat_produce",
            name: "Produce",
            nameKey: "cat_produce",
            icon: "Eco",
            isArchived: false,
            isSeeded: true,
            userId: "u1",
            createdAt: 1_000,
            updatedAt: 1_000,
            deletedAt: nil,
            pendingSync: true,
            displayOrder: 0,
            householdId: "u1"
        )
        try db.queue.write { conn in
            var copy = original
            try copy.insert(conn)
        }
        let fetched = try db.queue.read { conn in
            try Category.fetchOne(conn, key: ["id": "cat_produce"])
        }
        XCTAssertEqual(fetched, original)
    }

    func testItemInsertAndFetchRoundTrip() throws {
        let db = try freshDatabase()
        let original = Item(
            id: "i1",
            name: "Mozzarella",
            categoryId: nil,
            notes: "Fresh, not aged",
            quantity: "2",
            isNeeded: true,
            lastPurchasedAt: nil,
            userId: "u1",
            createdAt: 1_000,
            updatedAt: 1_000,
            deletedAt: nil,
            pendingSync: true,
            brand: "Galbani",
            imageUrl: "https://example.com/mozz.jpg",
            isStaple: false,
            isPriority: true,
            householdId: "u1"
        )
        try db.queue.write { conn in
            var copy = original
            try copy.insert(conn)
        }
        let fetched = try db.queue.read { conn in
            try Item.fetchOne(conn, key: ["id": "i1"])
        }
        XCTAssertEqual(fetched, original)
    }

    func testItemStoreXrefInsertWithCompositePk() throws {
        let db = try freshDatabase()
        try db.queue.write { conn in
            try conn.execute(sql: """
                INSERT INTO stores (id, name, isArchived, isSeeded, userId, createdAt, updatedAt)
                VALUES ('s1', 'Lidl', 0, 0, 'u1', 0, 0)
                """)
            try conn.execute(sql: """
                INSERT INTO items (id, name, isNeeded, userId, createdAt, updatedAt)
                VALUES ('i1', 'Apple', 1, 'u1', 0, 0)
                """)
            var xref = ItemStoreXref(
                itemId: "i1",
                storeId: "s1",
                userId: "u1",
                createdAt: 0,
                updatedAt: 0,
                deletedAt: nil,
                pendingSync: true,
                isNeeded: true,
                lastPurchasedAt: nil,
                householdId: "u1"
            )
            try xref.insert(conn)
        }
        let fetched = try db.queue.read { conn in
            try ItemStoreXref.fetchOne(conn, key: ["itemId": "i1", "storeId": "s1"])
        }
        XCTAssertNotNil(fetched)
        XCTAssertEqual(fetched?.itemId, "i1")
        XCTAssertEqual(fetched?.storeId, "s1")
    }

    func testStoreCategoryOrderInsertWithCompositePk() throws {
        let db = try freshDatabase()
        try db.queue.write { conn in
            try conn.execute(sql: """
                INSERT INTO stores (id, name, isArchived, isSeeded, userId, createdAt, updatedAt)
                VALUES ('s1', 'Lidl', 0, 0, 'u1', 0, 0)
                """)
            try conn.execute(sql: """
                INSERT INTO categories (id, name, isArchived, isSeeded, userId, createdAt, updatedAt)
                VALUES ('c1', 'Produce', 0, 0, 'u1', 0, 0)
                """)
            var sco = StoreCategoryOrder(
                storeId: "s1",
                categoryId: "c1",
                displayOrder: 7,
                isSeeded: false,
                userId: "u1",
                createdAt: 0,
                updatedAt: 0,
                deletedAt: nil,
                pendingSync: true,
                householdId: "u1"
            )
            try sco.insert(conn)
        }
        let fetched = try db.queue.read { conn in
            try StoreCategoryOrder.fetchOne(conn, key: ["storeId": "s1", "categoryId": "c1"])
        }
        XCTAssertEqual(fetched?.displayOrder, 7)
    }

    func testPurchaseRecordInsertAndFetch() throws {
        let db = try freshDatabase()
        try db.queue.write { conn in
            try conn.execute(sql: """
                INSERT INTO items (id, name, isNeeded, userId, createdAt, updatedAt)
                VALUES ('i1', 'Apple', 1, 'u1', 0, 0)
                """)
            var record = PurchaseRecord(
                id: "p1",
                itemId: "i1",
                storeId: nil,
                purchasedAt: 12_345,
                userId: "u1",
                createdAt: 0,
                updatedAt: 0,
                deletedAt: nil,
                pendingSync: true,
                householdId: "u1"
            )
            try record.insert(conn)
        }
        let fetched = try db.queue.read { conn in
            try PurchaseRecord.fetchOne(conn, key: ["id": "p1"])
        }
        XCTAssertEqual(fetched?.purchasedAt, 12_345)
    }

    func testBoolEncodesAsIntegerOneOrZero() throws {
        let db = try freshDatabase()
        try db.queue.write { conn in
            var category = Category(
                id: "c1",
                name: "Produce",
                nameKey: nil,
                icon: nil,
                isArchived: true,
                isSeeded: false,
                userId: "u1",
                createdAt: 0,
                updatedAt: 0,
                deletedAt: nil,
                pendingSync: true,
                displayOrder: 0,
                householdId: "u1"
            )
            try category.insert(conn)
        }
        let isArchivedRaw = try db.queue.read { conn in
            try Int.fetchOne(conn, sql: "SELECT isArchived FROM categories WHERE id = 'c1'")
        }
        XCTAssertEqual(isArchivedRaw, 1, "Bool true must store as INTEGER 1")
    }
}
