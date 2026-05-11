import Foundation

/// Firebase-backed `UserSessionProvider`. Construction has the side effects:
///   1. Subscribes to the Firebase auth-state stream and pumps each uid
///      change through a sync gate before publishing.
///   2. Kicks off `signInAnonymously()` if no user is present, so the app
///      gets a uid quickly on first launch.
///   3. v0.7.0 Phase 2 — bootstraps the user's active household. After
///      each uid resolves we look up `HouseholdMemberDao.activeMembershipFor`
///      to find an existing membership. If none exists we create a personal
///      household (`hid = uid`) and insert the corresponding local row;
///      sync pushes it to Firestore on the first push tick after publish.
///   4. Runs the appropriate sync flow for each (uid, householdId) pair
///      *before* the public streams flip. Both ids are published together
///      so observing repos never see a uid mismatched with the household.
///      The peek + branch:
///        - cloud has data → pull (skip orphan-claim; cloud wins).
///        - cloud is empty → orphan-claim, then push populates cloud.
///      Closes the silent-corruption bug where a fresh-install user signing
///      in to an existing Google account would have their cloud overwritten
///      by the locally-seeded baseline.
///   5. `switchToHousehold(_:)` exposes a write-side hook to
///      `HouseholdRepository`'s invite-accept / leave-household flow so the
///      sync gate re-runs against the new household, then the published
///      household id flips.
///
/// Anonymous accounts persist across app restarts (Firebase caches the
/// credential to disk), so `signInAnonymously()` only fires on first launch
/// or after "wipe app data."
///
/// Mirrors Android `FirebaseAuthSessionProvider` 1:1.
final actor FirebaseAuthSessionProvider: UserSessionProvider, HouseholdSessionProvider, HouseholdSwitcher {
    private let authClient: any FirebaseAuthClient
    private let migrationDao: LocalOnlyMigrationDao
    private let householdMemberDao: HouseholdMemberDao
    private let pullCoordinator: any PullCoordinator
    private let pullStateRepo: any PullStateRepository
    private let clock: any Clock

    /// Disk-cached uid on cold launch (returning users), or nil. Updates
    /// only after `runSync` completes for the new uid, so observers
    /// never see a uid that hasn't been claimed yet.
    private var publishedUserId: String?

    /// Resolved active household id. Published in lock-step with
    /// `publishedUserId` so observers see consistent (uid, hid) pairs.
    private var publishedHouseholdId: String?

    /// Mutable continuations for the public uid stream.
    private var uidContinuations: [UUID: AsyncStream<String?>.Continuation] = [:]

    /// Mutable continuations for the public household stream.
    private var hidContinuations: [UUID: AsyncStream<String?>.Continuation] = [:]

    /// The pump task subscribed to `authClient.authStateStream`. Cancelled
    /// in `shutdown()` (actors don't get deinit hooks today).
    private var pumpTask: Task<Void, Never>?

    init(
        authClient: any FirebaseAuthClient,
        migrationDao: LocalOnlyMigrationDao,
        householdMemberDao: HouseholdMemberDao,
        pullCoordinator: any PullCoordinator,
        pullStateRepo: any PullStateRepository,
        clock: any Clock
    ) {
        self.authClient = authClient
        self.migrationDao = migrationDao
        self.householdMemberDao = householdMemberDao
        self.pullCoordinator = pullCoordinator
        self.pullStateRepo = pullStateRepo
        self.clock = clock
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
        for (_, continuation) in uidContinuations {
            continuation.finish()
        }
        uidContinuations.removeAll()
        for (_, continuation) in hidContinuations {
            continuation.finish()
        }
        hidContinuations.removeAll()
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
                await self.registerUid(id: id, continuation: continuation)
            }
            continuation.onTermination = { @Sendable _ in
                Task { await self.unregisterUid(id: id) }
            }
        }
    }

    private func registerUid(id: UUID, continuation: AsyncStream<String?>.Continuation) {
        uidContinuations[id] = continuation
        // Replay the current value so a late subscriber doesn't sit at nil.
        continuation.yield(publishedUserId)
    }

    private func unregisterUid(id: UUID) {
        uidContinuations[id] = nil
    }

    // MARK: - HouseholdSessionProvider

    nonisolated var currentHouseholdId: String? {
        get async { await self.snapshotPublishedHid() }
    }

    private func snapshotPublishedHid() -> String? { publishedHouseholdId }

    nonisolated var householdIdStream: AsyncStream<String?> {
        AsyncStream { continuation in
            let id = UUID()
            Task {
                await self.registerHid(id: id, continuation: continuation)
            }
            continuation.onTermination = { @Sendable _ in
                Task { await self.unregisterHid(id: id) }
            }
        }
    }

    private func registerHid(id: UUID, continuation: AsyncStream<String?>.Continuation) {
        hidContinuations[id] = continuation
        continuation.yield(publishedHouseholdId)
    }

    private func unregisterHid(id: UUID) {
        hidContinuations[id] = nil
    }

    // MARK: - HouseholdSwitcher

    /// v0.7.0 Phase 3: re-point the active household after an invite-accept
    /// (Amanda joining Mike's household) or a leave-household action.
    /// Re-runs the same sync gate the auth listener uses so the cloud
    /// pull executes against the new path and pullState transitions
    /// correctly. Updates the published household only after sync
    /// completes so observing repos don't briefly see the new id with
    /// stale local data.
    ///
    /// No-op if no user is signed in.
    nonisolated func switchToHousehold(_ newHouseholdId: String) async {
        await self.performSwitch(to: newHouseholdId)
    }

    private func performSwitch(to newHouseholdId: String) async {
        guard let uid = publishedUserId else { return }
        if publishedHouseholdId == newHouseholdId { return }
        await runSync(uid: uid, householdId: newHouseholdId)
        publishedHouseholdId = newHouseholdId
        broadcastHid(newHouseholdId)
    }

    // MARK: - Auth pump

    /// Reacts to an emission from `authClient.authStateStream`. Runs the
    /// household resolution + pull-or-claim sync for new uids before
    /// flipping the published streams, so the next observer query
    /// already sees the right rows.
    private func handleAuthChange(newUid: String?) async {
        // Sign-out: nothing to migrate. Clear both flows together so
        // observers never see (uid=nil, hid="prev").
        guard let newUid else {
            if publishedUserId != nil {
                publishedUserId = nil
                publishedHouseholdId = nil
                broadcastUid(nil)
                broadcastHid(nil)
            }
            return
        }

        // Resolve the household locally — cheap DAO lookup. Must happen
        // before any short-circuit so we have a valid value to publish
        // even on the cold-launch fast path.
        let resolvedHouseholdId = await resolveHousehold(for: newUid)

        // Cold-launch short-circuit: when publishedUserId already matches
        // AND pullState is SUCCEEDED, skip the Firestore peek. For
        // NEEDED / IN_PROGRESS / FAILED we still run sync — otherwise
        // push stays paused forever and pendingSync rows pile up.
        var canSkipSync = false
        if publishedUserId == newUid {
            let state = await pullStateRepo.current(for: newUid)
            canSkipSync = (state == .succeeded)
        }
        if !canSkipSync {
            await runSync(uid: newUid, householdId: resolvedHouseholdId)
        }

        // Publish both ids together so observers never see a mismatch.
        publishedHouseholdId = resolvedHouseholdId
        publishedUserId = newUid
        broadcastHid(resolvedHouseholdId)
        broadcastUid(newUid)
    }

    /// Resolve the user's active household. Phase 2: local-first.
    ///   1. If a local household_members row exists for this uid, reuse it.
    ///   2. Otherwise create a personal household with hid = uid and
    ///      insert the local membership row. Sync pushes it to Firestore
    ///      on the first push tick after the household stream publishes.
    ///
    /// Phase 3+ adds a Firestore peek (`/memberships/{uid}/households`)
    /// as a fallback between (1) and (2) so a second device of the same
    /// Google account inherits the existing household without needing the
    /// invite-code dance. For v0.7.0 ship we accept the limitation that
    /// the second device has to be re-invited.
    private func resolveHousehold(for uid: String) async -> String {
        if let existing = try? await householdMemberDao.activeMembershipFor(uid: uid) {
            return existing.householdId
        }
        let now = clock.nowMs()
        let member = HouseholdMember(
            uid: uid,
            householdId: uid,
            displayName: nil,
            joinedAt: now,
            isOwner: true,
            createdAt: now,
            updatedAt: now,
            deletedAt: nil,
            pendingSync: true
        )
        do {
            try await householdMemberDao.upsert(member)
        } catch {
            // Membership-insert failure is non-fatal — production loses
            // the local row but the cloud-side membership will be
            // recreated on the next bootstrap. Log path could go here
            // in production builds.
        }
        return uid
    }

    /// Peek + branch + claim, mirroring Android. Always sets a final
    /// PullState. The SyncEngine gates push jobs on this state, so push is
    /// paused until SUCCEEDED. A failure here means the user can sign in
    /// but pushes are paused; Settings shows a Retry banner.
    private func runSync(uid: String, householdId: String) async {
        await pullStateRepo.set(.inProgress, for: uid)
        do {
            let cloudHasData = try await pullCoordinator.peek(householdId: householdId)
            if cloudHasData {
                let result = await pullCoordinator.pullForHousehold(householdId)
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

    private func broadcastUid(_ uid: String?) {
        for (_, continuation) in uidContinuations {
            continuation.yield(uid)
        }
    }

    private func broadcastHid(_ hid: String?) {
        for (_, continuation) in hidContinuations {
            continuation.yield(hid)
        }
    }
}
