import Foundation

/// Production `PullCoordinator` backed by Firestore.
///
/// Two responsibilities:
///   1. `peek(householdId:)` — cheap "does this household have any cloud
///      data?" check via `users/{householdId}/stores?limit=1`. Uses
///      `stores` rather than `items` because the seeded set ensures every
///      account has at least one store row in cloud once it has pushed at
///      least once — the cleanest "is this a returning household?" signal.
///   2. `pullForHousehold(_:)` — fetch all six subcollections in parallel,
///      transform DTOs to records, write through `PullWriteDao` in a
///      single all-or-nothing transaction.
///
/// Actor-isolated so a double sign-in tap can't race itself:
/// `pullForHousehold` is serialized per-instance. In practice only one
/// household is ever being pulled at a time (the session provider waits
/// for the previous pull before flipping householdId), so a single-actor
/// lock is enough.
///
/// v0.7.0: the wire path stays `/users/{X}/...` with `X` reinterpreted as
/// `householdId`. Preserves backward compat: Mike's v0.6.x data at
/// `/users/{uid}/` is now read as `/users/{hid}/` since `hid == uid`
/// for single-member households.
///
/// Mirrors Android `PullCoordinator`.
final actor FirestorePullCoordinator: PullCoordinator {
    private let firestore: any FirestoreClient
    private let pullWriteDao: PullWriteDao

    init(firestore: any FirestoreClient, pullWriteDao: PullWriteDao) {
        self.firestore = firestore
        self.pullWriteDao = pullWriteDao
    }

    func peek(householdId: String) async throws -> Bool {
        let path = "users/\(householdId)/\(SyncCollections.stores)"
        return try await firestore.peekHasDocuments(at: path)
    }

    func pullForHousehold(_ householdId: String) async -> PullResult {
        do {
            let householdPath = "users/\(householdId)"

            // Fetch all six collections in parallel. Any failure
            // propagates; the catch below maps to `PullResult.failure`
            // and `PullWriteDao.replaceAllForUid` never opens — so the
            // local DB is untouched on error.
            async let items = firestore.fetchAll(
                ItemDto.self,
                at: "\(householdPath)/\(SyncCollections.items)"
            )
            async let categories = firestore.fetchAll(
                CategoryDto.self,
                at: "\(householdPath)/\(SyncCollections.categories)"
            )
            async let stores = firestore.fetchAll(
                StoreDto.self,
                at: "\(householdPath)/\(SyncCollections.stores)"
            )
            async let xrefs = firestore.fetchAll(
                ItemStoreXrefDto.self,
                at: "\(householdPath)/\(SyncCollections.itemStoreXrefs)"
            )
            async let scoOrders = firestore.fetchAll(
                StoreCategoryOrderDto.self,
                at: "\(householdPath)/\(SyncCollections.storeCategoryOrders)"
            )
            async let purchases = firestore.fetchAll(
                PurchaseRecordDto.self,
                at: "\(householdPath)/\(SyncCollections.purchaseRecords)"
            )

            let (i, c, s, x, sco, p) = try await (
                items, categories, stores, xrefs, scoOrders, purchases
            )

            // v0.8.0.4: cloud wins on pull EXCEPT for rows with a local
            // pending edit (`pendingSync = 1`). Those survive until
            // push completes — see Mike's write-revert bug for the
            // canonical case.
            try await pullWriteDao.replaceAllForUid(
                householdId: householdId,
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
