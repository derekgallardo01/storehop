import Foundation

/// Helper for the Kotlin Flow `flatMapLatest` pattern: re-subscribe to a
/// per-uid stream whenever the session uid changes. ViewModels call
/// `bind(session:) { uid in stream(uid) } onValue: { value in ... }` and
/// the helper handles cancelling the prior inner subscription on uid
/// change, so they don't have to manage two Tasks themselves.
///
/// Usage:
///
///     binder.bind(session: container.session) { uid in
///         repo.observeAll(userId: uid)
///     } onValue: { items in
///         self.items = items
///     }
///
/// Cancelling the binder cancels both the outer uid loop and the inner
/// per-uid subscription. ViewModels store one binder per stream and call
/// `cancel()` in their tearDown.
final class SessionBinder: @unchecked Sendable {
    private var outerTask: Task<Void, Never>?
    private var innerTask: Task<Void, Never>?
    private let lock = NSLock()

    /// Subscribes to `session.userIdStream`. For each non-nil uid, builds
    /// the inner stream via `streamFor(uid)` and forwards every value to
    /// `onValue` on the main actor. nil uid emits `emptyValue` (so views
    /// clear their state on sign-out).
    func bind<T: Sendable>(
        session: any UserSessionProvider,
        emptyValue: T,
        streamFor: @escaping @Sendable (String) -> AsyncValueObservation<T>,
        onValue: @escaping @MainActor (T) -> Void
    ) {
        cancel()
        outerTask = Task { [weak self] in
            for await uid in session.userIdStream {
                guard let self else { return }
                self.cancelInner()
                if let uid {
                    let stream = streamFor(uid)
                    self.startInner { @Sendable in
                        do {
                            for try await value in stream {
                                await onValue(value)
                            }
                        } catch {
                            // Stream errors are surfaced silently here —
                            // GRDB ValueObservation rarely throws in
                            // production. ViewModels can add explicit
                            // error tracking if needed.
                        }
                    }
                } else {
                    await onValue(emptyValue)
                }
            }
        }
    }

    /// Variant for streams that aren't `AsyncValueObservation` (e.g.
    /// `AsyncStream<T>` from `ShoppingRepository.observeStorePickerRows`).
    func bindStream<T: Sendable>(
        session: any UserSessionProvider,
        emptyValue: T,
        streamFor: @escaping @Sendable (String) -> AsyncStream<T>,
        onValue: @escaping @MainActor (T) -> Void
    ) {
        cancel()
        outerTask = Task { [weak self] in
            for await uid in session.userIdStream {
                guard let self else { return }
                self.cancelInner()
                if let uid {
                    let stream = streamFor(uid)
                    self.startInner { @Sendable in
                        for await value in stream {
                            await onValue(value)
                        }
                    }
                } else {
                    await onValue(emptyValue)
                }
            }
        }
    }

    func cancel() {
        lock.lock(); defer { lock.unlock() }
        outerTask?.cancel()
        outerTask = nil
        innerTask?.cancel()
        innerTask = nil
    }

    private func cancelInner() {
        lock.lock(); defer { lock.unlock() }
        innerTask?.cancel()
        innerTask = nil
    }

    private func startInner(_ body: @Sendable @escaping () async -> Void) {
        lock.lock(); defer { lock.unlock() }
        innerTask = Task { await body() }
    }

    deinit {
        outerTask?.cancel()
        innerTask?.cancel()
    }
}
