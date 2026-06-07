import Foundation
import FirebaseFirestore

/// Cloud sync for [UserPreferencesRepository] — added in v0.7.1 so the
/// user's theme / language / sort-mode choices survive an uninstall +
/// reinstall across signing-cert boundaries.
///
/// Per-user, not per-household: preferences are personal. Doc path:
/// `/userPrefs/{uid}`. Security rules in `firestore.rules` scope read +
/// write to `request.auth.uid == uid`.
///
/// Mirrors Android `UserPreferencesSync` line-for-line:
///
/// ## Reconcile semantics — `reconcile(uid:)`
///
///  1. Pull `/userPrefs/{uid}`.
///  2. If cloud doc is **absent** OR `local.updatedAt > cloud.updatedAt`
///     → push local → cloud. This is the load-bearing case for the
///     first launch of v0.7.1: existing prefs (from a v0.7.0 install)
///     get captured to cloud on the next auth-state-stream emission
///     even without the user changing any setting.
///  3. If `cloud.updatedAt > local.updatedAt` → write cloud → local.
///     UI observes the snapshot stream and re-renders.
///  4. If equal → no-op.
///
/// After reconcile, [reconcile] starts a continuous observe-and-push
/// loop with a 500 ms debounce. Cancels any prior loop so cycling auth
/// states doesn't leak tasks.
///
/// ## Failure mode
///
/// Network errors / `PERMISSION_DENIED` are swallowed (logged at WARN).
/// Local state is intact; next reconcile retries. Keeps the app
/// functional if Firestore rules haven't deployed yet — preferences
/// just don't sync until they have.
final class UserPreferencesSync: @unchecked Sendable {
    private let firestore: Firestore
    private let prefs: any UserPreferencesRepository

    private let lock = NSLock()
    private var pushLoop: Task<Void, Never>?

    init(firestore: Firestore, prefs: any UserPreferencesRepository) {
        self.firestore = firestore
        self.prefs = prefs
    }

    /// Pull cloud + LWW-compare, push if needed, then start the
    /// continuous observe-and-push loop. Idempotent.
    func reconcile(uid: String) async {
        guard !uid.isEmpty else { return }

        // 1. Pull cloud.
        let docRef = firestore.collection(Self.collection).document(uid)
        let cloudDto: UserPreferencesDto?
        do {
            let snap = try await docRef.getDocument()
            cloudDto = if snap.exists {
                try? snap.data(as: UserPreferencesDto.self)
            } else {
                nil
            }
        } catch {
            // Permission denied / network error — log and bail; the
            // push loop below still starts so the next local edit
            // retries.
            print("[UserPreferencesSync] reconcile pull failed: \(error.localizedDescription)")
            startPushLoop(uid: uid)
            return
        }

        // 2. LWW compare.
        let local = prefs.snapshot
        do {
            if cloudDto == nil {
                try await pushSnapshot(uid: uid, snapshot: local)
            } else if let cloud = cloudDto {
                if local.updatedAt > cloud.updatedAt {
                    try await pushSnapshot(uid: uid, snapshot: local)
                } else if cloud.updatedAt > local.updatedAt {
                    await MainActor.run { prefs.applyRemoteSnapshot(cloud.toSnapshot()) }
                }
                // else equal → no-op
            }
        } catch {
            print("[UserPreferencesSync] reconcile push failed: \(error.localizedDescription)")
        }

        // 3. Start the long-lived observe-and-push loop.
        startPushLoop(uid: uid)
    }

    /// Synchronously push the current snapshot — skipping the 500 ms
    /// debounce. Called by the upcoming `SyncEngine.flushAllPending`
    /// before its "Safe to uninstall" signal so the user-prefs doc
    /// reflects truth even if the user toggled something a moment
    /// earlier.
    func flushPending(uid: String) async {
        guard !uid.isEmpty else { return }
        do {
            try await pushSnapshot(uid: uid, snapshot: prefs.snapshot)
        } catch {
            print("[UserPreferencesSync] flushPending failed: \(error.localizedDescription)")
        }
    }

    /// Tear down on sign-out. The push loop holds a reference to the
    /// uid that's no longer authoritative.
    func stop() {
        lock.lock()
        defer { lock.unlock() }
        pushLoop?.cancel()
        pushLoop = nil
    }

    private func startPushLoop(uid: String) {
        lock.lock()
        pushLoop?.cancel()
        pushLoop = Task { [weak self] in
            guard let self else { return }
            // Drop the first emission (reconcile already pushed if
            // needed). Use a flag rather than .dropFirst since the
            // snapshot stream's initial emission is the only one we
            // skip.
            var seenFirst = false
            var debounceTask: Task<Void, Never>?
            for await snapshot in self.prefs.snapshotStream {
                if !seenFirst {
                    seenFirst = true
                    continue
                }
                // Debounce: cancel the previous pending push, start a
                // new one. Only the trailing emission (~500 ms after
                // the last edit) actually hits Firestore.
                debounceTask?.cancel()
                debounceTask = Task { [weak self] in
                    guard let self else { return }
                    do {
                        try await Task.sleep(nanoseconds: Self.debounceNanos)
                        try await self.pushSnapshot(uid: uid, snapshot: snapshot)
                    } catch {
                        // Cancellation or push failure — drop quietly.
                    }
                }
            }
        }
        lock.unlock()
    }

    private func pushSnapshot(uid: String, snapshot: UserPreferencesSnapshot) async throws {
        try await firestore.collection(Self.collection)
            .document(uid)
            .setData(from: snapshot.toDto())
    }

    private static let collection = "userPrefs"
    private static let debounceNanos: UInt64 = 500_000_000 // 500 ms
}
