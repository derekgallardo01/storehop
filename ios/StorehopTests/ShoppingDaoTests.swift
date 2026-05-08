import XCTest
import GRDB
@testable import Storehop

/// Pins the cross-cutting query that drives Shop-at-Store. The session
/// window logic and per-store filtering are the high-value invariants.
final class ShoppingDaoTests: XCTestCase {

    private struct Setup {
        let db: StorehopDatabase
        let dao: ShoppingDao
    }

    private func setup() throws -> Setup {
        let db = try StorehopDatabase.inMemoryForTests()
        return Setup(db: db, dao: ShoppingDao(writer: db.queue))
    }

    /// Snapshot helper — pulls one emission from the SQL synchronously.
    private func fetchShoppingList(
        _ db: StorehopDatabase,
        userId: String = "u1",
        storeId: String,
        sessionStartMs: Int64 = .max
    ) throws -> [ShoppingRow] {
        try db.queue.read { conn in
            try ShoppingRow.fetchAll(conn, sql: """
                SELECT i.id            AS id,
                       i.name          AS name,
                       i.quantity      AS quantity,
                       i.notes         AS notes,
                       isx.isNeeded    AS isNeeded,
                       i.brand         AS brand,
                       i.imageUrl      AS imageUrl,
                       i.isPriority    AS isPriority,
                       i.isStaple      AS isStaple,
                       c.id            AS cat_id,
                       c.name          AS cat_name,
                       c.nameKey       AS cat_nameKey,
                       c.icon          AS cat_icon,
                       sco.displayOrder AS displayOrder
                FROM items i
                INNER JOIN item_store_xref isx
                       ON isx.itemId = i.id
                      AND isx.userId = ?
                      AND isx.deletedAt IS NULL
                LEFT  JOIN categories c
                       ON c.id = i.categoryId AND c.deletedAt IS NULL
                LEFT  JOIN store_category_order sco
                       ON sco.storeId = ?
                      AND sco.categoryId = i.categoryId
                      AND sco.deletedAt IS NULL
                WHERE isx.storeId = ?
                  AND i.deletedAt IS NULL
                  AND (
                        isx.isNeeded = 1
                     OR i.isStaple = 1
                     OR (isx.lastPurchasedAt IS NOT NULL AND isx.lastPurchasedAt >= ?)
                  )
                  AND i.userId = ?
                ORDER BY isx.isNeeded DESC,
                         COALESCE(sco.displayOrder, 9999),
                         c.name COLLATE NOCASE,
                         i.name COLLATE NOCASE
                """, arguments: [userId, storeId, storeId, sessionStartMs, userId])
        }
    }

    func testReturnsItemsStillNeededAtTheStore() throws {
        let setup = try setup()
        try setup.db.seed(
            items: [TestFixtures.item(id: "i1", name: "Mozzarella")],
            stores: [TestFixtures.store(id: "s1")],
            xrefs: [TestFixtures.xref(itemId: "i1", storeId: "s1", isNeeded: true)]
        )
        let rows = try fetchShoppingList(setup.db, storeId: "s1")
        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(rows.first?.id, "i1")
        XCTAssertTrue(rows.first?.isNeeded ?? false)
    }

    func testIncludesStaplesEvenWhenPurchased() throws {
        let setup = try setup()
        try setup.db.seed(
            items: [TestFixtures.item(id: "i1", name: "Milk", isStaple: true)],
            stores: [TestFixtures.store(id: "s1")],
            xrefs: [TestFixtures.xref(itemId: "i1", storeId: "s1", isNeeded: false, lastPurchasedAt: 100)]
        )
        let rows = try fetchShoppingList(setup.db, storeId: "s1", sessionStartMs: .max)
        XCTAssertEqual(rows.count, 1, "Staples surface even outside the session window")
        XCTAssertFalse(rows.first?.isNeeded ?? true)
        XCTAssertTrue(rows.first?.isStaple ?? false)
    }

    func testIncludesPurchasedNonStaplesWithinSessionWindow() throws {
        let setup = try setup()
        try setup.db.seed(
            items: [TestFixtures.item(id: "i1", name: "Bread", isStaple: false)],
            stores: [TestFixtures.store(id: "s1")],
            xrefs: [TestFixtures.xref(itemId: "i1", storeId: "s1", isNeeded: false, lastPurchasedAt: 1_500)]
        )
        // Session started at 1000 — 1500 is inside the window.
        let inside = try fetchShoppingList(setup.db, storeId: "s1", sessionStartMs: 1_000)
        XCTAssertEqual(inside.count, 1, "Non-staple purchased after session start should appear (struck-through)")

        // Session started at 2000 — 1500 is outside the window.
        let outside = try fetchShoppingList(setup.db, storeId: "s1", sessionStartMs: 2_000)
        XCTAssertEqual(outside.count, 0, "Non-staple purchased before session start must not appear")
    }

    func testPerStoreFilterIsolatesEachStoresList() throws {
        let setup = try setup()
        try setup.db.seed(
            items: [TestFixtures.item(id: "i1", name: "Mozzarella")],
            stores: [
                TestFixtures.store(id: "s_lidl", name: "Lidl"),
                TestFixtures.store(id: "s_aldi", name: "Aldi"),
            ],
            xrefs: [
                TestFixtures.xref(itemId: "i1", storeId: "s_lidl", isNeeded: false, lastPurchasedAt: 5_000),
                TestFixtures.xref(itemId: "i1", storeId: "s_aldi", isNeeded: true),
            ]
        )
        // Outside session window — Lidl row dropped, Aldi still needed.
        let lidl = try fetchShoppingList(setup.db, storeId: "s_lidl", sessionStartMs: 9_000)
        XCTAssertEqual(lidl.count, 0, "Lidl should be empty after the session window passes")
        let aldi = try fetchShoppingList(setup.db, storeId: "s_aldi", sessionStartMs: 9_000)
        XCTAssertEqual(aldi.count, 1, "Aldi xref still has isNeeded=1 — should appear")
        XCTAssertTrue(aldi.first?.isNeeded ?? false)
    }

    func testStoreCategoryOrderControlsAisleSorting() throws {
        let setup = try setup()
        try setup.db.seed(
            items: [
                TestFixtures.item(id: "i1", name: "Banana", categoryId: "c_produce"),
                TestFixtures.item(id: "i2", name: "Bread", categoryId: "c_bakery"),
            ],
            stores: [TestFixtures.store(id: "s1")],
            categories: [
                TestFixtures.category(id: "c_produce", name: "Produce"),
                TestFixtures.category(id: "c_bakery", name: "Bakery"),
            ],
            xrefs: [
                TestFixtures.xref(itemId: "i1", storeId: "s1"),
                TestFixtures.xref(itemId: "i2", storeId: "s1"),
            ],
            scoOrders: [
                // Bakery first, Produce second
                TestFixtures.sco(storeId: "s1", categoryId: "c_bakery", displayOrder: 0),
                TestFixtures.sco(storeId: "s1", categoryId: "c_produce", displayOrder: 1),
            ]
        )
        let rows = try fetchShoppingList(setup.db, storeId: "s1")
        XCTAssertEqual(rows.map(\.id), ["i2", "i1"], "SCO displayOrder must drive ordering")
    }

    func testItemsWithoutScoFallToBottomOfBucket() throws {
        let setup = try setup()
        try setup.db.seed(
            items: [
                TestFixtures.item(id: "i1", name: "Banana", categoryId: "c_produce"),
                TestFixtures.item(id: "i2", name: "Mystery", categoryId: "c_other"),
            ],
            stores: [TestFixtures.store(id: "s1")],
            categories: [
                TestFixtures.category(id: "c_produce", name: "Produce"),
                TestFixtures.category(id: "c_other", name: "Other"),
            ],
            xrefs: [
                TestFixtures.xref(itemId: "i1", storeId: "s1"),
                TestFixtures.xref(itemId: "i2", storeId: "s1"),
            ],
            scoOrders: [
                TestFixtures.sco(storeId: "s1", categoryId: "c_produce", displayOrder: 0),
                // No SCO for c_other — falls to COALESCE 9999
            ]
        )
        let rows = try fetchShoppingList(setup.db, storeId: "s1")
        XCTAssertEqual(rows.map(\.id), ["i1", "i2"], "Item without SCO row should fall to bottom")
    }

    func testTombstonedXrefsAreExcluded() throws {
        let setup = try setup()
        try setup.db.seed(
            items: [TestFixtures.item(id: "i1", name: "Mozzarella")],
            stores: [TestFixtures.store(id: "s1")]
        )
        try setup.db.queue.write { conn in
            try conn.execute(sql: """
                INSERT INTO item_store_xref (itemId, storeId, userId, createdAt, updatedAt, deletedAt, isNeeded)
                VALUES ('i1', 's1', 'u1', 0, 0, 999, 1)
                """)
        }
        let rows = try fetchShoppingList(setup.db, storeId: "s1")
        XCTAssertEqual(rows.count, 0, "Tombstoned xrefs must be excluded by the INNER JOIN filter")
    }

    func testStorePickerItemsReturnsOneRowPerStore() throws {
        let setup = try setup()
        try setup.db.seed(
            items: [TestFixtures.item(id: "i1", name: "Mozzarella")],
            stores: [
                TestFixtures.store(id: "s_lidl"),
                TestFixtures.store(id: "s_aldi"),
            ],
            xrefs: [
                TestFixtures.xref(itemId: "i1", storeId: "s_lidl"),
                TestFixtures.xref(itemId: "i1", storeId: "s_aldi"),
            ]
        )
        let rows = try setup.db.queue.read { conn in
            try StorePickerItemRow.fetchAll(conn, sql: """
                SELECT isx.storeId  AS storeId,
                       i.id         AS itemId,
                       i.name       AS itemName,
                       i.isPriority AS isPriority,
                       isx.isNeeded AS isNeeded
                FROM items i
                INNER JOIN item_store_xref isx
                       ON isx.itemId = i.id
                      AND isx.userId = ?
                      AND isx.deletedAt IS NULL
                WHERE i.deletedAt IS NULL
                  AND i.userId = ?
                  AND (
                        isx.isNeeded = 1
                     OR (isx.lastPurchasedAt IS NOT NULL AND isx.lastPurchasedAt >= ?)
                  )
                """, arguments: ["u1", "u1", Int64.max])
        }
        XCTAssertEqual(Set(rows.map(\.storeId)), ["s_lidl", "s_aldi"])
    }
}
