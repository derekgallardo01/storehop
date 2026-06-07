import Foundation

/// v0.4 pull-side sync. On each household change the session provider asks:
///   - `peek(householdId:)`: does cloud have any data for this household?
///     (cheap query — a `LIMIT 1` over `users/{householdId}/stores`).
///   - `pullForHousehold(_:)`: if peek returned true, batch-fetch every
///     entity under this household and write them through `PullWriteDao` in
///     one transaction. Either every entity lands or none do.
///
/// Branch logic in `FirebaseAuthSessionProvider`:
///   peek=true  → pull (cloud is authoritative; skip orphan-claim).
///   peek=false → run the orphan-claim path (`LocalOnlyMigrationDao`).
///                Local rows then push to populate the empty cloud.
///
/// v0.7.0: the wire path stays `/users/{X}/...` — see `SyncCollections` —
/// with `X` now interpreted as `householdId`. Single-member households
/// have `householdId == userId` so existing cloud data persists at the
/// same path.
protocol PullCoordinator: Sendable {
    /// Cheap "does this household have data in cloud?" check.
    /// Implementation queries Firestore for one document under the household.
    func peek(householdId: String) async throws -> Bool

    /// Batch-fetch every entity under `householdId` and write through
    /// `PullWriteDao`. Returns success/failure so the session provider can
    /// update PullState accordingly (FAILED → user sees a Retry banner).
    func pullForHousehold(_ householdId: String) async -> PullResult
}

enum PullResult: Sendable, Equatable {
    case success
    case failure(reason: String)
}

/// Test / preview stub. Pretends cloud is always empty so the orphan-claim
/// path runs. Production uses `FirestorePullCoordinator`.
struct StubPullCoordinator: PullCoordinator {
    func peek(householdId: String) async throws -> Bool { false }
    func pullForHousehold(_ householdId: String) async -> PullResult { .success }
}
