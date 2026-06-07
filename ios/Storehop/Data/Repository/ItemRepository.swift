import Foundation
import GRDB

/// Composes ItemDao + ItemStoreXrefDao + StoreCategoryOrderDao +
/// PurchaseRecordDao into the user-facing item operations. Every multi-DAO
/// path runs inside a single `writer.write { }` transaction so partial
/// states never reach disk or sync.
///
/// v0.7.0: queries scope by `householdId`. `userId` is still required (it's
/// the creator/audit field on each row, stamped on insert).
///
/// Cross-cascade DAOs (xrefDao, scoDao, purchaseRecordDao) scope by
/// `householdId` — the item is owned by the household, so cascading any
/// delete/restore reaches every member's rows under that household. The
/// snackbar-undo path on `purchaseRecordDao.softDeleteForItemAtTime` is
/// the deliberate exception: it stays per-user so only the purchaser can
/// rescind their own record.
struct ItemRepository: Sendable {
    let writer: any DatabaseWriter
    let itemDao: ItemDao
    let xrefDao: ItemStoreXrefDao
    let scoDao: StoreCategoryOrderDao
    let purchaseDao: PurchaseRecordDao
    let session: any UserSessionProvider
    let householdSession: any HouseholdSessionProvider
    let clock: any Clock
    let ids: any IdGenerator

    // MARK: - Reactive

    /// Caller passes the active access-scope id (the ViewModel rebinds on
    /// each session change). External param remains `userId:` for source
    /// compatibility; the DAO call forwards it as `householdId:`. In
    /// single-member households the two values are equal.
    func observeAll(userId: String) -> AsyncValueObservation<[ItemWithCategoryAndStores]> {
        itemDao.observeAll(householdId: userId)
    }

    func observeById(userId: String, id: String) -> AsyncValueObservation<ItemWithCategoryAndStores?> {
        itemDao.observeById(householdId: userId, id: id)
    }

    /// One-shot read for the form's initial-load path. The form re-reads
    /// from the repo on each construction; live updates aren't useful while
    /// the user is filling in the form.
    func fetchSnapshot(userId: String, id: String) async throws -> ItemWithCategoryAndStores? {
        try await writer.read { db in
            try ItemWithCategoryAndStores.fetch(db, householdId: userId, id: id)
        }
    }

    // MARK: - Add / update

    /// Returns the new item id. Wraps Item insert + xref diff +
    /// SCO append-if-missing in one transaction.
    @discardableResult
    func addItem(
        name: String,
        categoryId: String?,
        storeIds: Set<String>,
        quantity: String?,
        notes: String?,
        isNeeded: Bool = true,
        brand: String?,
        imageUrl: String?,
        isStaple: Bool,
        isPriority: Bool
    ) async throws -> String {
        let userId = try await session.requireSignedIn()
        let householdId = try await householdSession.requireHouseholdId()
        let now = clock.nowMs()
        let id = ids.newId()
        let trimmedName = name.trim()
        let item = Item(
            id: id,
            name: trimmedName,
            categoryId: categoryId,
            notes: notes.trimmedOrNil,
            quantity: quantity.trimmedOrNil,
            isNeeded: isNeeded,
            lastPurchasedAt: nil,
            userId: userId,
            createdAt: now,
            updatedAt: now,
            deletedAt: nil,
            pendingSync: true,
            brand: brand.trimmedOrNil,
            imageUrl: imageUrl,
            isStaple: isStaple,
            isPriority: isPriority,
            householdId: householdId
        )

        try await writer.write { db in
            var copy = item
            try copy.upsert(db)
            // Junction inherits both ids from the parent we just wrote —
            // ownership invariant. `userId` is creator/audit; `householdId`
            // is access scope.
            try ItemStoreXrefDao.setStoresForItem(on: db, itemId: id, storeIds: storeIds, householdId: householdId, userId: userId, now: now)
            try Self.ensureSCOForCategoryAtStores(on: db, categoryId: categoryId, storeIds: storeIds, householdId: householdId, userId: userId, now: now)
        }
        return id
    }

    func updateItem(
        id: String,
        name: String,
        categoryId: String?,
        storeIds: Set<String>,
        quantity: String?,
        notes: String?,
        brand: String?,
        imageUrl: String?,
        isStaple: Bool,
        isPriority: Bool
    ) async throws {
        let householdId = try await householdSession.requireHouseholdId()
        let now = clock.nowMs()

        try await writer.write { db in
            // Preserve isNeeded / lastPurchasedAt / createdAt from the
            // current row. The *parent* row is the source of truth for
            // cross-table ownership — pass the parent's householdId +
            // userId, NOT the live session, so a mid-call sign-in/out
            // swap can't break the cross-table invariant.
            guard var current = try ItemDao.findLiveById(on: db, householdId: householdId, id: id) else { return }
            let ownerHouseholdId = current.householdId
            let ownerUserId = current.userId

            current.name = name.trim()
            current.categoryId = categoryId
            current.quantity = quantity.trimmedOrNil
            current.notes = notes.trimmedOrNil
            current.brand = brand.trimmedOrNil
            current.imageUrl = imageUrl
            current.isStaple = isStaple
            current.isPriority = isPriority
            current.updatedAt = now
            current.pendingSync = true
            try current.upsert(db)

            try ItemStoreXrefDao.setStoresForItem(on: db, itemId: id, storeIds: storeIds, householdId: ownerHouseholdId, userId: ownerUserId, now: now)
            try Self.ensureSCOForCategoryAtStores(on: db, categoryId: categoryId, storeIds: storeIds, householdId: ownerHouseholdId, userId: ownerUserId, now: now)
        }
    }

    // MARK: - Soft delete + undo

    func softDelete(id: String) async throws {
        let householdId = try await householdSession.requireHouseholdId()
        let now = clock.nowMs()
        try await writer.write { db in
            // Verify ownership before any write; cascade uses the parent's
            // householdId for cross-table ownership consistency.
            guard let current = try ItemDao.findLiveById(on: db, householdId: householdId, id: id) else { return }
            let ownerHouseholdId = current.householdId

            try ItemDao.softDelete(on: db, householdId: ownerHouseholdId, id: id, now: now)
            try ItemStoreXrefDao.softDeleteForItem(on: db, householdId: ownerHouseholdId, itemId: id, now: now)
            try PurchaseRecordDao.softDeleteForItem(on: db, householdId: ownerHouseholdId, itemId: id, now: now)
        }
    }

    func undoSoftDelete(id: String) async throws {
        let householdId = try await householdSession.requireHouseholdId()
        let now = clock.nowMs()
        try await writer.write { db in
            guard let item = try ItemDao.findAnyById(on: db, householdId: householdId, id: id),
                  let deletedAt = item.deletedAt
            else { return }
            let ownerHouseholdId = item.householdId

            try ItemDao.restoreFromTombstone(on: db, householdId: ownerHouseholdId, id: id, now: now)
            try ItemStoreXrefDao.restoreCascadeForItem(on: db, householdId: ownerHouseholdId, itemId: id, deletedAt: deletedAt, now: now)
            try PurchaseRecordDao.restoreCascadeForItem(on: db, householdId: ownerHouseholdId, itemId: id, deletedAt: deletedAt, now: now)
        }
    }

    // MARK: - Mark purchased / needed

    /// Mark this item purchased. Cascades isNeeded=0 + lastPurchasedAt=now
    /// across every store the item is currently tagged to (the v0.5.1 rule:
    /// one trip satisfies the need everywhere). Writes one PurchaseRecord
    /// for the store the user actually shopped at.
    ///
    /// Returns the snapshot timestamp so `undoPurchase` can roll back by
    /// matching `lastPurchasedAt` precisely; returns nil if the item lookup
    /// fails (no item or wrong household).
    @discardableResult
    func markPurchasedAtStore(itemId: String, storeId: String) async throws -> Int64? {
        let userId = try await session.requireSignedIn()
        let householdId = try await householdSession.requireHouseholdId()
        let now = clock.nowMs()
        let recordId = ids.newId()

        return try await writer.write { db -> Int64? in
            guard let current = try ItemDao.findLiveById(on: db, householdId: householdId, id: itemId) else { return nil }
            let ownerHouseholdId = current.householdId

            try ItemStoreXrefDao.markPurchasedAcrossAllStores(on: db, householdId: ownerHouseholdId, itemId: itemId, now: now)
            // PurchaseRecord's userId is the *purchaser* (the user who
            // clicked the checkbox), not the item's creator. Under
            // multi-user this matters: stats filter by purchaser per the
            // v0.7.0 design ("what I bought" not "what we bought").
            let record = PurchaseRecord(
                id: recordId,
                itemId: itemId,
                storeId: storeId,
                purchasedAt: now,
                userId: userId,
                createdAt: now,
                updatedAt: now,
                deletedAt: nil,
                pendingSync: true,
                householdId: ownerHouseholdId
            )
            try PurchaseRecordDao.insert(record, on: db)
            return now
        }
    }

    /// Inverse of `markPurchasedAtStore`. Filtered by `snapshotTime` so a
    /// later, unrelated purchase of the same item isn't accidentally
    /// rolled back together.
    func undoPurchase(itemId: String, snapshotTime: Int64) async throws {
        let userId = try await session.requireSignedIn()
        let householdId = try await householdSession.requireHouseholdId()
        let now = clock.nowMs()
        try await writer.write { db in
            guard let current = try ItemDao.findLiveById(on: db, householdId: householdId, id: itemId) else { return }
            let ownerHouseholdId = current.householdId

            try ItemStoreXrefDao.restorePurchaseAcrossAllStores(on: db, householdId: ownerHouseholdId, itemId: itemId, lastPurchasedAt: snapshotTime, now: now)
            // Scope undo to the purchaser's own records (their userId),
            // matching the insert side. Different users undoing their own
            // purchases stays isolated even though they share the household.
            try PurchaseRecordDao.softDeleteForItemAtTime(on: db, userId: userId, itemId: itemId, purchasedAt: snapshotTime, now: now)
        }
    }

    /// v0.6.1: distinct item IDs that have at least one alive xref with
    /// `isNeeded = 1` for the active access-scope id. Powers the Items-list
    /// +/- toggle. External param stays `userId:` for source compatibility;
    /// forwarded as `householdId:` to the DAO.
    func observeNeededItemIds(userId: String) -> AsyncValueObservation<[String]> {
        ItemStoreXrefDao(writer: writer).observeNeededItemIds(householdId: userId)
    }

    /// v0.6.1: mark this item needed at every store it's tagged to. The
    /// "+" branch of the Items-list toggle. Cross-store cascade design
    /// keeps the inverse "−" branch coherent: clearing at one store
    /// already clears at all (see [markPurchasedAcrossAllStoresNoRecord]).
    func markNeededAcrossAllStores(itemId: String) async throws {
        let householdId = try await householdSession.requireHouseholdId()
        let now = clock.nowMs()
        try await writer.write { db in
            guard let current = try ItemDao.findLiveById(on: db, householdId: householdId, id: itemId) else { return }
            try ItemStoreXrefDao.markNeededAcrossAllStores(on: db, householdId: current.householdId, itemId: itemId, now: now)
        }
    }

    /// v0.6.1: mark this item not-needed at every store it's tagged to.
    /// Pure list-state action -- does NOT write a PurchaseRecord. The user
    /// is on the master Items list, not at a store, so attributing the
    /// purchase to any one store would be wrong.
    func markPurchasedAcrossAllStoresNoRecord(itemId: String) async throws {
        let householdId = try await householdSession.requireHouseholdId()
        let now = clock.nowMs()
        try await writer.write { db in
            guard let current = try ItemDao.findLiveById(on: db, householdId: householdId, id: itemId) else { return }
            try ItemStoreXrefDao.markPurchasedAcrossAllStores(on: db, householdId: current.householdId, itemId: itemId, now: now)
        }
    }

    /// Restore a single (item, store) pair to "still needed at this store."
    /// Used to un-check a struck-through purchased staple. Doesn't touch
    /// `lastPurchasedAt` — the prior purchase still happened in history.
    func markNeededAtStore(itemId: String, storeId: String) async throws {
        let householdId = try await householdSession.requireHouseholdId()
        let now = clock.nowMs()
        try await writer.write { db in
            guard let current = try ItemDao.findLiveById(on: db, householdId: householdId, id: itemId) else { return }
            try ItemStoreXrefDao.markNeededAtStore(on: db, householdId: current.householdId, itemId: itemId, storeId: storeId, now: now)
        }
    }

    /// Idempotently mark an existing item as needed at the given store.
    /// Creates the xref if missing, restores from a tombstone via upsert if
    /// only a tombstoned row exists, and flips `isNeeded` to true if a live
    /// xref already exists. Mirrors Android's `tagItemToStore` — the action
    /// behind the QuickAdd autocomplete's "tap a suggestion" flow.
    func tagItemToStore(itemId: String, storeId: String) async throws {
        let householdId = try await householdSession.requireHouseholdId()
        let now = clock.nowMs()
        try await writer.write { db in
            try Self.tagItemToStore(
                on: db,
                householdId: householdId,
                itemId: itemId,
                storeId: storeId,
                now: now
            )
        }
    }

    /// v0.8.1: idempotently tag a batch of items to a batch of stores in one
    /// transaction. Add-only semantics — each `(itemId, storeId)` pair is
    /// either already tagged (no-op + flips `isNeeded` back to true if it
    /// was false) or gets a fresh alive xref (resurrecting a tombstone by
    /// primary-key upsert when one is present). Stores not in
    /// `storeIdsToAdd` are not touched — this is the bulk equivalent of
    /// toggling more chips "on," not a replace-all.
    ///
    /// Powers the v0.8.1 bulk-tag UI from the Items list: long-press to
    /// enter selection mode, pick N items, choose stores to apply. The
    /// transaction wraps the whole batch so a partial failure can't leave
    /// half the items tagged.
    ///
    /// No-op if either set is empty. Items not owned by the live household
    /// are silently skipped (inherited from `tagItemToStore`'s guard).
    func bulkTagStoresForItems(itemIds: Set<String>, storeIdsToAdd: Set<String>) async throws {
        guard !itemIds.isEmpty && !storeIdsToAdd.isEmpty else { return }
        let householdId = try await householdSession.requireHouseholdId()
        let now = clock.nowMs()
        try await writer.write { db in
            for itemId in itemIds {
                for storeId in storeIdsToAdd {
                    // Delegate to the single-pair path. It already handles
                    // resurrect-tombstone / no-op-alive / fresh-insert
                    // correctly. Everything runs inside this outer
                    // transaction so a partial failure rolls back the
                    // whole batch.
                    try Self.tagItemToStore(
                        on: db,
                        householdId: householdId,
                        itemId: itemId,
                        storeId: storeId,
                        now: now
                    )
                }
            }
        }
    }

    /// Shared body for both `tagItemToStore` and `bulkTagStoresForItems`.
    /// Operates on an open `Database` handle so the bulk path can fold
    /// many pairs into one outer transaction.
    private static func tagItemToStore(
        on db: Database,
        householdId: String,
        itemId: String,
        storeId: String,
        now: Int64
    ) throws {
        guard let current = try ItemDao.findLiveById(on: db, householdId: householdId, id: itemId) else { return }
        let ownerHouseholdId = current.householdId
        let ownerUserId = current.userId
        let aliveXrefs = try ItemStoreXrefDao.findForItem(on: db, itemId: itemId)
        if aliveXrefs.contains(where: { $0.storeId == storeId }) {
            // Live xref already exists -- just ensure isNeeded=true.
            try ItemStoreXrefDao.markNeededAtStore(on: db, householdId: ownerHouseholdId, itemId: itemId, storeId: storeId, now: now)
        } else {
            // Either missing or only tombstoned. Upsert by primary key
            // (itemId, storeId) replaces a tombstone or inserts fresh.
            var xref = ItemStoreXref(
                itemId: itemId,
                storeId: storeId,
                userId: ownerUserId,
                createdAt: now,
                updatedAt: now,
                deletedAt: nil,
                pendingSync: true,
                isNeeded: true,
                lastPurchasedAt: nil,
                householdId: ownerHouseholdId
            )
            try xref.upsert(db)
        }
        // Mirror addItem's behavior: ensure the SCO row exists so the
        // category sorts into aisle order at this store.
        try Self.ensureSCOForCategoryAtStores(
            on: db,
            categoryId: current.categoryId,
            storeIds: [storeId],
            householdId: ownerHouseholdId,
            userId: ownerUserId,
            now: now
        )
    }

    /// Find-or-create entry point used by the Shop-at-Store QuickAdd bar.
    /// Trims input, then case-insensitive name-match against the household's
    /// master library:
    ///   - hit  → `tagItemToStore(existing.id, storeId)`; returns that id.
    ///   - miss → `addItem(name, storeIds: [storeId])`; returns the new id.
    ///
    /// Fixes the v0.5.6 bug where typing a name in the QuickAdd bar that
    /// already existed created a duplicate Item (uncategorized). `addItem`'s
    /// "always creates" semantics stay intact for non-QuickAdd callers; the
    /// dedupe lives only here.
    @discardableResult
    func addItemFromQuickAdd(name: String, storeId: String) async throws -> String {
        let householdId = try await householdSession.requireHouseholdId()
        let trimmed = name.trim()
        precondition(!trimmed.isEmpty, "name must be non-empty")
        // One-shot read for the dedupe lookup. The downstream call (either
        // tagItemToStore or addItem) opens its own writer transaction; no
        // need to wrap them together since each is atomic.
        let existing = try await writer.read { db in
            try ItemDao.findByName(on: db, householdId: householdId, name: trimmed)
        }
        if let existing {
            try await tagItemToStore(itemId: existing.id, storeId: storeId)
            return existing.id
        }
        return try await addItem(
            name: trimmed,
            categoryId: nil,
            storeIds: [storeId],
            quantity: nil,
            notes: nil,
            brand: nil,
            imageUrl: nil,
            isStaple: false,
            isPriority: false
        )
    }

    // MARK: - Helpers

    /// After a save, make sure each store the item is tagged at has a live
    /// SCO row for the item's category. Without this, custom user-added
    /// categories never become aisle-orderable for that store.
    private static func ensureSCOForCategoryAtStores(
        on db: Database,
        categoryId: String?,
        storeIds: Set<String>,
        householdId: String,
        userId: String,
        now: Int64
    ) throws {
        guard let categoryId else { return }
        for storeId in storeIds {
            try StoreCategoryOrderDao.appendIfMissing(on: db, storeId: storeId, categoryId: categoryId, householdId: householdId, userId: userId, now: now)
        }
    }
}

// MARK: - String trim helpers

private extension String {
    func trim() -> String {
        trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

private extension Optional where Wrapped == String {
    /// Trim and treat empty as nil — matches the Android pattern of dropping
    /// empty strings rather than persisting whitespace.
    var trimmedOrNil: String? {
        guard let raw = self?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty else {
            return nil
        }
        return raw
    }
}
