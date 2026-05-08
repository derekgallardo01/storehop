import Foundation
import Observation

@Observable
@MainActor
final class ItemsListViewModel {
    var items: [ItemWithCategoryAndStores] = []
    var query: String = "" {
        didSet { refresh() }
    }

    /// Pending undo prompt from a cross-screen event (item deleted via the
    /// form). Bound to a snackbar; cleared by Undo or auto-dismiss.
    var pendingUndo: UndoEvent?

    private var rawItems: [ItemWithCategoryAndStores] = []
    private let itemRepository: ItemRepository
    private let undoEventBus: UndoEventBus
    private let session: any UserSessionProvider
    private let binder = SessionBinder()
    private var undoListenerTask: Task<Void, Never>?

    init(
        itemRepository: ItemRepository,
        undoEventBus: UndoEventBus,
        session: any UserSessionProvider
    ) {
        self.itemRepository = itemRepository
        self.undoEventBus = undoEventBus
        self.session = session
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

        // Listen for cross-screen delete events from the form.
        undoListenerTask = Task { [weak self] in
            guard let self else { return }
            for await event in await self.undoEventBus.events() {
                await MainActor.run {
                    self.pendingUndo = event
                }
            }
        }
    }

    func teardown() {
        binder.cancel()
        undoListenerTask?.cancel()
        undoListenerTask = nil
    }

    private func refresh() {
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if needle.isEmpty {
            items = rawItems
            return
        }
        items = rawItems.filter { row in
            row.item.name.lowercased().contains(needle) ||
            (row.item.brand?.lowercased().contains(needle) == true)
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
}
