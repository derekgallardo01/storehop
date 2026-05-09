import Foundation
import GRDB

/// Composes ItemDao + ItemStoreXrefDao + StoreCategoryOrderDao +
/// PurchaseRecordDao into the user-facing item operations. Every multi-DAO
/// path runs inside a single `writer.write { }` transaction so partial
/// states never reach disk or sync.
struct ItemRepository: Sendable {
    let writer: any DatabaseWriter
    let itemDao: ItemDao
    let xrefDao: ItemStoreXrefDao
    let scoDao: StoreCategoryOrderDao
    let purchaseDao: PurchaseRecordDao
    let session: any UserSessionProvider
    let clock: any Clock
    let ids: any IdGenerator

    // MARK: - Reactive

    /// Caller passes the active uid; ViewModel is responsible for switching
    /// observations on session changes (cancel + re-bind on each new uid).
    func observeAll(userId: String) -> AsyncValueObservation<[ItemWithCategoryAndStores]> {
        itemDao.observeAll(userId: userId)
    }

    func observeById(userId: String, id: String) -> AsyncValueObservation<ItemWithCategoryAndStores?> {
        itemDao.observeById(userId: userId, id: id)
    }

    /// One-shot read for the form's initial-load path. The form re-reads
    /// from the repo on each construction; live updates aren't useful while
    /// the user is filling in the form.
    func fetchSnapshot(userId: String, id: String) async throws -> ItemWithCategoryAndStores? {
        try await writer.read { db in
            try ItemWithCategoryAndStores.fetch(db, userId: userId, id: id)
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
            isPriority: isPriority
        )

        try await writer.write { db in
            var copy = item
            try copy.upsert(db)
            // Junction inherits userId from the parent we just wrote — the
            // ownership invariant.
            try ItemStoreXrefDao.setStoresForItem(on: db, itemId: id, storeIds: storeIds, userId: userId, now: now)
            try Self.ensureSCOForCategoryAtStores(on: db, categoryId: categoryId, storeIds: storeIds, userId: userId, now: now)
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
        let userId = try await session.requireSignedIn()
        let now = clock.nowMs()

        try await writer.write { db in
            // Preserve isNeeded / lastPurchasedAt / createdAt from the
            // current row. The session uid above is for authorization;
            // the *parent* uid is the source of truth for cross-table
            // ownership — using the live session would let a mid-call
            // sign-in/out swap break the cross-table invariant.
            guard var current = try ItemDao.findLiveById(on: db, userId: userId, id: id) else { return }
            let ownerId = current.userId

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

            try ItemStoreXrefDao.setStoresForItem(on: db, itemId: id, storeIds: storeIds, userId: ownerId, now: now)
            try Self.ensureSCOForCategoryAtStores(on: db, categoryId: categoryId, storeIds: storeIds, userId: ownerId, now: now)
        }
    }

    // MARK: - Soft delete + undo

    func softDelete(id: String) async throws {
        let userId = try await session.requireSignedIn()
        let now = clock.nowMs()
        try await writer.write { db in
            // Verify ownership before any write; cascade uses the parent's
            // userId for cross-table ownership consistency.
            guard let current = try ItemDao.findLiveById(on: db, userId: userId, id: id) else { return }
            let ownerId = current.userId

            try ItemDao.softDelete(on: db, userId: ownerId, id: id, now: now)
            try ItemStoreXrefDao.softDeleteForItem(on: db, userId: ownerId, itemId: id, now: now)
            try PurchaseRecordDao.softDeleteForItem(on: db, userId: ownerId, itemId: id, now: now)
        }
    }

    func undoSoftDelete(id: String) async throws {
        let userId = try await session.requireSignedIn()
        let now = clock.nowMs()
        try await writer.write { db in
            guard let item = try ItemDao.findAnyById(on: db, userId: userId, id: id),
                  let deletedAt = item.deletedAt
            else { return }
            let ownerId = item.userId

            try ItemDao.restoreFromTombstone(on: db, userId: ownerId, id: id, now: now)
            try ItemStoreXrefDao.restoreCascadeForItem(on: db, userId: ownerId, itemId: id, deletedAt: deletedAt, now: now)
            try PurchaseRecordDao.restoreCascadeForItem(on: db, userId: ownerId, itemId: id, deletedAt: deletedAt, now: now)
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
    /// fails (no item or wrong owner).
    @discardableResult
    func markPurchasedAtStore(itemId: String, storeId: String) async throws -> Int64? {
        let userId = try await session.requireSignedIn()
        let now = clock.nowMs()
        let recordId = ids.newId()

        return try await writer.write { db -> Int64? in
            guard let current = try ItemDao.findLiveById(on: db, userId: userId, id: itemId) else { return nil }
            let ownerId = current.userId

            try ItemStoreXrefDao.markPurchasedAcrossAllStores(on: db, userId: ownerId, itemId: itemId, now: now)
            let record = PurchaseRecord(
                id: recordId,
                itemId: itemId,
                storeId: storeId,
                purchasedAt: now,
                userId: ownerId,
                createdAt: now,
                updatedAt: now,
                deletedAt: nil,
                pendingSync: true
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
        let now = clock.nowMs()
        try await writer.write { db in
            guard let current = try ItemDao.findLiveById(on: db, userId: userId, id: itemId) else { return }
            let ownerId = current.userId

            try ItemStoreXrefDao.restorePurchaseAcrossAllStores(on: db, userId: ownerId, itemId: itemId, lastPurchasedAt: snapshotTime, now: now)
            try PurchaseRecordDao.softDeleteForItemAtTime(on: db, userId: ownerId, itemId: itemId, purchasedAt: snapshotTime, now: now)
        }
    }

    /// Restore a single (item, store) pair to "still needed at this store."
    /// Used to un-check a struck-through purchased staple. Doesn't touch
    /// `lastPurchasedAt` — the prior purchase still happened in history.
    func markNeededAtStore(itemId: String, storeId: String) async throws {
        let userId = try await session.requireSignedIn()
        let now = clock.nowMs()
        try await writer.write { db in
            guard let current = try ItemDao.findLiveById(on: db, userId: userId, id: itemId) else { return }
            try ItemStoreXrefDao.markNeededAtStore(on: db, userId: current.userId, itemId: itemId, storeId: storeId, now: now)
        }
    }

    /// Idempotently mark an existing item as needed at the given store.
    /// Creates the xref if missing, restores from a tombstone via upsert if
    /// only a tombstoned row exists, and flips `isNeeded` to true if a live
    /// xref already exists. Mirrors Android's `tagItemToStore` — the action
    /// behind the QuickAdd autocomplete's "tap a suggestion" flow.
    func tagItemToStore(itemId: String, storeId: String) async throws {
        let userId = try await session.requireSignedIn()
        let now = clock.nowMs()
        try await writer.write { db in
            guard let current = try ItemDao.findLiveById(on: db, userId: userId, id: itemId) else { return }
            let ownerId = current.userId
            let aliveXrefs = try ItemStoreXrefDao.findForItem(on: db, itemId: itemId)
            if aliveXrefs.contains(where: { $0.storeId == storeId }) {
                // Live xref already exists -- just ensure isNeeded=true.
                try ItemStoreXrefDao.markNeededAtStore(on: db, userId: ownerId, itemId: itemId, storeId: storeId, now: now)
            } else {
                // Either missing or only tombstoned. Upsert by primary key
                // (itemId, storeId) replaces a tombstone or inserts fresh.
                var xref = ItemStoreXref(
                    itemId: itemId,
                    storeId: storeId,
                    userId: ownerId,
                    createdAt: now,
                    updatedAt: now,
                    deletedAt: nil,
                    pendingSync: true,
                    isNeeded: true,
                    lastPurchasedAt: nil
                )
                try xref.upsert(db)
            }
            // Mirror addItem's behavior: ensure the SCO row exists so the
            // category sorts into aisle order at this store.
            try Self.ensureSCOForCategoryAtStores(
                on: db,
                categoryId: current.categoryId,
                storeIds: [storeId],
                userId: ownerId,
                now: now
            )
        }
    }

    /// Find-or-create entry point used by the Shop-at-Store QuickAdd bar.
    /// Trims input, then case-insensitive name-match against the user's
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
        let userId = try await session.requireSignedIn()
        let trimmed = name.trim()
        precondition(!trimmed.isEmpty, "name must be non-empty")
        // One-shot read for the dedupe lookup. The downstream call (either
        // tagItemToStore or addItem) opens its own writer transaction; no
        // need to wrap them together since each is atomic.
        let existing = try await writer.read { db in
            try ItemDao.findByName(on: db, userId: userId, name: trimmed)
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
        userId: String,
        now: Int64
    ) throws {
        guard let categoryId else { return }
        for storeId in storeIds {
            try StoreCategoryOrderDao.appendIfMissing(on: db, storeId: storeId, categoryId: categoryId, userId: userId, now: now)
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
