import Foundation
import GRDB

/// One row of the Shop-at-Store list, denormalized via INNER/LEFT joins on
/// items, item_store_xref, categories, and store_category_order. The ordering
/// fields (`displayOrder`, `categoryName`) come from the joins, not the items
/// table — see `ShoppingDao.shoppingListForStore` for the shape.
///
/// Mirrors the Android `ShoppingRow` data class. Column names in the SQL
/// `AS` aliases below MUST match `CodingKeys` so `FetchableRecord` decodes
/// the join result correctly.
struct ShoppingRow: FetchableRecord, Decodable, Hashable, Sendable {
    let id: String
    let name: String
    let quantity: String?
    let notes: String?
    let isNeeded: Bool
    let brand: String?
    let imageUrl: String?
    let isPriority: Bool
    let isStaple: Bool
    let categoryId: String?
    let categoryName: String?
    let categoryNameKey: String?
    let categoryIcon: String?
    let displayOrder: Int?

    enum CodingKeys: String, CodingKey {
        case id
        case name
        case quantity
        case notes
        case isNeeded
        case brand
        case imageUrl
        case isPriority
        case isStaple
        case categoryId    = "cat_id"
        case categoryName  = "cat_name"
        case categoryNameKey = "cat_nameKey"
        case categoryIcon  = "cat_icon"
        case displayOrder
    }

    /// Convenience for the UI — `id` is the *item's* id, since each item
    /// shows once per shopping list per store. Lets SwiftUI use this row as
    /// `Identifiable` directly.
    var itemId: String { id }
    var itemName: String { name }
}

extension ShoppingRow: Identifiable {}
