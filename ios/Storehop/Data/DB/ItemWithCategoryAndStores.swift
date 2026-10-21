import Foundation
import GRDB

/// Item plus its (live) Category and (live) tagged Stores.
///
/// Mirrors Android's `ItemWithCategoryAndStores`. GRDB has no equivalent of
/// Room's `@Relation` / `@Junction`, so the related entities are loaded with
/// explicit follow-up queries inside a single read transaction. Tombstoned
/// rows are filtered out at query time, matching the cascade discipline the
/// repository layer enforces.
struct ItemWithCategoryAndStores: Hashable, Sendable {
    let item: Item
    let category: Category?
    let stores: [Store]

    /// Snapshot fetch for a single item by id, scoped to the parent household.
    /// Returns nil if the item is tombstoned or doesn't exist.
    static func fetch(_ db: Database, householdId: String, id: String) throws -> ItemWithCategoryAndStores? {
        guard let item = try Item.fetchOne(
            db,
            sql: "SELECT * FROM items WHERE id = ? AND householdId = ? AND deletedAt IS NULL",
            arguments: [id, householdId]
        ) else {
            return nil
        }
        return try assemble(db, item: item)
    }

    /// Snapshot fetch for every live item belonging to the household, sorted
    /// by name with case-insensitive collation (matches Android's ORDER BY).
    ///
    /// **v0.9.0 — one-off store filter.** Items whose alive xrefs ALL
    /// point at one-off stores are hidden from the master Items list
    /// (they live only inside the one-off store's Shop view). Items
    /// with zero alive xrefs (fresh CSV imports, just-created items)
    /// stay visible — the filter only hides items whose alive xrefs
    /// EXIST and are all one-off. The composite index
    /// `index_stores_householdId_isOneOff` from migration
    /// `v9_stores_one_off` backs the EXISTS subquery so the lookup
    /// stays O(log n).
    static func fetchAll(_ db: Database, householdId: String) throws -> [ItemWithCategoryAndStores] {
        let items = try Item.fetchAll(
            db,
            sql: """
                SELECT * FROM items
                WHERE householdId = ?
                  AND deletedAt IS NULL
                  AND (
                        NOT EXISTS (
                            SELECT 1 FROM item_store_xref isx
                            WHERE isx.itemId = items.id AND isx.deletedAt IS NULL
                        )
                     OR EXISTS (
                            SELECT 1 FROM item_store_xref isx
                            JOIN stores s ON s.id = isx.storeId
                            WHERE isx.itemId = items.id
                              AND isx.deletedAt IS NULL
                              AND s.isOneOff = 0
                              AND s.deletedAt IS NULL
                        )
                  )
                ORDER BY name COLLATE NOCASE
                """,
            arguments: [householdId]
        )
        guard !items.isEmpty else { return [] }

        let categories = try Category.fetchAll(
            db,
            sql: """
                SELECT c.* FROM categories c
                WHERE c.deletedAt IS NULL
                  AND (c.householdId = ? OR EXISTS (
                      SELECT 1 FROM items i
                      WHERE i.categoryId = c.id AND i.householdId = ? AND i.deletedAt IS NULL
                  ))
                """,
            arguments: [householdId, householdId]
        )
        let categoriesById = Dictionary(uniqueKeysWithValues: categories.map { ($0.id, $0) })

        let storeRows = try Row.fetchAll(
            db,
            sql: """
                SELECT isx.itemId AS _xrefItemId, s.*
                FROM stores s
                INNER JOIN item_store_xref isx
                       ON isx.storeId = s.id
                      AND isx.deletedAt IS NULL
                INNER JOIN items i
                       ON i.id = isx.itemId
                      AND i.householdId = ?
                      AND i.deletedAt IS NULL
                WHERE s.deletedAt IS NULL
                ORDER BY s.displayOrder, s.name COLLATE NOCASE
                """,
            arguments: [householdId]
        )
        var storesByItemId: [String: [Store]] = [:]
        for row in storeRows {
            let itemId: String = row["_xrefItemId"]
            let store = try Store(row: row)
            storesByItemId[itemId, default: []].append(store)
        }

        return items.map { item in
            let category = item.categoryId.flatMap { categoriesById[$0] }
            let stores = storesByItemId[item.id] ?? []
            return ItemWithCategoryAndStores(item: item, category: category, stores: stores)
        }
    }

    private static func assemble(_ db: Database, item: Item) throws -> ItemWithCategoryAndStores {
        let category: Category?
        if let categoryId = item.categoryId {
            category = try Category.fetchOne(
                db,
                sql: "SELECT * FROM categories WHERE id = ? AND deletedAt IS NULL",
                arguments: [categoryId]
            )
        } else {
            category = nil
        }
        let stores = try Store.fetchAll(
            db,
            sql: """
                SELECT s.*
                FROM stores s
                INNER JOIN item_store_xref isx
                       ON isx.storeId = s.id
                      AND isx.itemId = ?
                      AND isx.deletedAt IS NULL
                WHERE s.deletedAt IS NULL
                ORDER BY s.displayOrder, s.name COLLATE NOCASE
                """,
            arguments: [item.id]
        )
        return ItemWithCategoryAndStores(item: item, category: category, stores: stores)
    }
}
