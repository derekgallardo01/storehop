import Foundation
import Observation

/// One section of the per-store shopping list, grouped by category. The DAO
/// query already returns rows in aisle order, so we just split by category
/// boundary in `regroup`.
struct ShoppingCategorySection: Identifiable, Hashable, Sendable {
    let categoryName: String
    /// Seeded nameKey (e.g. "cat_produce") for localized header lookup.
    /// Nil for user-added categories — header falls back to `categoryName`.
    let categoryNameKey: String?
    let displayOrder: Int?
    let rows: [ShoppingRow]

    var id: String { categoryName }
}

/// One row in the QuickAddBar's autocomplete. Mirrors Android's
/// `QuickAddSuggestion`. Carries just what the suggestion list needs to
/// render (id + name + brand + category + staple flag).
struct QuickAddSuggestion: Identifiable, Hashable, Sendable {
    let itemId: String
    let name: String
    let brand: String?
    let categoryName: String?
    let isStaple: Bool

    var id: String { itemId }
}

@Observable
@MainActor
final class ShopAtStoreViewModel {
    let storeId: String

    var store: Store?
    var sections: [ShoppingCategorySection] = []
    var criticalNames: [String] = []
    var query: String = "" {
        didSet { refreshSections() }
    }
    /// User-toggled visibility for checked-off rows (any `!isNeeded` row,
    /// staple or not). Mirrored from `UserPreferencesRepository`; setter
    /// writes through and the stream broadcast feeds back here.
    var showPurchased: Bool = true {
        didSet { refreshSections() }
    }

    /// Current text in the QuickAdd field at the bottom of the screen.
    /// Drives `quickAddSuggestions` — recomputed on every change.
    var quickAddInput: String = "" {
        didSet { refreshQuickAddSuggestions() }
    }

    /// Autocomplete results for the QuickAddBar. Empty input shows up to 6
    /// staples not currently needed at this store. Non-empty input shows
    /// the master library filtered by name/brand substring (case-insensitive),
    /// prefix matches first, capped at 8.
    var quickAddSuggestions: [QuickAddSuggestion] = []

    /// Most recent `markPurchasedAtStore` snapshot timestamp. Powers the
    /// snackbar Undo: the user can roll back the cascade by tapping Undo
    /// before the snackbar dismisses. A new tap dismisses the prior
    /// snackbar (UI), and the snapshot is overwritten here.
    private var lastPurchaseSnapshot: Int64?

    /// Most recent purchased name shown in the snackbar.
    var lastPurchaseDisplayName: String?

    private var rawRows: [ShoppingRow] = []
    private var masterItems: [ItemWithCategoryAndStores] = []

    private let shoppingRepository: ShoppingRepository
    private let itemRepository: ItemRepository
    private let storeRepository: StoreRepository
    private let preferencesRepository: any UserPreferencesRepository
    private let session: any UserSessionProvider
    private let sessionTracker: ShoppingSessionTracker
    private let rowsBinder = SessionBinder()
    private let storeBinder = SessionBinder()
    private let masterItemsBinder = SessionBinder()
    private var prefsTask: Task<Void, Never>?

    init(
        storeId: String,
        shoppingRepository: ShoppingRepository,
        itemRepository: ItemRepository,
        storeRepository: StoreRepository,
        preferencesRepository: any UserPreferencesRepository,
        session: any UserSessionProvider,
        sessionTracker: ShoppingSessionTracker
    ) {
        self.storeId = storeId
        self.shoppingRepository = shoppingRepository
        self.itemRepository = itemRepository
        self.storeRepository = storeRepository
        self.preferencesRepository = preferencesRepository
        self.session = session
        self.sessionTracker = sessionTracker
        // Seed initial value synchronously so the first refreshSections()
        // doesn't race with the stream subscription below.
        self.showPurchased = preferencesRepository.showPurchased
    }

    /// Anchors the trip on first call. Subsequent ShopAtStore screens within
    /// the same process use the same anchor — purchases at one store keep
    /// items struck-through at every store the item is tagged to.
    func bind() {
        let storeId = self.storeId
        Task { @MainActor in
            let start = await sessionTracker.sessionStartMs()
            rowsBinder.bind(
                session: session,
                emptyValue: [ShoppingRow]()
            ) { [shoppingRepository] uid in
                shoppingRepository.shoppingListForStore(userId: uid, storeId: storeId, sessionStartMs: start)
            } onValue: { [weak self] rows in
                self?.applyRows(rows)
            }
        }
        storeBinder.bind(
            session: session,
            emptyValue: nil as Store?
        ) { [storeRepository] uid in
            storeRepository.observeById(userId: uid, id: storeId)
        } onValue: { [weak self] store in
            self?.store = store
        }
        // Master Items library — used by the QuickAdd autocomplete.
        masterItemsBinder.bind(
            session: session,
            emptyValue: [ItemWithCategoryAndStores]()
        ) { [itemRepository] uid in
            itemRepository.observeAll(userId: uid)
        } onValue: { [weak self] items in
            self?.applyMasterItems(items)
        }
        // Subscribe to the pref stream so the toggle stays in sync if the
        // user changes it from another VM instance.
        prefsTask = Task { @MainActor [weak self, preferencesRepository] in
            for await value in preferencesRepository.showPurchasedStream {
                self?.showPurchased = value
            }
        }
    }

    func teardown() {
        rowsBinder.cancel()
        storeBinder.cancel()
        masterItemsBinder.cancel()
        prefsTask?.cancel()
        prefsTask = nil
    }

    func setShowPurchased(_ value: Bool) {
        preferencesRepository.setShowPurchased(value)
    }

    private func applyRows(_ rows: [ShoppingRow]) {
        rawRows = rows
        criticalNames = rows.filter { $0.isPriority && $0.isNeeded }.map(\.itemName)
        refreshSections()
        // The "needed at this store" set comes from `rawRows`, so suggestions
        // also need to refresh when the shopping list changes (an item just
        // tagged here should drop out of the suggestion list).
        refreshQuickAddSuggestions()
    }

    private func applyMasterItems(_ items: [ItemWithCategoryAndStores]) {
        masterItems = items
        refreshQuickAddSuggestions()
    }

    private func refreshQuickAddSuggestions() {
        let needle = quickAddInput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !needle.isEmpty else {
            // Empty input → no suggestions. The bar stays unobtrusive until
            // the user starts typing.
            quickAddSuggestions = []
            return
        }
        let neededHere = Set(rawRows.filter { $0.isNeeded }.map(\.itemId))
        let lowerNeedle = needle.lowercased()
        quickAddSuggestions = masterItems
            .filter { !neededHere.contains($0.item.id) }
            .filter { row in
                row.item.name.lowercased().contains(lowerNeedle) ||
                (row.item.brand?.lowercased().contains(lowerNeedle) == true)
            }
            .sorted { lhs, rhs in
                let lRank = matchRank(lhs, needle: lowerNeedle)
                let rRank = matchRank(rhs, needle: lowerNeedle)
                if lRank != rRank { return lRank < rRank }
                return lhs.item.name.lowercased() < rhs.item.name.lowercased()
            }
            .prefix(8)
            .map { $0.toSuggestion() }
    }

    /// 0 = name prefix match, 1 = brand prefix match, 2 = substring only.
    private func matchRank(_ row: ItemWithCategoryAndStores, needle: String) -> Int {
        if row.item.name.lowercased().hasPrefix(needle) { return 0 }
        if row.item.brand?.lowercased().hasPrefix(needle) == true { return 1 }
        return 2
    }

    private func refreshSections() {
        // Single visibility filter: when showPurchased is false, hide every
        // checked-off row regardless of staple status. "Checked off" is one
        // concept to the user.
        let visible = showPurchased ? rawRows : rawRows.filter { $0.isNeeded }
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let filtered: [ShoppingRow]
        if needle.isEmpty {
            filtered = visible
        } else {
            filtered = visible.filter { row in
                row.itemName.lowercased().contains(needle) ||
                (row.brand?.lowercased().contains(needle) == true)
            }
        }
        sections = Self.groupByCategory(filtered)
    }

    /// Tap behavior on a row.
    ///   - needed → cascade-mark purchased (one trip = all stores).
    ///   - purchased → mark needed at this store only (manual un-check).
    func togglePurchased(row: ShoppingRow) {
        Task {
            if row.isNeeded {
                let snapshot = try? await itemRepository.markPurchasedAtStore(itemId: row.itemId, storeId: storeId)
                lastPurchaseSnapshot = snapshot
                lastPurchaseDisplayName = row.itemName
            } else {
                lastPurchaseSnapshot = nil
                lastPurchaseDisplayName = nil
                try? await itemRepository.markNeededAtStore(itemId: row.itemId, storeId: storeId)
            }
        }
    }

    /// Reverse the most recent cascade purchase. No-op if nothing's in
    /// flight (stale snackbar somehow escaped the dismiss-on-next-tap
    /// guard).
    func undoLastPurchase(itemId: String) {
        guard let snapshot = lastPurchaseSnapshot else { return }
        lastPurchaseSnapshot = nil
        lastPurchaseDisplayName = nil
        Task {
            try? await itemRepository.undoPurchase(itemId: itemId, snapshotTime: snapshot)
        }
    }

    func dismissPurchaseSnackbar() {
        lastPurchaseSnapshot = nil
        lastPurchaseDisplayName = nil
    }

    /// Submit the current QuickAdd input. Routes to
    /// `ItemRepository.addItemFromQuickAdd` which dedupes by case-insensitive
    /// name match before creating: existing master-list items get re-tagged
    /// to this store instead of duplicated. Clears the input on success.
    /// No-op for whitespace-only input.
    func submitQuickAddText() {
        let trimmed = quickAddInput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        Task { [storeId, itemRepository] in
            _ = try? await itemRepository.addItemFromQuickAdd(name: trimmed, storeId: storeId)
            await MainActor.run { self.quickAddInput = "" }
        }
    }

    /// The user tapped a suggestion in the QuickAdd autocomplete. Tag the
    /// existing master-list item to this store (idempotent for items already
    /// tagged) and clear the input.
    func pickExistingItem(itemId: String) {
        Task { [storeId, itemRepository] in
            try? await itemRepository.tagItemToStore(itemId: itemId, storeId: storeId)
            await MainActor.run { self.quickAddInput = "" }
        }
    }

    // MARK: - Helpers

    private static func groupByCategory(_ rows: [ShoppingRow]) -> [ShoppingCategorySection] {
        var groups: [(name: String, key: String?, displayOrder: Int?, rows: [ShoppingRow])] = []
        var groupIndex: [String: Int] = [:]
        for row in rows {
            let name = row.categoryName ?? "(uncategorized)"
            if let idx = groupIndex[name] {
                groups[idx].rows.append(row)
            } else {
                groupIndex[name] = groups.count
                groups.append((name: name, key: row.categoryNameKey, displayOrder: row.displayOrder, rows: [row]))
            }
        }
        return groups.map { ShoppingCategorySection(categoryName: $0.name, categoryNameKey: $0.key, displayOrder: $0.displayOrder, rows: $0.rows) }
    }
}

private extension ItemWithCategoryAndStores {
    func toSuggestion() -> QuickAddSuggestion {
        QuickAddSuggestion(
            itemId: item.id,
            name: item.name,
            brand: item.brand,
            categoryName: category?.name,
            isStaple: item.isStaple
        )
    }
}
