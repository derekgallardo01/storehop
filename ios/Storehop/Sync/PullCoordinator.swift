import Foundation

/// v0.4 pull-side sync. On each uid change the session provider asks:
///   - `peek(uid)`: does cloud have any data for this uid? (cheap query —
///     a `LIMIT 1` over `users/{uid}/items`).
///   - `pullForUid(uid)`: if peek returned true, batch-fetch every entity
///     under this uid and write them through `PullWriteDao` in one
///     transaction. Either every entity lands or none do.
///
/// Branch logic in `FirebaseAuthSessionProvider`:
///   peek=true  → pull (cloud is authoritative; skip orphan-claim).
///   peek=false → run the orphan-claim path (`LocalOnlyMigrationDao`).
///                Local rows then push to populate the empty cloud.
///
/// Phase 4 ships a stub that always returns `peek=false` and `Success` — so
/// fresh devices behave as if cloud is empty (claim path runs). Phase 10
/// implements the real Firestore reads.
protocol PullCoordinator: Sendable {
    /// Cheap "does this uid have data in cloud?" check. Phase 10
    /// implementation queries Firestore for one item under the uid.
    func peek(uid: String) async throws -> Bool

    /// Batch-fetch every entity under `uid` and write through `PullWriteDao`.
    /// Returns success/failure so the session provider can update PullState
    /// accordingly (FAILED → user sees a Retry banner).
    func pullForUid(_ uid: String) async -> PullResult
}

enum PullResult: Sendable, Equatable {
    case success
    case failure(reason: String)
}

/// Test / preview stub. Pretends cloud is always empty so the orphan-claim
/// path runs. Production uses `FirestorePullCoordinator` (Phase 10).
struct StubPullCoordinator: PullCoordinator {
    func peek(uid: String) async throws -> Bool { false }
    func pullForUid(_ uid: String) async -> PullResult { .success }
}
