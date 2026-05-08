import Foundation
import Observation

@Observable
@MainActor
final class StorePickerViewModel {
    var rows: [StorePickerRow] = []
    /// Cross-store critical names — every priority+needed item across all
    /// stores, deduped by name. Drives the banner above the picker list.
    var criticalAcrossStores: [String] = []

    /// Last-error string from add/rename. Bound to dialogs as inline
    /// supportingText. Reset to nil on successful submit.
    var lastError: String?

    private let storeRepository: StoreRepository
    private let shoppingRepository: ShoppingRepository
    private let session: any UserSessionProvider
    private let sessionTracker: ShoppingSessionTracker
    private let binder = SessionBinder()
    private var sessionStartMs: Int64?

    init(
        storeRepository: StoreRepository,
        shoppingRepository: ShoppingRepository,
        session: any UserSessionProvider,
        sessionTracker: ShoppingSessionTracker
    ) {
        self.storeRepository = storeRepository
        self.shoppingRepository = shoppingRepository
        self.session = session
        self.sessionTracker = sessionTracker
    }

    /// Subscribe to the live picker rows. Anchors the trip on first call —
    /// see `ShoppingSessionTracker`.
    func bind() {
        Task { @MainActor in
            let start = await sessionTracker.sessionStartMs()
            self.sessionStartMs = start
            binder.bindStream(
                session: session,
                emptyValue: [StorePickerRow]()
            ) { [shoppingRepository] uid in
                shoppingRepository.observeStorePickerRows(userId: uid, sessionStartMs: start)
            } onValue: { [weak self] rows in
                self?.applyRows(rows)
            }
        }
    }

    func teardown() {
        binder.cancel()
    }

    private func applyRows(_ rows: [StorePickerRow]) {
        self.rows = rows
        var seen: Set<String> = []
        var deduped: [String] = []
        for row in rows {
            for name in row.criticalItemNames where seen.insert(name).inserted {
                deduped.append(name)
            }
        }
        self.criticalAcrossStores = deduped
    }

    // MARK: - Reorder

    /// Persist the new picker order. Called after the user releases a drag
    /// with the full top-to-bottom list of store ids.
    func commitOrder(_ orderedIds: [String]) {
        Task {
            try? await storeRepository.reorderStores(orderedIds: orderedIds)
        }
    }

    // MARK: - Add / rename / delete

    /// Returns nil on success or a localized error key the dialog can map
    /// to a String Catalog string. The repo owns name validation and
    /// throws typed errors; the VM just maps them to UI strings.
    func addStore(name: String) async -> String? {
        lastError = nil
        do {
            _ = try await storeRepository.addStore(name: name)
            return nil
        } catch StoreRepositoryError.emptyName {
            return String(localized: "error_store_name_empty")
        } catch StoreRepositoryError.duplicateName(let n) {
            return String(format: String(localized: "error_store_name_duplicate %@"), n)
        } catch {
            return String(localized: "error_could_not_add_store")
        }
    }

    func renameStore(id: String, name: String) async -> String? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return String(localized: "error_store_name_empty")
        }
        do {
            try await storeRepository.rename(id: id, name: trimmed)
            return nil
        } catch {
            return String(localized: "error_could_not_rename_store")
        }
    }

    func deleteStore(id: String) {
        Task { try? await storeRepository.softDelete(id: id) }
    }

    func undoDeleteStore(id: String) {
        Task { try? await storeRepository.undoSoftDelete(id: id) }
    }
}
