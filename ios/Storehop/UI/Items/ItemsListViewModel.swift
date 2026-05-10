import Foundation
import Observation

/// Internal sentinel matching Android's `UNCATEGORISED_SENTINEL`. The view
/// swaps it for the localized "(uncategorised)" label when rendering the
/// trailing section header in `.category` sort mode.
let itemsUncategorisedSentinel = "__uncategorised__"

/// One section of the master Items list when `sortMode == .category`.
/// For uncategorised items, `categoryName` equals `itemsUncategorisedSentinel`
/// and `categoryNameKey` is nil.
struct ItemsCategorySection: Identifiable, Hashable, Sendable {
    let categoryName: String
    let categoryNameKey: String?
    let rows: [ItemWithCategoryAndStores]

    var id: String { categoryName }
}

@Observable
@MainActor
final class ItemsListViewModel {
    /// Flat alphabetic rows (case-insensitive on item.name), populated only
    /// when `sortMode == .alphabetic`.
    var items: [ItemWithCategoryAndStores] = []
    /// Category-grouped sections, populated only when `sortMode == .category`.
    /// Sections sorted alphabetically by category name; uncategorised items
    /// trail in a section keyed by `itemsUncategorisedSentinel`.
    var sections: [ItemsCategorySection] = []
    /// v0.6.1: item ids that have at least one alive xref where
    /// `isNeeded = 1`. The Items list shows "−" for these, "+" otherwise.
    var neededItemIds: Set<String> = []
    var query: String = "" {
        didSet { refresh() }
    }
    var sortMode: SortMode = .alphabetic {
        didSet { refresh() }
    }

    /// Pending undo prompt from a cross-screen event (item deleted via the
    /// form). Bound to a snackbar; cleared by Undo or auto-dismiss.
    var pendingUndo: UndoEvent?

    private var rawItems: [ItemWithCategoryAndStores] = []
    private let itemRepository: ItemRepository
    private let undoEventBus: UndoEventBus
    private let preferencesRepository: any UserPreferencesRepository
    private let session: any UserSessionProvider
    private let binder = SessionBinder()
    private let neededBinder = SessionBinder()
    private var undoListenerTask: Task<Void, Never>?
    private var sortPrefsTask: Task<Void, Never>?

    init(
        itemRepository: ItemRepository,
        undoEventBus: UndoEventBus,
        preferencesRepository: any UserPreferencesRepository,
        session: any UserSessionProvider
    ) {
        self.itemRepository = itemRepository
        self.undoEventBus = undoEventBus
        self.preferencesRepository = preferencesRepository
        self.session = session
        // Seed initial value synchronously so the first refresh() doesn't
        // race with the stream subscription below.
        self.sortMode = preferencesRepository.itemsListSortMode
    }

    func bind() {
        binder.bind(
            session: session,
            emptyValue: [ItemWithCategoryAndStores]()
        ) { [itemRepository] uid in
            itemRepository.observeAll(userId: uid)
        } onValue: { [weak self] items in
            self?.rawItems = items
            self?.refresh()
        }
        // v0.6.1: subscribe to the needed-ids stream. The view reads
        // `neededItemIds` to decide +/- per row.
        neededBinder.bind(
            session: session,
            emptyValue: [String]()
        ) { [itemRepository] uid in
            itemRepository.observeNeededItemIds(userId: uid)
        } onValue: { [weak self] ids in
            self?.neededItemIds = Set(ids)
        }

        // Listen for cross-screen delete events from the form.
        undoListenerTask = Task { [weak self] in
            guard let self else { return }
            for await event in await self.undoEventBus.events() {
                await MainActor.run {
                    self.pendingUndo = event
                }
            }
        }
        sortPrefsTask = Task { @MainActor [weak self, preferencesRepository] in
            for await value in preferencesRepository.itemsListSortModeStream {
                self?.sortMode = value
            }
        }
    }

    func teardown() {
        binder.cancel()
        neededBinder.cancel()
        undoListenerTask?.cancel()
        undoListenerTask = nil
        sortPrefsTask?.cancel()
        sortPrefsTask = nil
    }

    /// v0.6.1: toggle the +/- button on the Items list. Maps to
    /// `markPurchasedAcrossAllStoresNoRecord` when the item is currently
    /// needed (the "−" branch -- list-state action, no PurchaseRecord),
    /// or `markNeededAcrossAllStores` when it's not (the "+" branch).
    func toggleNeededAtAllStores(itemId: String, currentlyNeeded: Bool) {
        Task { [itemRepository] in
            do {
                if currentlyNeeded {
                    try await itemRepository.markPurchasedAcrossAllStoresNoRecord(itemId: itemId)
                } else {
                    try await itemRepository.markNeededAcrossAllStores(itemId: itemId)
                }
            } catch {
                // Sync writes are best-effort here -- the live observation
                // will roll the UI back if the write didn't land.
            }
        }
    }

    func setSortMode(_ mode: SortMode) {
        preferencesRepository.setItemsListSortMode(mode)
    }

    private func refresh() {
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let filtered: [ItemWithCategoryAndStores]
        if needle.isEmpty {
            filtered = rawItems
        } else {
            filtered = rawItems.filter { row in
                row.item.name.lowercased().contains(needle) ||
                (row.item.brand?.lowercased().contains(needle) == true)
            }
        }
        switch sortMode {
        case .alphabetic:
            items = filtered.sorted { $0.item.name.lowercased() < $1.item.name.lowercased() }
            sections = []
        case .category:
            items = []
            sections = Self.groupIntoSections(filtered)
        }
    }

    func undoItemDelete(_ event: UndoEvent) {
        switch event {
        case .itemDeleted(let itemId, _):
            Task { try? await itemRepository.undoSoftDelete(id: itemId) }
        }
        pendingUndo = nil
    }

    func dismissUndo() {
        pendingUndo = nil
    }

    private static func groupIntoSections(_ rows: [ItemWithCategoryAndStores]) -> [ItemsCategorySection] {
        var withCat: [String: (name: String, key: String?, rows: [ItemWithCategoryAndStores])] = [:]
        var withoutCat: [ItemWithCategoryAndStores] = []
        for row in rows {
            if let cat = row.category {
                if var bucket = withCat[cat.id] {
                    bucket.rows.append(row)
                    withCat[cat.id] = bucket
                } else {
                    withCat[cat.id] = (name: cat.name, key: cat.nameKey, rows: [row])
                }
            } else {
                withoutCat.append(row)
            }
        }
        let alive = withCat.values
            .map { ItemsCategorySection(
                categoryName: $0.name,
                categoryNameKey: $0.key,
                rows: $0.rows.sorted { $0.item.name.lowercased() < $1.item.name.lowercased() }
            ) }
            .sorted { $0.categoryName.lowercased() < $1.categoryName.lowercased() }
        if withoutCat.isEmpty { return alive }
        let trailing = ItemsCategorySection(
            categoryName: itemsUncategorisedSentinel,
            categoryNameKey: nil,
            rows: withoutCat.sorted { $0.item.name.lowercased() < $1.item.name.lowercased() }
        )
        return alive + [trailing]
    }
}
