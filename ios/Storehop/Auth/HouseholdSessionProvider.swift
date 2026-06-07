import Foundation

/// v0.7.0 multi-user: the *active household* the user is currently scoped to.
///
/// Mirrors the Android `HouseholdSessionProvider` interface — same contract,
/// same single-source-of-truth invariant (the production implementation is
/// the same Singleton that publishes `UserSessionProvider.userIdStream`, so
/// observing repos never see a stale household for a fresh uid).
///
/// Every repository query is filtered by `householdId` going forward. For
/// single-user households (everyone post-migration who hasn't joined another
/// household via invite) the value equals the user's uid, so the swap from
/// `userId`-scoped to `householdId`-scoped queries is behaviour-preserving.
///
/// `householdId` is nil when the user is signed out / not yet bootstrapped
/// (matches `UserSessionProvider.currentUserId` semantics).
protocol HouseholdSessionProvider: Sendable {
    /// Snapshot of the active household id; nil = not signed in / not
    /// bootstrapped. Used by repository read paths to scope queries.
    var currentHouseholdId: String? { get async }

    /// Stream of household-id changes. Emits the current value immediately on
    /// subscription, then again whenever the household flips (first-launch
    /// bootstrap, invite-accept, leave-household). ViewModels switch their
    /// reactive observations on each emission.
    var householdIdStream: AsyncStream<String?> { get }
}

extension HouseholdSessionProvider {
    /// Resolves the current household or throws `NoActiveHouseholdError` if
    /// absent. Repository write operations call this at the top of their
    /// transactions so a partial write never lands outside a household.
    func requireHouseholdId() async throws -> String {
        guard let hid = await currentHouseholdId else {
            throw NoActiveHouseholdError()
        }
        return hid
    }
}

struct NoActiveHouseholdError: Error, Equatable {}

/// v0.7.0 Phase 3 write-side hook used by `HouseholdRepository` after an
/// invite-accept or leave-household action. Calling `switchToHousehold`
/// re-runs the same sync gate the auth listener uses (peek + pull or
/// claim) against the new household and then publishes it via
/// `HouseholdSessionProvider.householdIdStream`. Implemented by
/// `FirebaseAuthSessionProvider` in production.
protocol HouseholdSwitcher: Sendable {
    func switchToHousehold(_ newHouseholdId: String) async
}

/// Pre-Firebase / test session provider that mirrors the user-session's uid
/// onto the household so single-member-household behaviour holds in tests.
/// Real production builds use `FirebaseAuthSessionProvider` (Phase 4 + Phase 2
/// bootstrap) which resolves a real membership row from
/// `HouseholdMemberDao` before publishing the active household.
final class LocalOnlyHouseholdSessionProvider: HouseholdSessionProvider, HouseholdSwitcher, @unchecked Sendable {
    private let lock = NSLock()
    private var _hid: String?
    private var continuations: [UUID: AsyncStream<String?>.Continuation] = [:]

    init(initialHouseholdId: String? = DatabaseSeeder.localOnlyUserId) {
        self._hid = initialHouseholdId
    }

    var currentHouseholdId: String? {
        get async {
            lock.lock()
            defer { lock.unlock() }
            return _hid
        }
    }

    var householdIdStream: AsyncStream<String?> {
        AsyncStream { continuation in
            let id = UUID()
            lock.lock()
            let snapshot = _hid
            continuations[id] = continuation
            lock.unlock()
            continuation.yield(snapshot)
            continuation.onTermination = { [weak self] _ in
                self?.lock.lock()
                self?.continuations.removeValue(forKey: id)
                self?.lock.unlock()
            }
        }
    }

    /// Test-only helper: change the active household and notify every
    /// subscriber. The production switcher (`FirebaseAuthSessionProvider`)
    /// also runs the sync gate before publishing; this stub just publishes
    /// because tests don't need the sync round-trip.
    func switchToHousehold(_ newHouseholdId: String) async {
        lock.lock()
        _hid = newHouseholdId
        let snapshot = Array(continuations.values)
        lock.unlock()
        for c in snapshot {
            c.yield(newHouseholdId)
        }
    }
}
