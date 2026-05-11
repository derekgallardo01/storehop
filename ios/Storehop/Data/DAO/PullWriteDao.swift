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
    func replaceAllForUid(
        items: [Item],
        categories: [Category],
        stores: [Store],
        xrefs: [ItemStoreXref],
        scoOrders: [StoreCategoryOrder],
        purchaseRecords: [PurchaseRecord]
    ) async throws {
        try await writer.write { db in
            for c in categories {
                var copy = c
                try copy.upsert(db)
            }
            for s in stores {
                var copy = s
                try copy.upsert(db)
            }
            for i in items {
                var copy = i
                try copy.upsert(db)
            }
            for x in xrefs {
                var copy = x
                try copy.upsert(db)
            }
            for sco in scoOrders {
                var copy = sco
                try copy.upsert(db)
            }
            for record in purchaseRecords {
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
