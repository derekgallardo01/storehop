import Foundation
import Observation

@Observable
@MainActor
final class ManageCategoriesViewModel {
    var categories: [Category] = []
    var pendingUndoId: String?
    var pendingUndoName: String?

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
}
