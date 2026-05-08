import Foundation
import GRDB

struct StoreRepository: Sendable {
    let writer: any DatabaseWriter
    let storeDao: StoreDao
    let xrefDao: ItemStoreXrefDao
    let scoDao: StoreCategoryOrderDao
    let session: any UserSessionProvider
    let clock: any Clock
    let ids: any IdGenerator

    // MARK: - Reactive

    func observeAll(userId: String, includeArchived: Bool) -> AsyncValueObservation<[Store]> {
        storeDao.observeAll(userId: userId, includeArchived: includeArchived)
    }

    func observeById(userId: String, id: String) -> AsyncValueObservation<Store?> {
        storeDao.observeById(userId: userId, id: id)
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
    func addStore(name: String, colorArgb: Int64? = nil) async throws -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            throw StoreRepositoryError.emptyName
        }
        let userId = try await session.requireSignedIn()
        let now = clock.nowMs()
        let newId = ids.newId()

        return try await writer.write { db -> String in
            let existing = try StoreDao.findAnyByName(on: db, userId: userId, name: trimmed)
            switch existing {
            case .none:
                let displayOrder = try StoreDao.nextDisplayOrder(on: db, userId: userId)
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
                    displayOrder: displayOrder
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
                try row.upsert(db)
                return row.id
            }
        }
    }

    // MARK: - Update

    func rename(id: String, name: String) async throws {
        let userId = try await session.requireSignedIn()
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let now = clock.nowMs()
        try await writer.write { db in
            guard var current = try StoreDao.findById(on: db, userId: userId, id: id) else { return }
            current.name = trimmed
            current.updatedAt = now
            current.pendingSync = true
            try current.upsert(db)
        }
    }

    func setColor(id: String, colorArgb: Int64?) async throws {
        let userId = try await session.requireSignedIn()
        let now = clock.nowMs()
        try await writer.write { db in
            guard var current = try StoreDao.findById(on: db, userId: userId, id: id) else { return }
            current.colorArgb = colorArgb
            current.updatedAt = now
            current.pendingSync = true
            try current.upsert(db)
        }
    }

    func setArchived(id: String, archived: Bool) async throws {
        let userId = try await session.requireSignedIn()
        try await storeDao.setArchived(userId: userId, id: id, archived: archived, now: clock.nowMs())
    }

    /// Atomic reorder: each store's displayOrder updates inside one
    /// transaction so a partial reorder (cancellation, device dies) never
    /// leaves the picker with mixed old/new positions.
    func reorderStores(orderedIds: [String]) async throws {
        let userId = try await session.requireSignedIn()
        let now = clock.nowMs()
        try await writer.write { db in
            for (index, id) in orderedIds.enumerated() {
                try StoreDao.setDisplayOrder(on: db, userId: userId, id: id, displayOrder: index, now: now)
            }
        }
    }

    // MARK: - Soft delete + undo (cascade)

    func softDelete(id: String) async throws {
        let userId = try await session.requireSignedIn()
        let now = clock.nowMs()
        try await writer.write { db in
            try StoreDao.softDelete(on: db, userId: userId, id: id, now: now)
            try ItemStoreXrefDao.softDeleteForStore(on: db, userId: userId, storeId: id, now: now)
            try StoreCategoryOrderDao.softDeleteForStore(on: db, userId: userId, storeId: id, now: now)
        }
    }

    func undoSoftDelete(id: String) async throws {
        let userId = try await session.requireSignedIn()
        let now = clock.nowMs()
        try await writer.write { db in
            guard let store = try StoreDao.findAnyById(on: db, userId: userId, id: id),
                  let deletedAt = store.deletedAt
            else { return }
            try StoreDao.restoreFromTombstone(on: db, userId: userId, id: id, now: now)
            try ItemStoreXrefDao.restoreCascadeForStore(on: db, userId: userId, storeId: id, deletedAt: deletedAt, now: now)
            try StoreCategoryOrderDao.restoreCascadeForStore(on: db, userId: userId, storeId: id, deletedAt: deletedAt, now: now)
        }
    }
}

enum StoreRepositoryError: Error, Equatable {
    case emptyName
    case duplicateName(String)
}
