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
                    pendingSync: true
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
}

enum CategoryRepositoryError: Error, Equatable {
    case emptyName
    case duplicateName(String)
}
