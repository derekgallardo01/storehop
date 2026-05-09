import Foundation
import GRDB

/// Items + categories CSV import / export. Mirrors Android's
/// `ImportExportRepository` 1:1.
///
/// Hard contract: **import never erases or modifies existing local data,
/// and never overwrites an existing Firestore document.** Implementation
/// achieves this by:
///
///  - Items: skip CSV rows whose name matches an alive item; otherwise
///    insert via `ItemRepository.addItem`.
///  - Categories / stores: reuse the existing add-with-resurrection paths
///    so a tombstoned row with the same name is brought back rather than
///    duplicated. No alive row is ever modified.
///
/// All sync side-effects fall out naturally: each new row carries
/// `pendingSync = true` so the next push tick uploads it; no existing
/// Firestore doc is touched.
struct ImportExportRepository: Sendable {
    let writer: any DatabaseWriter
    let categoryDao: CategoryDao
    let storeDao: StoreDao
    let itemDao: ItemDao
    let categoryRepository: CategoryRepository
    let storeRepository: StoreRepository
    let itemRepository: ItemRepository
    let session: any UserSessionProvider

    // MARK: - Export

    func exportItemsCsv() async throws -> String {
        let userId = try await session.requireSignedIn()
        // Snapshot read: pull every alive item along with its category +
        // store names. The shape mirrors `ItemWithCategoryAndStores` but
        // we don't need the full Room relations machinery for a one-shot
        // export — direct SQL is simpler.
        let csv = try await writer.read { db -> String in
            let items = try Item.fetchAll(db, sql: """
                SELECT * FROM items
                WHERE userId = ? AND deletedAt IS NULL
                ORDER BY name COLLATE NOCASE
                """, arguments: [userId])

            return try items.map { item -> ItemCsvRow in
                let categoryName = try item.categoryId.flatMap { categoryId in
                    try Category.fetchOne(db, sql: """
                        SELECT * FROM categories
                        WHERE id = ? AND userId = ? AND deletedAt IS NULL
                        """, arguments: [categoryId, userId])?.name
                }
                let storeNames = try Store.fetchAll(db, sql: """
                    SELECT s.* FROM stores s
                    JOIN item_store_xref x ON x.storeId = s.id
                    WHERE x.itemId = ? AND s.userId = ?
                      AND s.deletedAt IS NULL AND x.deletedAt IS NULL
                    ORDER BY s.name COLLATE NOCASE
                    """, arguments: [item.id, userId]).map(\.name)

                return ItemCsvRow(
                    name: item.name,
                    category: categoryName,
                    storeNames: storeNames,
                    brand: item.brand,
                    notes: item.notes,
                    quantity: item.quantity,
                    isStaple: item.isStaple,
                    isPriority: item.isPriority
                )
            }.toItemsCsv()
        }
        return csv
    }

    func exportCategoriesCsv() async throws -> String {
        let userId = try await session.requireSignedIn()
        return try await writer.read { db -> String in
            let cats = try Category.fetchAll(db, sql: """
                SELECT * FROM categories
                WHERE userId = ? AND deletedAt IS NULL AND isArchived = 0
                ORDER BY name COLLATE NOCASE
                """, arguments: [userId])
            return cats.map { CategoryCsvRow(name: $0.name, icon: $0.icon) }.toCategoriesCsv()
        }
    }

    // MARK: - Import

    /// Parse [content] as an items CSV and import each valid row. Skips
    /// rows whose name matches an alive item. Auto-creates referenced
    /// categories and stores by name (resurrecting tombstones if any).
    func importItemsCsv(_ content: String) async throws -> ImportResult {
        let userId = try await session.requireSignedIn()
        let parsed = parseItemCsv(content)
        if parsed.rows.isEmpty {
            return ImportResult(errors: parsed.errors)
        }

        var importedItemIds: [String] = []
        var importedCategoryIds: [String] = []
        var importedStoreIds: [String] = []
        var errors = parsed.errors
        var duplicatesSkipped = 0

        for row in parsed.rows {
            // Hard constraint: never modify an existing alive item.
            let existing = try await writer.read { db in
                try ItemDao.findByName(on: db, userId: userId, name: row.name)
            }
            if existing != nil {
                duplicatesSkipped += 1
                continue
            }

            let categoryId: String?
            if let name = row.category {
                categoryId = try await resolveCategory(
                    userId: userId,
                    name: name,
                    importedIds: &importedCategoryIds
                )
            } else {
                categoryId = nil
            }
            var storeIds: Set<String> = []
            for name in row.storeNames {
                if let id = try await resolveStore(
                    userId: userId,
                    name: name,
                    importedIds: &importedStoreIds
                ) {
                    storeIds.insert(id)
                }
            }

            do {
                let newId = try await itemRepository.addItem(
                    name: row.name,
                    categoryId: categoryId,
                    storeIds: storeIds,
                    quantity: row.quantity,
                    notes: row.notes,
                    brand: row.brand,
                    imageUrl: nil,
                    isStaple: row.isStaple,
                    isPriority: row.isPriority
                )
                importedItemIds.append(newId)
            } catch {
                errors.append("Could not import \"\(row.name)\": \(error.localizedDescription)")
            }
        }

        return ImportResult(
            itemsImported: importedItemIds.count,
            duplicatesSkipped: duplicatesSkipped,
            categoriesImported: importedCategoryIds.count,
            storesImported: importedStoreIds.count,
            errors: errors,
            importedItemIds: importedItemIds,
            importedCategoryIds: importedCategoryIds,
            importedStoreIds: importedStoreIds
        )
    }

    /// Parse [content] as a categories CSV. Each row goes through
    /// `CategoryRepository.addCategory` which handles tombstone-resurrect.
    /// Alive duplicates are skipped without error.
    func importCategoriesCsv(_ content: String) async throws -> ImportResult {
        let userId = try await session.requireSignedIn()
        let parsed = parseCategoryCsv(content)
        var importedCategoryIds: [String] = []
        var errors = parsed.errors
        var duplicatesSkipped = 0

        for row in parsed.rows {
            let existing = try await writer.read { db in
                try CategoryDao.findByName(on: db, userId: userId, name: row.name)
            }
            if existing != nil {
                duplicatesSkipped += 1
                continue
            }
            do {
                let id = try await categoryRepository.addCategory(name: row.name, icon: row.icon)
                importedCategoryIds.append(id)
            } catch CategoryRepositoryError.duplicateName(_), CategoryRepositoryError.emptyName {
                // Race: someone added the same-named category between our
                // findByName check and the upsert. Treat as skip.
                duplicatesSkipped += 1
            } catch {
                errors.append("Could not import \"\(row.name)\": \(error.localizedDescription)")
            }
        }

        return ImportResult(
            duplicatesSkipped: duplicatesSkipped,
            categoriesImported: importedCategoryIds.count,
            errors: errors,
            importedCategoryIds: importedCategoryIds
        )
    }

    /// Soft-delete every id in [result]. Called from the Undo bar after a
    /// fresh import. No-op for ids no longer alive (the user already
    /// deleted them manually before tapping Undo).
    func undoImport(_ result: ImportResult) async {
        // Items first so xrefs cascade-tombstone before categories / stores
        // get tombstoned themselves.
        for id in result.importedItemIds {
            try? await itemRepository.softDelete(id: id)
        }
        for id in result.importedCategoryIds {
            try? await categoryRepository.softDelete(id: id)
        }
        for id in result.importedStoreIds {
            try? await storeRepository.softDelete(id: id)
        }
    }

    // MARK: - Helpers

    /// Find an alive category by name; if none, call addCategory (which
    /// resurrects tombstones or inserts fresh) and remember the new id.
    private func resolveCategory(
        userId: String,
        name: String,
        importedIds: inout [String]
    ) async throws -> String? {
        if let existing = try await writer.read({ db in
            try CategoryDao.findByName(on: db, userId: userId, name: name)
        }) {
            return existing.id
        }
        do {
            let id = try await categoryRepository.addCategory(name: name, icon: nil)
            importedIds.append(id)
            return id
        } catch CategoryRepositoryError.duplicateName(_), CategoryRepositoryError.emptyName {
            return nil
        }
    }

    private func resolveStore(
        userId: String,
        name: String,
        importedIds: inout [String]
    ) async throws -> String? {
        if let existing = try await writer.read({ db in
            try StoreDao.findByName(on: db, userId: userId, name: name)
        }) {
            return existing.id
        }
        do {
            let id = try await storeRepository.addStore(name: name)
            importedIds.append(id)
            return id
        } catch StoreRepositoryError.duplicateName(_), StoreRepositoryError.emptyName {
            return nil
        }
    }
}

/// Summary returned by an import call. The caller renders the
/// "imported / skipped" counts in a snackbar and keeps the result around
/// (in the ViewModel) until the Undo window closes.
struct ImportResult: Equatable, Sendable {
    let itemsImported: Int
    /// Total CSV rows skipped because a duplicate already existed. Items
    /// import increments for name-matched alive items; categories import
    /// increments for name-matched alive categories. The snackbar shows a
    /// single rolled-up "Skipped N duplicates."
    let duplicatesSkipped: Int
    let categoriesImported: Int
    let storesImported: Int
    let errors: [String]
    let importedItemIds: [String]
    let importedCategoryIds: [String]
    let importedStoreIds: [String]

    init(
        itemsImported: Int = 0,
        duplicatesSkipped: Int = 0,
        categoriesImported: Int = 0,
        storesImported: Int = 0,
        errors: [String] = [],
        importedItemIds: [String] = [],
        importedCategoryIds: [String] = [],
        importedStoreIds: [String] = []
    ) {
        self.itemsImported = itemsImported
        self.duplicatesSkipped = duplicatesSkipped
        self.categoriesImported = categoriesImported
        self.storesImported = storesImported
        self.errors = errors
        self.importedItemIds = importedItemIds
        self.importedCategoryIds = importedCategoryIds
        self.importedStoreIds = importedStoreIds
    }
}
