import Foundation
import GRDB

/// Read-only DAO that powers the Shop-at-Store screen and the Store Picker
/// badges. Queries lift verbatim from Android's `ShoppingDao` — same joins,
/// same filters, same ORDER BY, including the COALESCE-to-9999 fallback for
/// categories without a per-store `displayOrder`.
///
/// v0.7.0 access scope: queries filter by `householdId` on both `items`
/// and `item_store_xref` (not `userId`). `userId` remains on each row as
/// creator/audit metadata; `householdId` is what scopes who can see and
/// mutate the rows. For single-member households both columns hold the
/// same value, so behaviour matches v0.6.x exactly.
struct ShoppingDao: Sendable {
    let writer: any DatabaseWriter

    /// The cross-cutting query that powers Shop-at-Store.
    ///
    /// Includes (for this store specifically):
    ///   - xrefs with `isNeeded = 1` (still on the list at this store)
    ///   - any xref whose item is a staple (always-on-the-list)
    ///   - any xref the user marked purchased *within the current session*
    ///     at this store (`lastPurchasedAt >= sessionStartMs`).
    ///
    /// Sort: needed rows first, then by aisle order at this store; ties
    /// broken by category name then item name. Items in categories without
    /// a per-store SCO row fall to the bottom of their bucket.
    ///
    /// Pass `Int64.max` as `sessionStartMs` to disable the session window
    /// (only needed/staple rows surface) — useful in tests.
    func shoppingListForStore(
        householdId: String,
        storeId: String,
        sessionStartMs: Int64
    ) -> AsyncValueObservation<[ShoppingRow]> {
        ValueObservation
            .tracking { db in
                try ShoppingRow.fetchAll(db, sql: Self.shoppingListSql, arguments: [
                    householdId,    // isx.householdId (INNER JOIN)
                    storeId,        // sco.storeId     (LEFT JOIN)
                    storeId,        // isx.storeId     (WHERE)
                    sessionStartMs, // isx.lastPurchasedAt >= ?
                    householdId,    // i.householdId
                ])
            }
            .values(in: writer)
    }

    /// Cross-store flat list of every (item, store) pair currently relevant
    /// to a Store Picker badge: still needed at the store, OR purchased
    /// within the active session. The picker repository groups by storeId
    /// to render one badge per store.
    func observeStorePickerItems(
        householdId: String,
        sessionStartMs: Int64
    ) -> AsyncValueObservation<[StorePickerItemRow]> {
        ValueObservation
            .tracking { db in
                try StorePickerItemRow.fetchAll(db, sql: Self.storePickerSql, arguments: [
                    householdId,
                    householdId,
                    sessionStartMs,
                ])
            }
            .values(in: writer)
    }

    // MARK: - SQL (single source of truth, parameterized for both observers)

    /// Argument order (in SQL-occurrence order):
    ///   1. isx.householdId      (INNER JOIN ON)
    ///   2. sco.storeId          (LEFT JOIN ON)
    ///   3. isx.storeId          (WHERE)
    ///   4. isx.lastPurchasedAt  (session window)
    ///   5. i.householdId        (WHERE)
    private static let shoppingListSql = """
        SELECT i.id            AS id,
               i.name          AS name,
               i.quantity      AS quantity,
               i.notes         AS notes,
               isx.isNeeded    AS isNeeded,
               i.brand         AS brand,
               i.imageUrl      AS imageUrl,
               i.isPriority    AS isPriority,
               i.isStaple      AS isStaple,
               i.isBuyToday    AS isBuyToday,
               c.id            AS cat_id,
               c.name          AS cat_name,
               c.nameKey       AS cat_nameKey,
               c.icon          AS cat_icon,
               sco.displayOrder AS displayOrder
        FROM items i
        INNER JOIN item_store_xref isx
               ON isx.itemId = i.id
              AND isx.householdId = ?
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
          AND i.householdId = ?
        ORDER BY isx.isNeeded DESC,
                 COALESCE(sco.displayOrder, 9999),
                 c.name COLLATE NOCASE,
                 i.name COLLATE NOCASE
        """

    /// Argument order (in SQL-occurrence order):
    ///   1. isx.householdId      (INNER JOIN ON)
    ///   2. i.householdId        (WHERE)
    ///   3. isx.lastPurchasedAt  (session window)
    private static let storePickerSql = """
        SELECT isx.storeId  AS storeId,
               i.id         AS itemId,
               i.name       AS itemName,
               i.isPriority AS isPriority,
               i.isBuyToday AS isBuyToday,
               isx.isNeeded AS isNeeded
        FROM items i
        INNER JOIN item_store_xref isx
               ON isx.itemId = i.id
              AND isx.householdId = ?
              AND isx.deletedAt IS NULL
        WHERE i.deletedAt IS NULL
          AND i.householdId = ?
          AND (
                isx.isNeeded = 1
             OR (isx.lastPurchasedAt IS NOT NULL AND isx.lastPurchasedAt >= ?)
          )
        """
}
