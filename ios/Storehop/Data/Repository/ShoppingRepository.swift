import Foundation
import GRDB

/// One row per live (non-archived) store, with the data the Store Picker
/// needs to render its badges and the cross-store critical banner.
struct StorePickerRow: Hashable, Sendable, Identifiable {
    let store: Store
    let neededCount: Int
    /// Items tagged to this store that the user has picked up within the
    /// active shopping session — whether the purchase happened here or at
    /// another store the item was also tagged to. Drives the "All set"
    /// affirmation when a store had things to grab and they've all been
    /// grabbed.
    let pickedUpInSessionCount: Int
    /// Names of priority-flagged items currently needed at this store.
    /// Empty if none. Drives the per-store "⚠ N critical" badge AND the
    /// cross-store banner (caller dedupes across rows).
    let criticalItemNames: [String]

    var id: String { store.id }
}

struct ShoppingRepository: Sendable {
    let shoppingDao: ShoppingDao
    let storeDao: StoreDao
    let session: any UserSessionProvider

    /// Shopping list for a single store, in that store's aisle order.
    /// Includes needed items, staples, and items purchased within the
    /// current session. Pass `Int64.max` to disable the session window.
    func shoppingListForStore(
        userId: String,
        storeId: String,
        sessionStartMs: Int64
    ) -> AsyncValueObservation<[ShoppingRow]> {
        shoppingDao.shoppingListForStore(userId: userId, storeId: storeId, sessionStartMs: sessionStartMs)
    }

    /// One row per live store with needed count + session-picked-up count
    /// + priority names. Drives the Store Picker home screen.
    ///
    /// Combines the live-stores stream with the cross-store picker-items
    /// stream and reduces them per-store. Returns AsyncStream so the
    /// caller binds with `for await rows in repo.observeStorePickerRows(...)`.
    func observeStorePickerRows(
        userId: String,
        sessionStartMs: Int64
    ) -> AsyncStream<[StorePickerRow]> {
        let stores = storeDao.observeAll(userId: userId, includeArchived: false)
        let items = shoppingDao.observeStorePickerItems(userId: userId, sessionStartMs: sessionStartMs)
        return Self.combineLatest(stores, items) { stores, items in
            let byStore = Dictionary(grouping: items, by: \.storeId)
            return stores.map { store in
                let rows = byStore[store.id, default: []]
                let needed = rows.filter { $0.isNeeded }
                let pickedUp = rows.filter { !$0.isNeeded }
                return StorePickerRow(
                    store: store,
                    neededCount: needed.count,
                    pickedUpInSessionCount: pickedUp.count,
                    criticalItemNames: needed.filter { $0.isPriority }.map { $0.itemName }
                )
            }
        }
    }

    // MARK: - combineLatest helper

    /// AsyncStream port of Kotlin Flow's `combine` — emits whenever either
    /// upstream produces a new value, applying `transform` to the most
    /// recent pair. Yields nothing until both upstreams have emitted at
    /// least once.
    private static func combineLatest<A, B, R>(
        _ first: AsyncValueObservation<A>,
        _ second: AsyncValueObservation<B>,
        transform: @escaping @Sendable (A, B) -> R
    ) -> AsyncStream<R> where A: Sendable, B: Sendable, R: Sendable {
        AsyncStream { continuation in
            let state = CombineState<A, B>()

            let firstTask = Task {
                do {
                    for try await value in first {
                        let pair = await state.updateA(value)
                        if let pair {
                            continuation.yield(transform(pair.0, pair.1))
                        }
                    }
                } catch {
                    continuation.finish()
                }
            }
            let secondTask = Task {
                do {
                    for try await value in second {
                        let pair = await state.updateB(value)
                        if let pair {
                            continuation.yield(transform(pair.0, pair.1))
                        }
                    }
                } catch {
                    continuation.finish()
                }
            }

            continuation.onTermination = { _ in
                firstTask.cancel()
                secondTask.cancel()
            }
        }
    }
}

/// Holds the most-recent value from each upstream so combineLatest can
/// emit only when both have produced at least once. Actor isolation
/// prevents the two upstream Tasks from racing each other.
private actor CombineState<A: Sendable, B: Sendable> {
    private var a: A?
    private var b: B?

    func updateA(_ value: A) -> (A, B)? {
        a = value
        guard let a, let b else { return nil }
        return (a, b)
    }

    func updateB(_ value: B) -> (A, B)? {
        b = value
        guard let a, let b else { return nil }
        return (a, b)
    }
}
