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

    /// Most recent `markPurchasedAtStore` snapshot timestamp. Powers the
    /// snackbar Undo: the user can roll back the cascade by tapping Undo
    /// before the snackbar dismisses. A new tap dismisses the prior
    /// snackbar (UI), and the snapshot is overwritten here.
    private var lastPurchaseSnapshot: Int64?

    /// Most recent purchased name shown in the snackbar.
    var lastPurchaseDisplayName: String?

    private var rawRows: [ShoppingRow] = []

    private let shoppingRepository: ShoppingRepository
    private let itemRepository: ItemRepository
    private let storeRepository: StoreRepository
    private let session: any UserSessionProvider
    private let sessionTracker: ShoppingSessionTracker
    private let rowsBinder = SessionBinder()
    private let storeBinder = SessionBinder()

    init(
        storeId: String,
        shoppingRepository: ShoppingRepository,
        itemRepository: ItemRepository,
        storeRepository: StoreRepository,
        session: any UserSessionProvider,
        sessionTracker: ShoppingSessionTracker
    ) {
        self.storeId = storeId
        self.shoppingRepository = shoppingRepository
        self.itemRepository = itemRepository
        self.storeRepository = storeRepository
        self.session = session
        self.sessionTracker = sessionTracker
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
    }

    func teardown() {
        rowsBinder.cancel()
        storeBinder.cancel()
    }

    private func applyRows(_ rows: [ShoppingRow]) {
        rawRows = rows
        criticalNames = rows.filter { $0.isPriority && $0.isNeeded }.map(\.itemName)
        refreshSections()
    }

    private func refreshSections() {
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let filtered: [ShoppingRow]
        if needle.isEmpty {
            filtered = rawRows
        } else {
            filtered = rawRows.filter { row in
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

    /// Quick-add from the bottom bar: name only, auto-tagged to this store,
    /// defaults for everything else.
    func quickAdd(name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        Task {
            _ = try? await itemRepository.addItem(
                name: trimmed,
                categoryId: nil,
                storeIds: [storeId],
                quantity: nil,
                notes: nil,
                brand: nil,
                imageUrl: nil,
                isStaple: false,
                isPriority: false
            )
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
