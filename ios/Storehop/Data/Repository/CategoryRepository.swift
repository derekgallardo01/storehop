import Foundation
import GRDB

struct CategoryRepository: Sendable {
    let writer: any DatabaseWriter
    let categoryDao: CategoryDao
    let itemDao: ItemDao
    let scoDao: StoreCategoryOrderDao
    let session: any UserSessionProvider
    let clock: any Clock
    let ids: any IdGenerator

    func observeAll(userId: String, includeArchived: Bool) -> AsyncValueObservation<[Category]> {
        categoryDao.observeAll(userId: userId, includeArchived: includeArchived)
    }

    /// Same three-case pattern as `StoreRepository.addStore` — see that
    /// method for the resurrect rationale.
    @discardableResult
    func addCategory(name: String, icon: String? = nil) async throws -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            throw CategoryRepositoryError.emptyName
        }
        let userId = try await session.requireSignedIn()
        let now = clock.nowMs()
        let newId = ids.newId()

        return try await writer.write { db -> String in
            let existing = try CategoryDao.findAnyByName(on: db, userId: userId, name: trimmed)
            // v0.6.4: append at the end of the global Manage Categories
            // list so a new row doesn't collide with another row's
            // existing displayOrder.
            let nextOrder = (try CategoryDao.maxDisplayOrder(on: db, userId: userId) ?? -1) + 1
            switch existing {
            case .none:
                var fresh = Category(
                    id: newId,
                    name: trimmed,
                    nameKey: nil,
                    icon: icon,
                    isArchived: false,
                    isSeeded: false,
                    userId: userId,
                    createdAt: now,
                    updatedAt: now,
                    deletedAt: nil,
                    pendingSync: true,
                    displayOrder: nextOrder
                )
                try fresh.upsert(db)
                return newId
            case .some(let row) where row.deletedAt == nil:
                throw CategoryRepositoryError.duplicateName(trimmed)
            case .some(var row):
                row.icon = icon ?? row.icon
                row.isArchived = false
                row.deletedAt = nil
                row.updatedAt = now
                row.pendingSync = true
                row.displayOrder = nextOrder
                try row.upsert(db)
                return row.id
            }
        }
    }

    func rename(id: String, name: String) async throws {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            throw CategoryRepositoryError.emptyName
        }
        let userId = try await session.requireSignedIn()
        let now = clock.nowMs()
        try await writer.write { db in
            guard var current = try CategoryDao.findById(on: db, userId: userId, id: id) else { return }
            // Alive-only collision check — the v6 fix. The UNIQUE index
            // counted tombstones, so a previously deleted "Pets" blocked
            // renaming "Pet" → "Pets". Now we reject only when an alive
            // row holds the target. Same-id case changes pass through.
            if let collision = try CategoryDao.findByName(on: db, userId: userId, name: trimmed),
               collision.id != current.id {
                throw CategoryRepositoryError.duplicateName(trimmed)
            }
            current.name = trimmed
            current.updatedAt = now
            current.pendingSync = true
            try current.upsert(db)
        }
    }

    func setArchived(id: String, archived: Bool) async throws {
        let userId = try await session.requireSignedIn()
        try await categoryDao.setArchived(userId: userId, id: id, archived: archived, now: clock.nowMs())
    }

    /// Cascade so a deleted category doesn't leave orphan SCO rows or items
    /// whose categoryId resolves to a tombstoned Category through joins.
    func softDelete(id: String) async throws {
        let userId = try await session.requireSignedIn()
        let now = clock.nowMs()
        try await writer.write { db in
            try CategoryDao.softDelete(on: db, userId: userId, id: id, now: now)
            try ItemDao.clearCategoryReferences(on: db, userId: userId, categoryId: id, now: now)
            try StoreCategoryOrderDao.softDeleteForCategory(on: db, userId: userId, categoryId: id, now: now)
        }
    }

    func undoSoftDelete(id: String) async throws {
        let userId = try await session.requireSignedIn()
        let now = clock.nowMs()
        try await writer.write { db in
            guard let category = try CategoryDao.findAnyById(on: db, userId: userId, id: id),
                  let deletedAt = category.deletedAt
            else { return }
            try CategoryDao.restoreFromTombstone(on: db, userId: userId, id: id, now: now)
            try StoreCategoryOrderDao.restoreCascadeForCategory(on: db, userId: userId, categoryId: id, deletedAt: deletedAt, now: now)
            // Re-link items: the cascade-clear stamped them with
            // updatedAt = deletedAt and categoryId = NULL. Mirror that
            // window to restore. See ItemDao.restoreCategoryReferences for
            // the precision caveat.
            try ItemDao.restoreCategoryReferences(on: db, userId: userId, categoryId: id, clearedAt: deletedAt, now: now)
        }
    }

    // MARK: - v0.6.4: reorder + batch delete + multi-add

    /// Rewrite the global Manage Categories order. `orderedIds` is the
    /// new top-to-bottom sequence; each id gets `displayOrder = index`.
    /// Wrapped in a transaction so a partial write can't leave the list
    /// in a half-reordered state. Per-store aisle order
    /// (StoreCategoryOrder) is unaffected.
    func reorder(orderedIds: [String]) async throws {
        let userId = try await session.requireSignedIn()
        let now = clock.nowMs()
        try await writer.write { db in
            for (index, id) in orderedIds.enumerated() {
                try CategoryDao.updateDisplayOrder(on: db, userId: userId, id: id, order: index, now: now)
            }
        }
    }

    /// Batch soft-delete. Every id in `ids` is tombstoned at the same
    /// `deletedAt` so `undoSoftDeleteMany` can restore the exact set in
    /// one shot. Cascades item.categoryId clearing + per-store aisle
    /// order tombstoning identically to single-row `softDelete`. Returns
    /// the batch deletedAt the caller can hand to undoSoftDeleteMany.
    @discardableResult
    func softDeleteMany(ids: [String]) async throws -> Int64 {
        let userId = try await session.requireSignedIn()
        let now = clock.nowMs()
        try await writer.write { db in
            for id in ids {
                try CategoryDao.softDelete(on: db, userId: userId, id: id, now: now)
                try ItemDao.clearCategoryReferences(on: db, userId: userId, categoryId: id, now: now)
                try StoreCategoryOrderDao.softDeleteForCategory(on: db, userId: userId, categoryId: id, now: now)
            }
        }
        return now
    }

    /// Reverse a `softDeleteMany` batch. Restores every category
    /// tombstoned at exactly `deletedAt`, plus their cascade-cleared
    /// SCO + item links.
    func undoSoftDeleteMany(deletedAt: Int64) async throws {
        let userId = try await session.requireSignedIn()
        let now = clock.nowMs()
        try await writer.write { db in
            let tombstoned = try CategoryDao.findTombstonedAt(on: db, userId: userId, deletedAt: deletedAt)
            for category in tombstoned {
                try CategoryDao.restoreFromTombstone(on: db, userId: userId, id: category.id, now: now)
                try StoreCategoryOrderDao.restoreCascadeForCategory(on: db, userId: userId, categoryId: category.id, deletedAt: deletedAt, now: now)
                try ItemDao.restoreCategoryReferences(on: db, userId: userId, categoryId: category.id, clearedAt: deletedAt, now: now)
            }
        }
    }
}

enum CategoryRepositoryError: Error, Equatable {
    case emptyName
    case duplicateName(String)
}
