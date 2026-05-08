import Foundation

/// Production `PullCoordinator` backed by Firestore.
///
/// Two responsibilities:
///   1. `peek(uid:)` — cheap "does this uid have any cloud data?" check via
///      `users/{uid}/stores?limit=1`. Uses `stores` rather than `items`
///      because the seeded set ensures every account has at least one
///      store row in cloud once it has pushed at least once — the cleanest
///      "is this a returning user?" signal.
///   2. `pullForUid(_:)` — fetch all six subcollections in parallel,
///      transform DTOs to records, write through `PullWriteDao` in a
///      single all-or-nothing transaction.
///
/// Actor-isolated so a double sign-in tap can't race itself: `pullForUid`
/// is serialized per-instance. In practice only one uid is ever being
/// pulled at a time (the session provider waits for the previous pull
/// before flipping uid), so a single-actor lock is enough.
///
/// Mirrors Android `PullCoordinator`.
final actor FirestorePullCoordinator: PullCoordinator {
    private let firestore: any FirestoreClient
    private let pullWriteDao: PullWriteDao

    init(firestore: any FirestoreClient, pullWriteDao: PullWriteDao) {
        self.firestore = firestore
        self.pullWriteDao = pullWriteDao
    }

    func peek(uid: String) async throws -> Bool {
        let path = "users/\(uid)/\(SyncCollections.stores)"
        return try await firestore.peekHasDocuments(at: path)
    }

    func pullForUid(_ uid: String) async -> PullResult {
        do {
            let userPath = "users/\(uid)"

            // Fetch all six collections in parallel. Any failure
            // propagates; the catch below maps to `PullResult.failure`
            // and `PullWriteDao.replaceAllForUid` never opens — so the
            // local DB is untouched on error.
            async let items = firestore.fetchAll(
                ItemDto.self,
                at: "\(userPath)/\(SyncCollections.items)"
            )
            async let categories = firestore.fetchAll(
                CategoryDto.self,
                at: "\(userPath)/\(SyncCollections.categories)"
            )
            async let stores = firestore.fetchAll(
                StoreDto.self,
                at: "\(userPath)/\(SyncCollections.stores)"
            )
            async let xrefs = firestore.fetchAll(
                ItemStoreXrefDto.self,
                at: "\(userPath)/\(SyncCollections.itemStoreXrefs)"
            )
            async let scoOrders = firestore.fetchAll(
                StoreCategoryOrderDto.self,
                at: "\(userPath)/\(SyncCollections.storeCategoryOrders)"
            )
            async let purchases = firestore.fetchAll(
                PurchaseRecordDto.self,
                at: "\(userPath)/\(SyncCollections.purchaseRecords)"
            )

            let (i, c, s, x, sco, p) = try await (
                items, categories, stores, xrefs, scoOrders, purchases
            )

            // Cloud always wins on pull: pulled rows write
            // `pendingSync = false` so they don't immediately re-push.
            // A user's prior local edits to the same row id are
            // overwritten — a documented limitation; merge-anon-to-cloud
            // is a v0.5+ question.
            try await pullWriteDao.replaceAllForUid(
                items: i.map { $0.toEntity() },
                categories: c.map { $0.toEntity() },
                stores: s.map { $0.toEntity() },
                xrefs: x.map { $0.toEntity() },
                scoOrders: sco.map { $0.toEntity() },
                purchaseRecords: p.map { $0.toEntity() }
            )

            return .success
        } catch {
            return .failure(reason: "pull failed: \(error.localizedDescription)")
        }
    }
}
