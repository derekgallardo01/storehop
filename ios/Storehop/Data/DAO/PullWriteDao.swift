import Foundation
import GRDB

/// Single-transaction batch write for the pull side. Either every entity
/// from the cloud lands or none of them do — the contract that lets the
/// rest of the system skip partial-pull handling. The atomicity also
/// matters for foreign-key consistency: items reference categoryId,
/// xrefs reference itemId/storeId, etc. — if items landed but stores
/// didn't, the DB would briefly contain dangling references.
///
/// Caller is responsible for converting DTOs to records (with
/// `pendingSync = false`) before invoking — see `EntityMappers.swift`
/// (Phase 9–10).
struct PullWriteDao: Sendable {
    let writer: any DatabaseWriter

    /// Replace every entity in one transaction. Empty lists are no-ops.
    /// Order inside the transaction doesn't matter for FK consistency
    /// (FKs are checked at commit), but parents-before-children keeps
    /// the intent visible.
    ///
    /// v0.8.0.4: rows with a local `pendingSync = 1` for the active
    /// household are **excluded** from the upsert. Local edits waiting
    /// to be pushed are the user's most-recent intent and must survive
    /// a pull-before-push race — otherwise the cloud's still-stale
    /// copy resurrects a delete the user just made. See the matching
    /// Android fix + Mike's bug report. Once `markPushed()` clears
    /// `pendingSync`, the next pull has no guard for that row and
    /// cloud is authoritative again.
    func replaceAllForUid(
        householdId: String,
        items: [Item],
        categories: [Category],
        stores: [Store],
        xrefs: [ItemStoreXref],
        scoOrders: [StoreCategoryOrder],
        purchaseRecords: [PurchaseRecord]
    ) async throws {
        try await writer.write { db in
            // Snapshot the local pending-sync primary keys per entity.
            // Any cloud row whose PK matches is dropped.
            let pendingItemIds = Set(try String.fetchAll(
                db,
                sql: "SELECT id FROM items WHERE householdId = ? AND pendingSync = 1",
                arguments: [householdId]
            ))
            let pendingCategoryIds = Set(try String.fetchAll(
                db,
                sql: "SELECT id FROM categories WHERE householdId = ? AND pendingSync = 1",
                arguments: [householdId]
            ))
            let pendingStoreIds = Set(try String.fetchAll(
                db,
                sql: "SELECT id FROM stores WHERE householdId = ? AND pendingSync = 1",
                arguments: [householdId]
            ))
            let pendingPurchaseIds = Set(try String.fetchAll(
                db,
                sql: "SELECT id FROM purchase_records WHERE householdId = ? AND pendingSync = 1",
                arguments: [householdId]
            ))
            // Composite PKs use a tuple-style key.
            struct XrefKey: Hashable { let itemId: String; let storeId: String }
            struct ScoKey: Hashable { let storeId: String; let categoryId: String }
            let pendingXrefRows = try Row.fetchAll(
                db,
                sql: "SELECT itemId, storeId FROM item_store_xref WHERE householdId = ? AND pendingSync = 1",
                arguments: [householdId]
            )
            let pendingXrefKeys: Set<XrefKey> = Set(pendingXrefRows.map {
                XrefKey(itemId: $0["itemId"], storeId: $0["storeId"])
            })
            let pendingScoRows = try Row.fetchAll(
                db,
                sql: "SELECT storeId, categoryId FROM store_category_order WHERE householdId = ? AND pendingSync = 1",
                arguments: [householdId]
            )
            let pendingScoKeys: Set<ScoKey> = Set(pendingScoRows.map {
                ScoKey(storeId: $0["storeId"], categoryId: $0["categoryId"])
            })

            for c in categories where !pendingCategoryIds.contains(c.id) {
                var copy = c
                try copy.upsert(db)
            }
            for s in stores where !pendingStoreIds.contains(s.id) {
                var copy = s
                try copy.upsert(db)
            }
            for i in items where !pendingItemIds.contains(i.id) {
                var copy = i
                try copy.upsert(db)
            }
            for x in xrefs where !pendingXrefKeys.contains(XrefKey(itemId: x.itemId, storeId: x.storeId)) {
                var copy = x
                try copy.upsert(db)
            }
            for sco in scoOrders where !pendingScoKeys.contains(ScoKey(storeId: sco.storeId, categoryId: sco.categoryId)) {
                var copy = sco
                try copy.upsert(db)
            }
            for record in purchaseRecords where !pendingPurchaseIds.contains(record.id) {
                var copy = record
                try copy.upsert(db)
            }
        }
    }

    /// v0.7.0 Phase 3: hard-delete every household-scoped row for
    /// [householdId]. Used when the user accepts another household's
    /// invite (their personal household's data is dropped and the new
    /// shared household is then pulled in) or when they leave a shared
    /// household. Single transaction so the wipe is all-or-nothing.
    ///
    /// Children-before-parents because of FK constraints — xrefs and
    /// SCOs reference stores/categories/items, so they must clear first.
    /// `household_members` rows are intentionally NOT touched here; the
    /// caller manages those.
    func wipeAllForHousehold(householdId: String) async throws {
        try await writer.write { db in
            try db.execute(
                sql: "DELETE FROM item_store_xref WHERE householdId = ?",
                arguments: [householdId]
            )
            try db.execute(
                sql: "DELETE FROM store_category_order WHERE householdId = ?",
                arguments: [householdId]
            )
            try db.execute(
                sql: "DELETE FROM purchase_records WHERE householdId = ?",
                arguments: [householdId]
            )
            try db.execute(
                sql: "DELETE FROM items WHERE householdId = ?",
                arguments: [householdId]
            )
            try db.execute(
                sql: "DELETE FROM categories WHERE householdId = ?",
                arguments: [householdId]
            )
            try db.execute(
                sql: "DELETE FROM stores WHERE householdId = ?",
                arguments: [householdId]
            )
        }
    }
}
