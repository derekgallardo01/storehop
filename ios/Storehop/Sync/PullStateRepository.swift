import Foundation

/// Per-uid pull-state store. Phase 10 will persist this to a small disk file
/// so a process death mid-pull is recoverable; Phase 4 uses an in-memory
/// implementation, which is fine because:
///   - The session provider sets state synchronously around each pull.
///   - The SyncEngine reads the live state from this same instance.
///   - On cold launch, every uid starts at `.needed` and the session
///     provider re-runs sync, which re-populates the state.
protocol PullStateRepository: Sendable {
    /// Current state for `uid`, or `.needed` if no entry exists.
    func current(for uid: String) async -> PullState

    /// Replace the state for `uid`. Idempotent.
    func set(_ state: PullState, for uid: String) async

    /// v0.8.1.2 diagnostic: last failure reason string for `uid`, or nil if
    /// the last set didn't carry one. Surfaces in the Settings sync banner
    /// so users can copy/screenshot the actual Firestore error instead of
    /// the generic "Cloud sync incomplete" copy.
    func lastFailureReason(for uid: String) async -> String?

    /// Replace the state for `uid` AND record a failure reason. Use this
    /// instead of `set(_:for:)` when the new state is `.failed`. The reason
    /// is cleared when the state moves away from `.failed`.
    func set(_ state: PullState, for uid: String, reason: String?) async

    /// Stream of state changes for a uid. Emits the current value on
    /// subscription. Used by the Settings banner (FAILED → show Retry).
    func observe(_ uid: String) -> AsyncStream<PullState>
}

/// Process-lifetime in-memory implementation. Phase 10 will replace this
/// with a disk-backed version; the protocol shape stays the same.
final actor InMemoryPullStateRepository: PullStateRepository {
    private var states: [String: PullState] = [:]
    private var reasons: [String: String] = [:]
    private var continuations: [String: [UUID: AsyncStream<PullState>.Continuation]] = [:]

    func current(for uid: String) -> PullState {
        states[uid] ?? .needed
    }

    func lastFailureReason(for uid: String) -> String? { reasons[uid] }

    func set(_ state: PullState, for uid: String) {
        set(state, for: uid, reason: nil)
    }

    func set(_ state: PullState, for uid: String, reason: String?) {
        states[uid] = state
        if state == .failed, let reason {
            reasons[uid] = reason
        } else if state != .failed {
            reasons[uid] = nil
        }
        for (_, continuation) in continuations[uid] ?? [:] {
            continuation.yield(state)
        }
    }

    nonisolated func observe(_ uid: String) -> AsyncStream<PullState> {
        AsyncStream { continuation in
            let id = UUID()
            Task {
                await register(uid: uid, id: id, continuation: continuation)
            }
            continuation.onTermination = { @Sendable _ in
                Task {
                    await self.unregister(uid: uid, id: id)
                }
            }
        }
    }

    private func register(uid: String, id: UUID, continuation: AsyncStream<PullState>.Continuation) {
        continuations[uid, default: [:]][id] = continuation
        continuation.yield(states[uid] ?? .needed)
    }

    private func unregister(uid: String, id: UUID) {
        continuations[uid]?[id] = nil
        if continuations[uid]?.isEmpty == true {
            continuations[uid] = nil
        }
    }
}

/// Disk-backed `PullStateRepository` using `UserDefaults`, mirroring Android's
/// DataStore-backed `PullStateRepository` (`pull_state_<uid>`). Persisting
/// `.succeeded` across app launches enables instant local-first cold starts
/// (<50ms) without re-gating the session behind a full cloud peek + pull.
final actor UserDefaultsPullStateRepository: PullStateRepository {
    nonisolated(unsafe) private let defaults: UserDefaults
    private var states: [String: PullState] = [:]
    private var reasons: [String: String] = [:]
    private var continuations: [String: [UUID: AsyncStream<PullState>.Continuation]] = [:]

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func current(for uid: String) -> PullState {
        if let cached = states[uid] {
            return cached
        }
        let persisted = defaults.string(forKey: stateKey(for: uid))
            .flatMap(PullState.init(rawValue:)) ?? .needed
        states[uid] = persisted
        return persisted
    }

    func lastFailureReason(for uid: String) -> String? {
        if let cached = reasons[uid] {
            return cached
        }
        let persisted = defaults.string(forKey: reasonKey(for: uid))
        if let persisted {
            reasons[uid] = persisted
        }
        return persisted
    }

    func set(_ state: PullState, for uid: String) {
        set(state, for: uid, reason: nil)
    }

    func set(_ state: PullState, for uid: String, reason: String?) {
        states[uid] = state
        defaults.set(state.rawValue, forKey: stateKey(for: uid))
        if state == .failed, let reason {
            reasons[uid] = reason
            defaults.set(reason, forKey: reasonKey(for: uid))
        } else if state != .failed {
            reasons[uid] = nil
            defaults.removeObject(forKey: reasonKey(for: uid))
        }
        for (_, continuation) in continuations[uid] ?? [:] {
            continuation.yield(state)
        }
    }

    nonisolated func observe(_ uid: String) -> AsyncStream<PullState> {
        AsyncStream { continuation in
            let id = UUID()
            Task {
                await register(uid: uid, id: id, continuation: continuation)
            }
            continuation.onTermination = { @Sendable _ in
                Task {
                    await self.unregister(uid: uid, id: id)
                }
            }
        }
    }

    private func register(uid: String, id: UUID, continuation: AsyncStream<PullState>.Continuation) {
        let initial = current(for: uid)
        continuations[uid, default: [:]][id] = continuation
        continuation.yield(initial)
    }

    private func unregister(uid: String, id: UUID) {
        continuations[uid]?[id] = nil
        if continuations[uid]?.isEmpty == true {
            continuations[uid] = nil
        }
    }

    private func stateKey(for uid: String) -> String { "pull_state_\(uid)" }
    private func reasonKey(for uid: String) -> String { "pull_failure_reason_\(uid)" }
}

