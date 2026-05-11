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
        purchaseDao: PurchaseRecordDao
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
        pushTasks = [
            startItemPushJob(householdId: householdId),
            startCategoryPushJob(householdId: householdId),
            startStorePushJob(householdId: householdId),
            startXrefPushJob(householdId: householdId),
            startScoPushJob(householdId: householdId),
            startPurchasePushJob(householdId: householdId),
        ]
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
