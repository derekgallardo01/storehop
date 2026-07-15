import Foundation

/// Push side of cloud sync.
///
/// Started once at app launch (from `StorehopApp`'s `.task`). For the life
/// of the process:
///
///   1. Watches `(session.userIdStream, householdSession.householdIdStream)`
///      jointly. A change in EITHER (sign-in, sign-out, anonymous→Google
///      upgrade, join/leave household) cancels the previous push jobs and
///      re-binds against the new (uid, householdId) pair.
///   2. For the active uid, observes `pullStateRepo.observe(uid)`. Push
///      jobs run only when state == `.succeeded`. While `.inProgress` /
///      `.failed` / `.needed`, push is paused — local edits accumulate
///      `pendingSync = 1` and flush when state flips back. PullState is
///      keyed on uid (matches Android), not householdId.
///   3. Per synced entity, watches that entity's
///      `observePendingPush(householdId:)` stream. For each row in each
///      emission, encodes to its DTO and writes to
///      `users/{householdId}/<collection>/{docId}` — the `users` segment
///      name is preserved from v0.4 for backward compatibility;
///      `householdId == userId` in single-member households so existing
///      cloud data persists at the same wire path. On Firestore ack,
///      `markPushed` clears `pendingSync`.
///
/// The pull-state gate is what closes the silent-corruption bug: a fresh
/// install with seeded local data sitting at `pendingSync = 1` never
/// pushes until pull has either populated the local DB from cloud (so
/// seeded rows get overwritten by cloud's authoritative copy) or
/// confirmed cloud was empty (so push freely populates cloud).
///
/// Mirrors Android `SyncEngine` 1:1.
final actor SyncEngine {
    private let firestore: any FirestoreClient
    private let session: any UserSessionProvider
    private let householdSession: any HouseholdSessionProvider
    private let pullStateRepo: any PullStateRepository

    private let itemDao: ItemDao
    private let categoryDao: CategoryDao
    private let storeDao: StoreDao
    private let xrefDao: ItemStoreXrefDao
    private let scoDao: StoreCategoryOrderDao
    private let purchaseDao: PurchaseRecordDao
    /// v0.7.1.3: explicit DAO for the household_members push job. The
    /// v0.7.0 implementation created the personal-household membership
    /// row locally but never pushed it — see the Android fix commit
    /// d6bd1a2 for the bug. Optional so existing test constructors keep
    /// working; when nil, the household_members push job is skipped.
    private let householdMemberDao: HouseholdMemberDao?
    /// v0.7.1: prefs cloud-sync. Used by `flushAllPending` to bypass the
    /// 500 ms debounce and synchronously push the latest snapshot before
    /// signalling "Safe to uninstall." Optional for the same back-compat
    /// reason.
    private let userPreferencesSync: UserPreferencesSync?

    /// Outermost task: subscribed to (uid, householdId) changes. Cancelled
    /// in `shutdown`.
    private var sessionTask: Task<Void, Never>?
    /// Per-(uid, householdId) task: subscribed to pullState changes for the
    /// active uid. Cancelled when EITHER changes or on shutdown.
    private var perBindingTask: Task<Void, Never>?
    /// Live push jobs (one per entity). Spawned only when pullState ==
    /// `.succeeded`; cancelled on (uid, householdId) change OR pullState
    /// transition away from `.succeeded`.
    private var pushTasks: [Task<Void, Never>] = []

    /// Active joint binding (uid, householdId). Tracked so the joint stream
    /// can suppress duplicate emissions.
    private var currentBinding: (uid: String, householdId: String)?

    init(
        firestore: any FirestoreClient,
        session: any UserSessionProvider,
        householdSession: any HouseholdSessionProvider,
        pullStateRepo: any PullStateRepository,
        itemDao: ItemDao,
        categoryDao: CategoryDao,
        storeDao: StoreDao,
        xrefDao: ItemStoreXrefDao,
        scoDao: StoreCategoryOrderDao,
        purchaseDao: PurchaseRecordDao,
        householdMemberDao: HouseholdMemberDao? = nil,
        userPreferencesSync: UserPreferencesSync? = nil
    ) {
        self.firestore = firestore
        self.session = session
        self.householdSession = householdSession
        self.pullStateRepo = pullStateRepo
        self.itemDao = itemDao
        self.categoryDao = categoryDao
        self.storeDao = storeDao
        self.xrefDao = xrefDao
        self.scoDao = scoDao
        self.purchaseDao = purchaseDao
        self.householdMemberDao = householdMemberDao
        self.userPreferencesSync = userPreferencesSync
    }

    /// Boot the engine. Idempotent.
    func start() {
        if sessionTask != nil { return }
        let joined = Self.joinSessions(userStream: session.userIdStream, householdStream: householdSession.householdIdStream)
        sessionTask = Task { [weak self] in
            for await binding in joined {
                guard let self else { return }
                await self.handleBindingChange(uid: binding.uid, householdId: binding.householdId)
            }
        }
    }

    /// Stop all in-flight tasks. Tests call this in tearDown; production
    /// has no shutdown path (the engine lives for the app's lifetime).
    func shutdown() {
        sessionTask?.cancel()
        sessionTask = nil
        perBindingTask?.cancel()
        perBindingTask = nil
        for t in pushTasks { t.cancel() }
        pushTasks = []
        currentBinding = nil
    }

    // MARK: - Binding handling

    private func handleBindingChange(uid: String?, householdId: String?) async {
        // Cancel everything tied to the previous binding.
        perBindingTask?.cancel()
        perBindingTask = nil
        for t in pushTasks { t.cancel() }
        pushTasks = []

        guard let uid, let hid = householdId else {
            currentBinding = nil
            return
        }
        currentBinding = (uid: uid, householdId: hid)

        // Watch pull-state for this uid; flip push on/off accordingly.
        // PullState is keyed on uid (matches Android), not householdId.
        let stateStream = pullStateRepo.observe(uid)
        perBindingTask = Task { [weak self] in
            for await state in stateStream {
                guard let self else { return }
                await self.handlePullStateChange(uid: uid, householdId: hid, state: state)
            }
        }
    }

    private func handlePullStateChange(uid: String, householdId: String, state: PullState) async {
        if state == .succeeded {
            startPushJobsIfNeeded(householdId: householdId)
        } else {
            // Pause: cancel running push jobs but keep the per-binding state
            // observation alive so we can resume on the next .succeeded.
            for t in pushTasks { t.cancel() }
            pushTasks = []
        }
    }

    // MARK: - push jobs

    private func startPushJobsIfNeeded(householdId: String) {
        guard pushTasks.isEmpty else { return }
        var tasks: [Task<Void, Never>] = [
            startItemPushJob(householdId: householdId),
            startCategoryPushJob(householdId: householdId),
            startStorePushJob(householdId: householdId),
            startXrefPushJob(householdId: householdId),
            startScoPushJob(householdId: householdId),
            startPurchasePushJob(householdId: householdId),
        ]
        // v0.7.1.3 fix: also push household_members rows. v0.7.0 created
        // the personal-household membership locally with pendingSync = 1
        // but had no push job; the path-uid fallback in firestore.rules
        // masked the gap for single-user flows until v0.7.1's
        // Force-sync count surfaced it.
        if let memberDao = householdMemberDao {
            tasks.append(startHouseholdMemberPushJob(dao: memberDao))
        }
        pushTasks = tasks
    }

    private func startHouseholdMemberPushJob(dao: HouseholdMemberDao) -> Task<Void, Never> {
        let stream = dao.observePendingPush()
        let firestore = self.firestore
        return Task {
            do {
                for try await rows in stream {
                    for row in rows {
                        // Memberships live at `/memberships/{uid}/households/{hid}`
                        // — uid-scoped, NOT household-scoped. Each row carries
                        // both ids.
                        let path = "memberships/\(row.uid)/households/\(row.householdId)"
                        // Inline payload Map (no DTO) since the shape is
                        // small and we don't decode it back the same way.
                        // Mirrors Android's pushOne with mapOf<String, Any?>.
                        let payload = HouseholdMemberPayload(
                            uid: row.uid,
                            householdId: row.householdId,
                            displayName: row.displayName,
                            joinedAt: row.joinedAt,
                            isOwner: row.isOwner,
                            createdAt: row.createdAt,
                            updatedAt: row.updatedAt,
                            deletedAt: row.deletedAt
                        )
                        await Self.pushOne(firestore: firestore, path: path, value: payload) {
                            try? await dao.markPushed(uid: row.uid, householdId: row.householdId)
                        }
                    }
                }
            } catch is CancellationError {
                return
            } catch {}
        }
    }

    private func startItemPushJob(householdId: String) -> Task<Void, Never> {
        let stream = itemDao.observePendingPush(householdId: householdId)
        let dao = self.itemDao
        let firestore = self.firestore
        return Task {
            do {
                for try await rows in stream {
                    for row in rows {
                        let path = "users/\(householdId)/\(SyncCollections.items)/\(row.id)"
                        await Self.pushOne(firestore: firestore, path: path, value: row.toDto()) {
                            try? await dao.markPushed(householdId: householdId, id: row.id)
                        }
                    }
                }
            } catch is CancellationError {
                return
            } catch {
                // ValueObservation rarely throws; if it does, the outer
                // restart on binding/pullState change re-creates this job.
            }
        }
    }

    private func startCategoryPushJob(householdId: String) -> Task<Void, Never> {
        let stream = categoryDao.observePendingPush(householdId: householdId)
        let dao = self.categoryDao
        let firestore = self.firestore
        return Task {
            do {
                for try await rows in stream {
                    for row in rows {
                        let path = "users/\(householdId)/\(SyncCollections.categories)/\(row.id)"
                        await Self.pushOne(firestore: firestore, path: path, value: row.toDto()) {
                            try? await dao.markPushed(householdId: householdId, id: row.id)
                        }
                    }
                }
            } catch is CancellationError {
                return
            } catch {}
        }
    }

    private func startStorePushJob(householdId: String) -> Task<Void, Never> {
        let stream = storeDao.observePendingPush(householdId: householdId)
        let dao = self.storeDao
        let firestore = self.firestore
        return Task {
            do {
                for try await rows in stream {
                    for row in rows {
                        let path = "users/\(householdId)/\(SyncCollections.stores)/\(row.id)"
                        await Self.pushOne(firestore: firestore, path: path, value: row.toDto()) {
                            try? await dao.markPushed(householdId: householdId, id: row.id)
                        }
                    }
                }
            } catch is CancellationError {
                return
            } catch {}
        }
    }

    private func startXrefPushJob(householdId: String) -> Task<Void, Never> {
        let stream = xrefDao.observePendingPush(householdId: householdId)
        let dao = self.xrefDao
        let firestore = self.firestore
        return Task {
            do {
                for try await rows in stream {
                    for row in rows {
                        let path = "users/\(householdId)/\(SyncCollections.itemStoreXrefs)/\(row.docId)"
                        await Self.pushOne(firestore: firestore, path: path, value: row.toDto()) {
                            try? await dao.markPushed(householdId: householdId, itemId: row.itemId, storeId: row.storeId)
                        }
                    }
                }
            } catch is CancellationError {
                return
            } catch {}
        }
    }

    private func startScoPushJob(householdId: String) -> Task<Void, Never> {
        let stream = scoDao.observePendingPush(householdId: householdId)
        let dao = self.scoDao
        let firestore = self.firestore
        return Task {
            do {
                for try await rows in stream {
                    for row in rows {
                        let path = "users/\(householdId)/\(SyncCollections.storeCategoryOrders)/\(row.docId)"
                        await Self.pushOne(firestore: firestore, path: path, value: row.toDto()) {
                            try? await dao.markPushed(householdId: householdId, storeId: row.storeId, categoryId: row.categoryId)
                        }
                    }
                }
            } catch is CancellationError {
                return
            } catch {}
        }
    }

    private func startPurchasePushJob(householdId: String) -> Task<Void, Never> {
        let stream = purchaseDao.observePendingPush(householdId: householdId)
        let dao = self.purchaseDao
        let firestore = self.firestore
        return Task {
            do {
                for try await rows in stream {
                    for row in rows {
                        let path = "users/\(householdId)/\(SyncCollections.purchaseRecords)/\(row.id)"
                        await Self.pushOne(firestore: firestore, path: path, value: row.toDto()) {
                            try? await dao.markPushed(householdId: householdId, id: row.id)
                        }
                    }
                }
            } catch is CancellationError {
                return
            } catch {}
        }
    }

    // MARK: - v0.7.1 Force-sync surface

    /// v0.7.1: aggregated row-count of every entity table's
    /// `pendingSync = 1` rows for the active household, plus the
    /// `household_members` table (uid-scoped, not household-scoped).
    /// Mirrors Android `SyncEngine.observeAllPendingCount`.
    ///
    /// Powers the Settings → Data → "Force sync now" UX. When this
    /// drops to 0 the UX shows "Safe to uninstall."
    nonisolated func observeAllPendingCount(householdId: String) -> AsyncStream<Int> {
        AsyncStream { continuation in
            let itemStream = itemDao.countPendingPush(householdId: householdId)
            let categoryStream = categoryDao.countPendingPush(householdId: householdId)
            let storeStream = storeDao.countPendingPush(householdId: householdId)
            let xrefStream = xrefDao.countPendingPush(householdId: householdId)
            let scoStream = scoDao.countPendingPush(householdId: householdId)
            let purchaseStream = purchaseDao.countPendingPush(householdId: householdId)
            let memberStream = householdMemberDao?.countPendingPush()

            let aggregator = PendingCountAggregator()
            let tasks: [Task<Void, Never>] = [
                Task {
                    do {
                        for try await c in itemStream {
                            if let sum = await aggregator.update(.items, c) { continuation.yield(sum) }
                        }
                    } catch {}
                },
                Task {
                    do {
                        for try await c in categoryStream {
                            if let sum = await aggregator.update(.categories, c) { continuation.yield(sum) }
                        }
                    } catch {}
                },
                Task {
                    do {
                        for try await c in storeStream {
                            if let sum = await aggregator.update(.stores, c) { continuation.yield(sum) }
                        }
                    } catch {}
                },
                Task {
                    do {
                        for try await c in xrefStream {
                            if let sum = await aggregator.update(.xrefs, c) { continuation.yield(sum) }
                        }
                    } catch {}
                },
                Task {
                    do {
                        for try await c in scoStream {
                            if let sum = await aggregator.update(.sco, c) { continuation.yield(sum) }
                        }
                    } catch {}
                },
                Task {
                    do {
                        for try await c in purchaseStream {
                            if let sum = await aggregator.update(.purchases, c) { continuation.yield(sum) }
                        }
                    } catch {}
                },
            ]

            var memberTask: Task<Void, Never>?
            if let memberStream {
                memberTask = Task {
                    do {
                        for try await c in memberStream {
                            if let sum = await aggregator.update(.members, c) { continuation.yield(sum) }
                        }
                    } catch {}
                }
            }

            continuation.onTermination = { _ in
                for t in tasks { t.cancel() }
                memberTask?.cancel()
            }
        }
    }

    /// v0.7.1: nudge the push side to flush every pending row + the
    /// user-prefs doc immediately, and wait until the queue is empty
    /// (or the timeout elapses).
    ///
    /// Returns `true` if the queue drained, `false` if rows are still
    /// pending at the timeout (UI surfaces the stuck count).
    nonisolated func flushAllPending(
        householdId: String,
        uid: String,
        timeoutNanos: UInt64 = 30 * NSEC_PER_SEC
    ) async -> Bool {
        // v0.9 repair pass: converge any item stranded at isNeeded = 0 by the
        // pre-v0.9 asymmetric un-check back to global-needed BEFORE we flush,
        // so healed rows (flagged pendingSync = 1) push up in this same drain.
        // Idempotent; a no-op once data is already consistent.
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let repaired = (try? await xrefDao.repairStrandedNeededLinks(householdId: householdId, now: now)) ?? 0
        if repaired > 0 { print("[SyncEngine] Force sync repaired \(repaired) stranded item↔store link(s)") }
        await userPreferencesSync?.flushPending(uid: uid)
        return await withTaskGroup(of: Bool.self) { group in
            group.addTask {
                for await count in self.observeAllPendingCount(householdId: householdId) {
                    if count == 0 { return true }
                }
                return false
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: timeoutNanos)
                return false
            }
            let result = await group.next() ?? false
            group.cancelAll()
            return result
        }
    }

    // MARK: - pushOne

    /// Push a single document. On Firestore success, calls `markClean` so
    /// the corresponding `pendingSync` flips to 0. On failure, leaves the
    /// row at `pendingSync = 1` — it'll retry on the next observation
    /// re-emission. Firestore's offline queue covers transient network drops;
    /// durable loss (process killed mid-write) is recovered via on-restart
    /// re-emission of pending rows.
    private static func pushOne<T: Encodable & Sendable>(
        firestore: any FirestoreClient,
        path: String,
        value: T,
        markClean: @Sendable () async -> Void
    ) async {
        do {
            try await firestore.setDocument(at: path, value: value)
            await markClean()
        } catch is CancellationError {
            return
        } catch {
            // Push failed; pendingSync stays 1 for retry on next emission.
        }
    }

    // MARK: - Joint (uid, householdId) stream

    /// Joins two `AsyncStream`s into a single stream of `(uid, householdId)`
    /// snapshots. Emits whenever EITHER upstream produces a new value,
    /// using the most recent value from each. Duplicate emissions (same
    /// (uid, householdId) tuple twice) are suppressed. Mirrors Kotlin Flow's
    /// `combine(...).distinctUntilChanged()`.
    private static func joinSessions(
        userStream: AsyncStream<String?>,
        householdStream: AsyncStream<String?>
    ) -> AsyncStream<(uid: String?, householdId: String?)> {
        AsyncStream { continuation in
            let state = JointBindingState()

            let userTask = Task {
                for await uid in userStream {
                    if let snapshot = await state.updateUid(uid) {
                        continuation.yield(snapshot)
                    }
                }
            }
            let householdTask = Task {
                for await hid in householdStream {
                    if let snapshot = await state.updateHouseholdId(hid) {
                        continuation.yield(snapshot)
                    }
                }
            }

            continuation.onTermination = { _ in
                userTask.cancel()
                householdTask.cancel()
            }
        }
    }
}

/// Codable payload for the `household_members` push job. Inline struct
/// rather than a generated DTO since the shape is small and we don't
/// decode it back via this same Codable path — the pull side uses a
/// different Firestore query shape. Mirrors Android's mapOf payload in
/// `SyncEngine.startHouseholdMembersPushJob`.
private struct HouseholdMemberPayload: Codable, Sendable {
    let uid: String
    let householdId: String
    let displayName: String?
    let joinedAt: Int64
    let isOwner: Bool
    let createdAt: Int64
    let updatedAt: Int64
    let deletedAt: Int64?
}

/// Actor-isolated state for `observeAllPendingCount`. Holds the latest
/// count from each entity stream so the aggregated sum re-emits with
/// fresh values each time any one of them changes.
private actor PendingCountAggregator {
    enum Source { case items, categories, stores, xrefs, sco, purchases, members }
    private var counts: [Source: Int] = [:]
    private var lastSum: Int = -1

    func update(_ source: Source, _ count: Int) -> Int? {
        counts[source] = count
        let sum = counts.values.reduce(0, +)
        if sum != lastSum {
            lastSum = sum
            return sum
        }
        return nil
    }
}

/// Actor-isolated state for `joinSessions`. Holds the latest value from
/// each upstream so the combined stream emits the freshest pair and
/// suppresses duplicate emissions.
private actor JointBindingState {
    private var uid: String?
    private var householdId: String?
    private var lastEmittedUid: String?
    private var lastEmittedHouseholdId: String?
    private var haveEmittedAtLeastOnce = false

    func updateUid(_ value: String?) -> (uid: String?, householdId: String?)? {
        uid = value
        return snapshotIfChanged()
    }

    func updateHouseholdId(_ value: String?) -> (uid: String?, householdId: String?)? {
        householdId = value
        return snapshotIfChanged()
    }

    private func snapshotIfChanged() -> (uid: String?, householdId: String?)? {
        if haveEmittedAtLeastOnce && uid == lastEmittedUid && householdId == lastEmittedHouseholdId {
            return nil
        }
        haveEmittedAtLeastOnce = true
        lastEmittedUid = uid
        lastEmittedHouseholdId = householdId
        return (uid: uid, householdId: householdId)
    }
}
