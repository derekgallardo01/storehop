import Foundation
import GRDB

/// v0.7.0: queries scope by `householdId`. `userId` is still required (it's
/// the creator/audit field on each row, stamped on insert). Cross-cascade
/// DAOs (xrefDao, scoDao) scope by `householdId`. For single-member
/// households `householdId == userId` so behaviour matches v0.6.x exactly.
struct StoreRepository: Sendable {
    let writer: any DatabaseWriter
    let storeDao: StoreDao
    let xrefDao: ItemStoreXrefDao
    let scoDao: StoreCategoryOrderDao
    let session: any UserSessionProvider
    let householdSession: any HouseholdSessionProvider
    let clock: any Clock
    let ids: any IdGenerator

    // MARK: - Reactive

    /// ViewModel-facing observer. External param stays `userId:` for source
    /// compatibility; the DAO call forwards it as `householdId:`. In
    /// single-member households the two values are equal.
    func observeAll(userId: String, includeArchived: Bool) -> AsyncValueObservation<[Store]> {
        storeDao.observeAll(householdId: userId, includeArchived: includeArchived)
    }

    func observeById(userId: String, id: String) -> AsyncValueObservation<Store?> {
        storeDao.observeById(householdId: userId, id: id)
    }

    // MARK: - Add (with resurrect-on-re-add)

    /// Three cases inside one transaction:
    ///   1. No row at all          → insert with a fresh UUID + max+1 displayOrder.
    ///   2. Live row with same name → throw `DuplicateStoreError`.
    ///   3. Tombstoned row with same name → resurrect (clear deletedAt,
    ///      refresh colorArgb + updatedAt). Re-using the id is the right
    ///      sync semantic: other devices see the row come back to life
    ///      rather than appearing as a new row that conflicts with their
    ///      tombstone.
    @discardableResult
    /// `isOneOff` (v0.9.0) — see `Store.isOneOff` for semantics. Resurrected
    /// tombstones adopt the caller's flag value so the post-create state
    /// matches what the user asked for (the prior tombstone's flag isn't
    /// preserved across resurrection).
    func addStore(name: String, colorArgb: Int64? = nil, isOneOff: Bool = false) async throws -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            throw StoreRepositoryError.emptyName
        }
        let userId = try await session.requireSignedIn()
        let householdId = try await householdSession.requireHouseholdId()
        let now = clock.nowMs()
        let newId = ids.newId()

        return try await writer.write { db -> String in
            let existing = try StoreDao.findAnyByName(on: db, householdId: householdId, name: trimmed)
            switch existing {
            case .none:
                let displayOrder = try StoreDao.nextDisplayOrder(on: db, householdId: householdId)
                var newStore = Store(
                    id: newId,
                    name: trimmed,
                    colorArgb: colorArgb,
                    isArchived: false,
                    isSeeded: false,
                    userId: userId,
                    createdAt: now,
                    updatedAt: now,
                    deletedAt: nil,
                    pendingSync: true,
                    displayOrder: displayOrder,
                    householdId: householdId,
                    isOneOff: isOneOff
                )
                try newStore.upsert(db)
                return newId
            case .some(let row) where row.deletedAt == nil:
                throw StoreRepositoryError.duplicateName(trimmed)
            case .some(var row):
                // Resurrect: keep original displayOrder so other devices see
                // the row revived in place, not bumped to the bottom.
                row.colorArgb = colorArgb
                row.isArchived = false
                row.deletedAt = nil
                row.updatedAt = now
                row.pendingSync = true
                row.isOneOff = isOneOff
                try row.upsert(db)
                return row.id
            }
        }
    }

    /// v0.9.0 — flip a store's `isOneOff` flag. Idempotent: if the
    /// current value matches the requested one, skips the write entirely
    /// (no `updatedAt` bump, no `pendingSync` flag, no Firestore push)
    /// to avoid churning the LWW timestamp on no-op taps from the UI.
    func setOneOff(id: String, isOneOff: Bool) async throws {
        let householdId = try await householdSession.requireHouseholdId()
        let now = clock.nowMs()
        try await writer.write { db in
            guard var current = try StoreDao.findById(on: db, householdId: householdId, id: id) else { return }
            if current.isOneOff == isOneOff { return }
            current.isOneOff = isOneOff
            current.updatedAt = now
            current.pendingSync = true
            try current.upsert(db)
        }
    }

    // MARK: - Update

    func rename(id: String, name: String) async throws {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            throw StoreRepositoryError.emptyName
        }
        let householdId = try await householdSession.requireHouseholdId()
        let now = clock.nowMs()
        try await writer.write { db in
            guard var current = try StoreDao.findById(on: db, householdId: householdId, id: id) else { return }
            // Alive-only collision check. Schema v6 dropped the UNIQUE
            // constraint on (userId, name) so tombstoned rows don't block
            // name reuse. Same-id case-only changes ("Aldi" → "ALDI")
            // pass through because findByName returns the same row.
            if let collision = try StoreDao.findByName(on: db, householdId: householdId, name: trimmed),
               collision.id != current.id {
                throw StoreRepositoryError.duplicateName(trimmed)
            }
            current.name = trimmed
            current.updatedAt = now
            current.pendingSync = true
            try current.upsert(db)
        }
    }

    func setColor(id: String, colorArgb: Int64?) async throws {
        let householdId = try await householdSession.requireHouseholdId()
        let now = clock.nowMs()
        try await writer.write { db in
            guard var current = try StoreDao.findById(on: db, householdId: householdId, id: id) else { return }
            current.colorArgb = colorArgb
            current.updatedAt = now
            current.pendingSync = true
            try current.upsert(db)
        }
    }

    func setArchived(id: String, archived: Bool) async throws {
        let householdId = try await householdSession.requireHouseholdId()
        try await storeDao.setArchived(householdId: householdId, id: id, archived: archived, now: clock.nowMs())
    }

    /// Atomic reorder: each store's displayOrder updates inside one
    /// transaction so a partial reorder (cancellation, device dies) never
    /// leaves the picker with mixed old/new positions.
    func reorderStores(orderedIds: [String]) async throws {
        let householdId = try await householdSession.requireHouseholdId()
        let now = clock.nowMs()
        try await writer.write { db in
            for (index, id) in orderedIds.enumerated() {
                try StoreDao.setDisplayOrder(on: db, householdId: householdId, id: id, displayOrder: index, now: now)
            }
        }
    }

    // MARK: - Soft delete + undo (cascade)

    func softDelete(id: String) async throws {
        let householdId = try await householdSession.requireHouseholdId()
        let now = clock.nowMs()
        try await writer.write { db in
            try StoreDao.softDelete(on: db, householdId: householdId, id: id, now: now)
            try ItemStoreXrefDao.softDeleteForStore(on: db, householdId: householdId, storeId: id, now: now)
            try StoreCategoryOrderDao.softDeleteForStore(on: db, householdId: householdId, storeId: id, now: now)
        }
    }

    func undoSoftDelete(id: String) async throws {
        let householdId = try await householdSession.requireHouseholdId()
        let now = clock.nowMs()
        try await writer.write { db in
            guard let store = try StoreDao.findAnyById(on: db, householdId: householdId, id: id),
                  let deletedAt = store.deletedAt
            else { return }
            try StoreDao.restoreFromTombstone(on: db, householdId: householdId, id: id, now: now)
            try ItemStoreXrefDao.restoreCascadeForStore(on: db, householdId: householdId, storeId: id, deletedAt: deletedAt, now: now)
            try StoreCategoryOrderDao.restoreCascadeForStore(on: db, householdId: householdId, storeId: id, deletedAt: deletedAt, now: now)
        }
    }
}

enum StoreRepositoryError: Error, Equatable {
    case emptyName
    case duplicateName(String)
}
