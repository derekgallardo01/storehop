import Foundation
import Observation

/// Summary of a [ManageCategoriesViewModel.addManyCategories] call.
///  - `added`: number of new category rows persisted (includes resurrected
///    tombstones).
///  - `duplicates`: input names that already existed alive and were
///    silently skipped.
///  - `errors`: per-line failure messages (rare; usually empty).
struct BulkAddResult: Sendable, Equatable {
    let added: Int
    let duplicates: Int
    let errors: [String]
}

@Observable
@MainActor
final class ManageCategoriesViewModel {
    var categories: [Category] = []
    var pendingUndoId: String?
    var pendingUndoName: String?

    // v0.6.4: selection-mode state. In-memory only; resets when the screen
    // leaves. Mirrors Gmail / Photos.
    var selectionMode: Bool = false
    var selectedIds: Set<String> = []
    /// When non-nil, the screen renders an undo prompt with this count
    /// after a bulk-delete. Captures the batch's `deletedAt` for the
    /// undo callback.
    var pendingBulkUndoCount: Int = 0
    var pendingBulkUndoDeletedAt: Int64?

    private let categoryRepository: CategoryRepository
    private let session: any UserSessionProvider
    private let binder = SessionBinder()

    init(categoryRepository: CategoryRepository, session: any UserSessionProvider) {
        self.categoryRepository = categoryRepository
        self.session = session
    }

    func bind() {
        binder.bind(
            session: session,
            emptyValue: [Category]()
        ) { [categoryRepository] uid in
            categoryRepository.observeAll(userId: uid, includeArchived: false)
        } onValue: { [weak self] cats in
            self?.categories = cats
        }
    }

    func teardown() { binder.cancel() }

    /// Returns nil on success or a localized error string for the dialog.
    func addCategory(name: String) async -> String? {
        do {
            _ = try await categoryRepository.addCategory(name: name)
            return nil
        } catch CategoryRepositoryError.emptyName {
            return String(localized: "error_category_name_empty")
        } catch CategoryRepositoryError.duplicateName(let n) {
            return String(format: String(localized: "error_category_name_duplicate %@"), n)
        } catch {
            return String(localized: "error_could_not_add_category")
        }
    }

    func renameCategory(id: String, name: String) async -> String? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return String(localized: "error_category_name_empty") }
        do {
            try await categoryRepository.rename(id: id, name: trimmed)
            return nil
        } catch {
            return String(localized: "error_could_not_rename_category")
        }
    }

    func deleteCategory(_ category: Category) {
        let id = category.id
        let name = category.name
        Task {
            try? await categoryRepository.softDelete(id: id)
            await MainActor.run {
                self.pendingUndoId = id
                self.pendingUndoName = name
            }
        }
    }

    func undoDelete() {
        guard let id = pendingUndoId else { return }
        pendingUndoId = nil
        pendingUndoName = nil
        Task { try? await categoryRepository.undoSoftDelete(id: id) }
    }

    func dismissUndo() {
        pendingUndoId = nil
        pendingUndoName = nil
    }

    // MARK: - v0.6.4: selection mode + bulk delete + reorder + multi-add

    func enterSelection(id: String) {
        selectionMode = true
        selectedIds = [id]
    }

    func toggleSelection(id: String) {
        if selectedIds.contains(id) {
            selectedIds.remove(id)
        } else {
            selectedIds.insert(id)
        }
        if selectedIds.isEmpty {
            selectionMode = false
        }
    }

    func selectAll() {
        selectedIds = Set(categories.map(\.id))
    }

    func cancelSelection() {
        selectionMode = false
        selectedIds = []
    }

    /// Soft-delete every currently-selected category. Stages the batch
    /// deletedAt in `pendingBulkUndoDeletedAt` so the screen's undo bar
    /// can call `undoBulkDelete()` with the right value. Exits selection
    /// mode immediately so the screen renders against the shorter list.
    func deleteSelected() async {
        let ids = Array(selectedIds)
        guard !ids.isEmpty else { return }
        let count = ids.count
        let deletedAt: Int64
        do {
            deletedAt = try await categoryRepository.softDeleteMany(ids: ids)
        } catch {
            return
        }
        pendingBulkUndoCount = count
        pendingBulkUndoDeletedAt = deletedAt
        cancelSelection()
    }

    func undoBulkDelete() {
        guard let deletedAt = pendingBulkUndoDeletedAt else { return }
        pendingBulkUndoDeletedAt = nil
        pendingBulkUndoCount = 0
        Task { try? await categoryRepository.undoSoftDeleteMany(deletedAt: deletedAt) }
    }

    func dismissBulkUndo() {
        pendingBulkUndoDeletedAt = nil
        pendingBulkUndoCount = 0
    }

    /// Drag-reorder commit. `orderedIds` is the new top-to-bottom
    /// sequence; the screen builds it from its local drag state.
    func commitReorder(orderedIds: [String]) {
        Task { try? await categoryRepository.reorder(orderedIds: orderedIds) }
    }

    /// Bulk-add categories from a multi-line text blob. Splits on
    /// newlines, trims, drops blanks, and case-insensitively de-dupes
    /// within the input. Routes each unique name through
    /// `CategoryRepository.addCategory` which already handles the
    /// alive-skip + tombstone-resurrect cases.
    func addManyCategories(raw: String) async -> BulkAddResult {
        let unique = raw.split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
            .reduce(into: [String]()) { acc, name in
                if !acc.contains(where: { $0.caseInsensitiveCompare(name) == .orderedSame }) {
                    acc.append(name)
                }
            }
        if unique.isEmpty {
            return BulkAddResult(
                added: 0,
                duplicates: 0,
                errors: [String(localized: "error_category_name_empty")],
            )
        }
        var added = 0
        var duplicates = 0
        var errors: [String] = []
        for name in unique {
            do {
                _ = try await categoryRepository.addCategory(name: name)
                added += 1
            } catch CategoryRepositoryError.duplicateName(_) {
                duplicates += 1
            } catch {
                errors.append("\(String(localized: "error_could_not_add_category")): \(name)")
            }
        }
        return BulkAddResult(added: added, duplicates: duplicates, errors: errors)
    }
}
