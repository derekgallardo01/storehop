import Foundation

/// Provides the current authenticated user id and a stream of changes.
///
/// Phase 4 will implement this against Firebase Auth (anonymous-first sign-in
/// + Google upgrade + orphan-uid claim). For Phase 3 the protocol is enough
/// to wire repositories; tests use `LocalOnlyUserSessionProvider`.
///
/// Mirrors the Android `UserSessionProvider` interface — same contract, same
/// failure modes (writes throw `NotSignedInError` when no uid is available).
protocol UserSessionProvider: Sendable {
    /// Snapshot of the current uid; nil = not signed in. Used by repository
    /// write operations; throws via `requireSignedIn()` when nil so a
    /// mid-call sign-out doesn't silently corrupt cross-table ownership.
    var currentUserId: String? { get async }

    /// Stream of uid changes. Emits the current value immediately on
    /// subscription, then again whenever the uid flips (sign-in, sign-out,
    /// anonymous→Google upgrade). ViewModels switch their reactive
    /// observations on each emission.
    var userIdStream: AsyncStream<String?> { get }

    /// Boot the session provider — subscribe to the auth state, kick off
    /// anonymous sign-in if no user is present, etc. Idempotent. Local-only
    /// providers default to a no-op.
    func start() async
}

extension UserSessionProvider {
    func start() async {
        // Default no-op for local-only / test providers.
    }
}

extension UserSessionProvider {
    /// Resolves the current uid or throws `NotSignedInError` if absent.
    /// Repository write operations call this at the top of their
    /// transactions so a partial write never lands without an owner.
    func requireSignedIn() async throws -> String {
        guard let uid = await currentUserId else {
            throw NotSignedInError()
        }
        return uid
    }
}

struct NotSignedInError: Error, Equatable {}

/// Pre-Firebase / test session provider that always returns the local-only
/// sentinel. Real production builds use `FirebaseAuthSessionProvider` from
/// Phase 4. Tests use this directly to avoid spinning up Firebase.
final class LocalOnlyUserSessionProvider: UserSessionProvider, @unchecked Sendable {
    private let uid: String

    init(uid: String = DatabaseSeeder.localOnlyUserId) {
        self.uid = uid
    }

    var currentUserId: String? {
        get async { uid }
    }

    var userIdStream: AsyncStream<String?> {
        AsyncStream { continuation in
            continuation.yield(uid)
            // Never finishes — local-only sessions don't change uid. The
            // stream stays open for the consumer's lifetime.
        }
    }
}
