import Foundation

/// Push side of cloud sync.
///
/// Started once at app launch (from `StorehopApp`'s `.task`). For the life
/// of the process:
///
///   1. Watches `session.userIdStream`. A uid change (sign-in, sign-out,
///      anonymous→Google upgrade) cancels the previous user's push jobs
///      and re-binds.
///   2. For the active uid, observes `pullStateRepo.observe(uid)`. Push
///      jobs run only when state == `.succeeded`. While `.inProgress` /
///      `.failed` / `.needed`, push is paused — local edits accumulate
///      `pendingSync = 1` and flush when state flips back.
///   3. Per synced entity, watches that entity's `observePendingPush(uid)`
///      stream. For each row in each emission, encodes to its DTO and
///      writes to `users/{uid}/<collection>/{docId}`. On Firestore ack,
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
    private let pullStateRepo: any PullStateRepository

    private let itemDao: ItemDao
    private let categoryDao: CategoryDao
    private let storeDao: StoreDao
    private let xrefDao: ItemStoreXrefDao
    private let scoDao: StoreCategoryOrderDao
    private let purchaseDao: PurchaseRecordDao

    /// Outermost task: subscribed to uid changes. Cancelled in `shutdown`.
    private var sessionTask: Task<Void, Never>?
    /// Per-uid task: subscribed to pullState changes for that uid. Cancelled
    /// when uid changes or on shutdown.
    private var perUidTask: Task<Void, Never>?
    /// Live push jobs (one per entity). Spawned only when pullState ==
    /// `.succeeded`; cancelled on uid change OR pullState transition away
    /// from `.succeeded`.
    private var pushTasks: [Task<Void, Never>] = []

    init(
        firestore: any FirestoreClient,
        session: any UserSessionProvider,
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
        let stream = session.userIdStream
        sessionTask = Task { [weak self] in
            for await uid in stream {
                guard let self else { return }
                await self.handleUidChange(uid)
            }
        }
    }

    /// Stop all in-flight tasks. Tests call this in tearDown; production
    /// has no shutdown path (the engine lives for the app's lifetime).
    func shutdown() {
        sessionTask?.cancel()
        sessionTask = nil
        perUidTask?.cancel()
        perUidTask = nil
        for t in pushTasks { t.cancel() }
        pushTasks = []
    }

    // MARK: - uid handling

    private func handleUidChange(_ uid: String?) async {
        // Cancel everything tied to the previous uid.
        perUidTask?.cancel()
        perUidTask = nil
        for t in pushTasks { t.cancel() }
        pushTasks = []

        guard let uid else { return }

        // Watch pull-state for this uid; flip push on/off accordingly.
        let stateStream = pullStateRepo.observe(uid)
        perUidTask = Task { [weak self] in
            for await state in stateStream {
                guard let self else { return }
                await self.handlePullStateChange(uid: uid, state: state)
            }
        }
    }

    private func handlePullStateChange(uid: String, state: PullState) async {
        if state == .succeeded {
            startPushJobsIfNeeded(uid: uid)
        } else {
            // Pause: cancel running push jobs but keep the per-uid state
            // observation alive so we can resume on the next .succeeded.
            for t in pushTasks { t.cancel() }
            pushTasks = []
        }
    }

    // MARK: - push jobs

    private func startPushJobsIfNeeded(uid: String) {
        guard pushTasks.isEmpty else { return }
        pushTasks = [
            startItemPushJob(uid: uid),
            startCategoryPushJob(uid: uid),
            startStorePushJob(uid: uid),
            startXrefPushJob(uid: uid),
            startScoPushJob(uid: uid),
            startPurchasePushJob(uid: uid),
        ]
    }

    private func startItemPushJob(uid: String) -> Task<Void, Never> {
        let stream = itemDao.observePendingPush(userId: uid)
        let dao = self.itemDao
        let firestore = self.firestore
        return Task {
            do {
                for try await rows in stream {
                    for row in rows {
                        let path = "users/\(uid)/\(SyncCollections.items)/\(row.id)"
                        await Self.pushOne(firestore: firestore, path: path, value: row.toDto()) {
                            try? await dao.markPushed(userId: uid, id: row.id)
                        }
                    }
                }
            } catch is CancellationError {
                return
            } catch {
                // ValueObservation rarely throws; if it does, the outer
                // restart on uid/pullState change re-creates this job.
            }
        }
    }

    private func startCategoryPushJob(uid: String) -> Task<Void, Never> {
        let stream = categoryDao.observePendingPush(userId: uid)
        let dao = self.categoryDao
        let firestore = self.firestore
        return Task {
            do {
                for try await rows in stream {
                    for row in rows {
                        let path = "users/\(uid)/\(SyncCollections.categories)/\(row.id)"
                        await Self.pushOne(firestore: firestore, path: path, value: row.toDto()) {
                            try? await dao.markPushed(userId: uid, id: row.id)
                        }
                    }
                }
            } catch is CancellationError {
                return
            } catch {}
        }
    }

    private func startStorePushJob(uid: String) -> Task<Void, Never> {
        let stream = storeDao.observePendingPush(userId: uid)
        let dao = self.storeDao
        let firestore = self.firestore
        return Task {
            do {
                for try await rows in stream {
                    for row in rows {
                        let path = "users/\(uid)/\(SyncCollections.stores)/\(row.id)"
                        await Self.pushOne(firestore: firestore, path: path, value: row.toDto()) {
                            try? await dao.markPushed(userId: uid, id: row.id)
                        }
                    }
                }
            } catch is CancellationError {
                return
            } catch {}
        }
    }

    private func startXrefPushJob(uid: String) -> Task<Void, Never> {
        let stream = xrefDao.observePendingPush(userId: uid)
        let dao = self.xrefDao
        let firestore = self.firestore
        return Task {
            do {
                for try await rows in stream {
                    for row in rows {
                        let path = "users/\(uid)/\(SyncCollections.itemStoreXrefs)/\(row.docId)"
                        await Self.pushOne(firestore: firestore, path: path, value: row.toDto()) {
                            try? await dao.markPushed(userId: uid, itemId: row.itemId, storeId: row.storeId)
                        }
                    }
                }
            } catch is CancellationError {
                return
            } catch {}
        }
    }

    private func startScoPushJob(uid: String) -> Task<Void, Never> {
        let stream = scoDao.observePendingPush(userId: uid)
        let dao = self.scoDao
        let firestore = self.firestore
        return Task {
            do {
                for try await rows in stream {
                    for row in rows {
                        let path = "users/\(uid)/\(SyncCollections.storeCategoryOrders)/\(row.docId)"
                        await Self.pushOne(firestore: firestore, path: path, value: row.toDto()) {
                            try? await dao.markPushed(userId: uid, storeId: row.storeId, categoryId: row.categoryId)
                        }
                    }
                }
            } catch is CancellationError {
                return
            } catch {}
        }
    }

    private func startPurchasePushJob(uid: String) -> Task<Void, Never> {
        let stream = purchaseDao.observePendingPush(userId: uid)
        let dao = self.purchaseDao
        let firestore = self.firestore
        return Task {
            do {
                for try await rows in stream {
                    for row in rows {
                        let path = "users/\(uid)/\(SyncCollections.purchaseRecords)/\(row.id)"
                        await Self.pushOne(firestore: firestore, path: path, value: row.toDto()) {
                            try? await dao.markPushed(userId: uid, id: row.id)
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
}
