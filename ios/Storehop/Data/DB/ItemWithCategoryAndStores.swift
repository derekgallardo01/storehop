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

    /// Snapshot fetch for a single item by id, scoped to the parent userId.
    /// Returns nil if the item is tombstoned or doesn't exist.
    static func fetch(_ db: Database, userId: String, id: String) throws -> ItemWithCategoryAndStores? {
        guard let item = try Item.fetchOne(
            db,
            sql: "SELECT * FROM items WHERE id = ? AND userId = ? AND deletedAt IS NULL",
            arguments: [id, userId]
        ) else {
            return nil
        }
        return try assemble(db, item: item)
    }

    /// Snapshot fetch for every live item belonging to the user, sorted by
    /// name with case-insensitive collation (matches Android's ORDER BY).
    static func fetchAll(_ db: Database, userId: String) throws -> [ItemWithCategoryAndStores] {
        let items = try Item.fetchAll(
            db,
            sql: """
                SELECT * FROM items
                WHERE userId = ? AND deletedAt IS NULL
                ORDER BY name COLLATE NOCASE
                """,
            arguments: [userId]
        )
        return try items.map { try assemble(db, item: $0) }
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
