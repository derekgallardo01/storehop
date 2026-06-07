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
