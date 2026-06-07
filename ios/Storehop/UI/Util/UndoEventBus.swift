import Foundation

/// Cross-screen undo events. Today's only producer is `ItemFormViewModel`
/// after a successful `softDelete`; the consumer is `ItemsListViewModel`
/// which the form pops back to and which shows an UNDO snackbar.
///
/// Each event is delivered at most once per subscriber. Within-screen undo
/// (purchase + undo on Shop-at-Store, store delete + undo on Store Picker)
/// doesn't go through here — those screens own both ends and use local
/// state.
enum UndoEvent: Sendable, Hashable {
    /// Item was soft-deleted from the form. The list screen surfaces UNDO.
    case itemDeleted(itemId: String, itemName: String)
}

/// Process-scoped event bus. Backed by an actor so producers and
/// consumers serialize without manual locking.
final actor UndoEventBus {
    private var continuations: [UUID: AsyncStream<UndoEvent>.Continuation] = [:]

    func emit(_ event: UndoEvent) {
        for (_, continuation) in continuations {
            continuation.yield(event)
        }
    }

    nonisolated func events() -> AsyncStream<UndoEvent> {
        AsyncStream { continuation in
            let id = UUID()
            Task { await self.register(id: id, continuation: continuation) }
            continuation.onTermination = { @Sendable _ in
                Task { await self.unregister(id: id) }
            }
        }
    }

    private func register(id: UUID, continuation: AsyncStream<UndoEvent>.Continuation) {
        continuations[id] = continuation
    }

    private func unregister(id: UUID) {
        continuations[id] = nil
    }
}
