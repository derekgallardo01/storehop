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
            for category in categories { try category.upsert(db) }
            for store in stores { try store.upsert(db) }
            for item in items { try item.upsert(db) }
            for xref in xrefs { try xref.upsert(db) }
            for sco in scoOrders { try sco.upsert(db) }
            for record in purchaseRecords { try record.upsert(db) }
        }
    }
}
