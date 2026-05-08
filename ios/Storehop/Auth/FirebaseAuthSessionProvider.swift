import Foundation

/// Firebase-backed `UserSessionProvider`. Construction has the side effects:
///   1. Subscribes to the Firebase auth-state stream and pumps each uid
///      change through a sync gate before publishing.
///   2. Kicks off `signInAnonymously()` if no user is present, so the app
///      gets a uid quickly on first launch.
///   3. Runs the appropriate sync flow for each uid change *before* the
///      public uid stream flips. The peek + branch:
///        - cloud has data → pull (skip orphan-claim; cloud wins).
///        - cloud is empty → orphan-claim, then push populates cloud.
///      Closes the silent-corruption bug where a fresh-install user signing
///      in to an existing Google account would have their cloud overwritten
///      by the locally-seeded baseline.
///
/// Anonymous accounts persist across app restarts (Firebase caches the
/// credential to disk), so `signInAnonymously()` only fires on first launch
/// or after "wipe app data."
///
/// Mirrors Android `FirebaseAuthSessionProvider` 1:1.
final actor FirebaseAuthSessionProvider: UserSessionProvider {
    private let authClient: any FirebaseAuthClient
    private let migrationDao: LocalOnlyMigrationDao
    private let pullCoordinator: any PullCoordinator
    private let pullStateRepo: any PullStateRepository

    /// Disk-cached uid on cold launch (returning users), or nil. Updates
    /// only after `runSyncFor` completes for the new uid, so observers
    /// never see a uid that hasn't been claimed yet.
    private var publishedUserId: String?

    /// Mutable continuations for the public stream.
    private var continuations: [UUID: AsyncStream<String?>.Continuation] = [:]

    /// The pump task subscribed to `authClient.authStateStream`. Cancelled
    /// in `deinit` (well, in `shutdown()` since actors don't get deinit
    /// hooks in Swift today).
    private var pumpTask: Task<Void, Never>?

    init(
        authClient: any FirebaseAuthClient,
        migrationDao: LocalOnlyMigrationDao,
        pullCoordinator: any PullCoordinator,
        pullStateRepo: any PullStateRepository
    ) {
        self.authClient = authClient
        self.migrationDao = migrationDao
        self.pullCoordinator = pullCoordinator
        self.pullStateRepo = pullStateRepo
    }

    /// Boot the session provider. Must be called once at app start before
    /// any consumer reads `currentUserId` or subscribes to `userIdStream`.
    /// Idempotent — calling twice is a no-op.
    func start() async {
        if pumpTask != nil { return }

        // Read the disk-cached uid synchronously so cold-launch observers
        // see the right value immediately.
        publishedUserId = await authClient.currentUserId

        // First launch: nobody's signed in, kick off anonymous sign-in. The
        // auth state listener picks up the resulting uid and runs sync.
        if publishedUserId == nil {
            do {
                _ = try await authClient.signInAnonymously()
            } catch {
                // Anonymous sign-in failure is a hard failure mode; the
                // app sits without a uid until the network comes back. The
                // auth listener will fire once it succeeds.
            }
        }

        let stream = authClient.authStateStream
        pumpTask = Task { [weak self] in
            for await newUid in stream {
                guard let self else { return }
                await self.handleAuthChange(newUid: newUid)
            }
        }
    }

    /// Stop the auth-state listener and clean up subscriptions. Tests call
    /// this in tearDown; production never has a reason to.
    func shutdown() {
        pumpTask?.cancel()
        pumpTask = nil
        for (_, continuation) in continuations {
            continuation.finish()
        }
        continuations.removeAll()
    }

    // MARK: - UserSessionProvider

    nonisolated var currentUserId: String? {
        get async { await self.snapshotPublishedUid() }
    }

    private func snapshotPublishedUid() -> String? { publishedUserId }

    nonisolated var userIdStream: AsyncStream<String?> {
        AsyncStream { continuation in
            let id = UUID()
            Task {
                await self.register(id: id, continuation: continuation)
            }
            continuation.onTermination = { @Sendable _ in
                Task { await self.unregister(id: id) }
            }
        }
    }

    private func register(id: UUID, continuation: AsyncStream<String?>.Continuation) {
        continuations[id] = continuation
        // Replay the current value so a late subscriber doesn't sit at nil.
        continuation.yield(publishedUserId)
    }

    private func unregister(id: UUID) {
        continuations[id] = nil
    }

    // MARK: - Auth pump

    /// Reacts to an emission from `authClient.authStateStream`. Runs the
    /// pull-or-claim sync for new uids before flipping `publishedUserId`,
    /// so the next observer query already sees the right rows.
    private func handleAuthChange(newUid: String?) async {
        // Sign-out: nothing to migrate, propagate the nil immediately.
        guard let newUid else {
            if publishedUserId != nil {
                publishedUserId = nil
                broadcast(nil)
            }
            return
        }

        // Skip only when this uid has already been fully synced. Cold
        // launch can land here with `publishedUserId == newUid` and pull
        // state stuck at NEEDED/IN_PROGRESS/FAILED — we still need to run
        // sync, otherwise push stays paused forever.
        if publishedUserId == newUid {
            let state = await pullStateRepo.current(for: newUid)
            if state == .succeeded { return }
        }

        await runSync(for: newUid)
        publishedUserId = newUid
        broadcast(newUid)
    }

    /// Peek + branch + claim, mirroring Android. Always sets a final
    /// PullState. The SyncEngine gates push jobs on this state, so push is
    /// paused until SUCCEEDED. A failure here means the user can sign in
    /// but pushes are paused; Settings shows a Retry banner.
    private func runSync(for uid: String) async {
        await pullStateRepo.set(.inProgress, for: uid)
        do {
            let cloudHasData = try await pullCoordinator.peek(uid: uid)
            if cloudHasData {
                let result = await pullCoordinator.pullForUid(uid)
                switch result {
                case .success:
                    await pullStateRepo.set(.succeeded, for: uid)
                case .failure:
                    await pullStateRepo.set(.failed, for: uid)
                }
            } else {
                await runClaims(for: uid)
                await pullStateRepo.set(.succeeded, for: uid)
            }
        } catch {
            // Peek failure or unexpected error during sync. Fail closed:
            // PullState=FAILED keeps push paused so seeded data can't leak
            // to the cloud.
            await pullStateRepo.set(.failed, for: uid)
        }
    }

    /// Re-stamp local-only and orphan-uid rows onto the active uid. Used
    /// when cloud is empty (first-ever Google sign-in for this account).
    /// Catches errors so a migration failure doesn't permanently trap the
    /// app on the previous uid — we still proceed; the data is just
    /// invisible until the next attempt.
    private func runClaims(for uid: String) async {
        do {
            let localOnly = try await migrationDao.countLocalOnlyStores()
            if localOnly > 0 {
                try await migrationDao.claimAllLocalOnlyRowsAs(uid: uid)
            }
            let orphans = try await migrationDao.countOrphanStores(uid: uid)
            if orphans > 0 {
                try await migrationDao.claimAllOrphanRowsAs(uid: uid)
            }
        } catch {
            // Swallow — see doc comment.
        }
    }

    private func broadcast(_ uid: String?) {
        for (_, continuation) in continuations {
            continuation.yield(uid)
        }
    }
}
