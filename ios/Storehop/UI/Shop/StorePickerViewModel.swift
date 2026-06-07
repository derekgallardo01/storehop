import Foundation
import Observation

/// Snapshot of the critical-needs banner's state. Computed by the VM and
/// consumed by `CriticalNeedsBanner`. `byStore` lists only stores that have
/// at least one critical item, preserving the user's display order. Mirrors
/// `CriticalBannerState` in Android's `StorePickerViewModel.kt`.
struct CriticalBannerState: Equatable, Sendable {
    let totalCount: Int
    let topStoreName: String
    let topStoreCount: Int
    let singleStore: Bool
    /// (store name, list of critical item names at that store).
    let byStore: [(String, [String])]

    static func == (lhs: CriticalBannerState, rhs: CriticalBannerState) -> Bool {
        lhs.totalCount == rhs.totalCount
            && lhs.topStoreName == rhs.topStoreName
            && lhs.topStoreCount == rhs.topStoreCount
            && lhs.singleStore == rhs.singleStore
            && lhs.byStore.count == rhs.byStore.count
            && zip(lhs.byStore, rhs.byStore).allSatisfy { l, r in l.0 == r.0 && l.1 == r.1 }
    }
}

@Observable
@MainActor
final class StorePickerViewModel {
    var rows: [StorePickerRow] = []
    /// Routing-aware summary of priority+needed items across stores. Nil
    /// when nothing critical is needed (banner hidden). When present,
    /// names the single store that covers the most criticals so the user
    /// knows where to shop first. Ties resolve to whichever store appears
    /// earlier in `rows` (which is already in user display order).
    var criticalBannerState: CriticalBannerState?

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
        self.criticalBannerState = Self.makeBannerState(from: rows)
    }

    /// Build the routing-aware banner state. Walks rows in display order so
    /// ties on critical count resolve to whichever store comes first
    /// (matches Android: `maxByOrNull` returns the first match on equal
    /// counts when iterating a list).
    private static func makeBannerState(from rows: [StorePickerRow]) -> CriticalBannerState? {
        // v0.9.0 — skip one-off stores in the critical-needs banner.
        // The banner is a "grocery run" signal aimed at recurring
        // purchases; surfacing a critical for a one-off store (e.g.
        // "Hardware (One Off)") would be noisy and miss the point.
        // Items mixed-tagged across both kinds keep ringing through the
        // regular-store row, so the user still sees them.
        let withCriticals = rows.filter { !$0.store.isOneOff && !$0.criticalItemNames.isEmpty }
        guard !withCriticals.isEmpty else { return nil }

        // Distinct count across all stores (an item tagged to N stores
        // counts once toward the total).
        var seen: Set<String> = []
        for row in withCriticals {
            for name in row.criticalItemNames { seen.insert(name) }
        }
        let total = seen.count

        // First store with the max critical count (preserves display
        // order on ties).
        var top = withCriticals[0]
        for row in withCriticals.dropFirst() where row.criticalItemNames.count > top.criticalItemNames.count {
            top = row
        }

        return CriticalBannerState(
            totalCount: total,
            topStoreName: top.store.name,
            topStoreCount: top.criticalItemNames.count,
            singleStore: withCriticals.count == 1,
            byStore: withCriticals.map { ($0.store.name, $0.criticalItemNames) }
        )
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
    /// `isOneOff` (v0.9.0) — see `Store.isOneOff` semantics. Default
    /// `false` so existing call sites compile unchanged.
    func addStore(name: String, isOneOff: Bool = false) async -> String? {
        lastError = nil
        do {
            _ = try await storeRepository.addStore(name: name, isOneOff: isOneOff)
            return nil
        } catch StoreRepositoryError.emptyName {
            return L("error_store_name_empty")
        } catch StoreRepositoryError.duplicateName(let n) {
            return String(format: L("error_store_name_duplicate %@"), n)
        } catch {
            return L("error_could_not_add_store")
        }
    }

    /// v0.9.0 — flip the `isOneOff` flag on an existing store. The
    /// repository's idempotency guard handles the "tap the toggle but
    /// the value is already correct" case without a Firestore push.
    func setOneOff(id: String, isOneOff: Bool) async {
        do {
            try await storeRepository.setOneOff(id: id, isOneOff: isOneOff)
        } catch {
            lastError = L("error_could_not_update_store")
        }
    }

    func renameStore(id: String, name: String) async -> String? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return L("error_store_name_empty")
        }
        do {
            try await storeRepository.rename(id: id, name: trimmed)
            return nil
        } catch {
            return L("error_could_not_rename_store")
        }
    }

    func deleteStore(id: String) {
        Task { try? await storeRepository.softDelete(id: id) }
    }

    func undoDeleteStore(id: String) {
        Task { try? await storeRepository.undoSoftDelete(id: id) }
    }
}
