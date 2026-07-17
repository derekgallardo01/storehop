import XCTest
import GRDB
@testable import Storehop

/// Verifies the push-side gate: jobs run only when PullState=SUCCEEDED for
/// the active uid, and re-binding works on uid + state changes.
final class SyncEngineTests: XCTestCase {

    private struct Setup {
        let db: StorehopDatabase
        let firestore: RecordingFirestoreClient
        let session: TestSession
        let pullStateRepo: InMemoryPullStateRepository
        let engine: SyncEngine
    }

    private func makeSetup() async throws -> Setup {
        let db = try StorehopDatabase.inMemoryForTests()
        let firestore = RecordingFirestoreClient()
        let session = TestSession()
        let pullStateRepo = InMemoryPullStateRepository()
        let writer = db.queue
        let engine = SyncEngine(
            firestore: firestore,
            session: session,
            householdSession: session,
            pullStateRepo: pullStateRepo,
            itemDao: ItemDao(writer: writer),
            categoryDao: CategoryDao(writer: writer),
            storeDao: StoreDao(writer: writer),
            xrefDao: ItemStoreXrefDao(writer: writer),
            scoDao: StoreCategoryOrderDao(writer: writer),
            purchaseDao: PurchaseRecordDao(writer: writer)
        )
        return Setup(db: db, firestore: firestore, session: session, pullStateRepo: pullStateRepo, engine: engine)
    }

    // MARK: - Gate

    func testPushDoesNotRunWhilePullStateIsNotSucceeded() async throws {
        let s = try await makeSetup()
        try s.db.seed(stores: [TestFixtures.store(id: "s1", userId: "u1")])

        await s.session.publish("u1")
        await s.pullStateRepo.set(.inProgress, for: "u1")
        await s.engine.start()

        // Wait briefly to let any push attempts happen — they shouldn't.
        try await Task.sleep(nanoseconds: 250_000_000)

        let writes = await s.firestore.snapshotPaths()
        XCTAssertTrue(writes.isEmpty, "No writes expected while PullState != .succeeded")
        await s.engine.shutdown()
    }

    func testPushFlushesPendingRowsWhenPullStateFlipsToSucceeded() async throws {
        let s = try await makeSetup()
        try s.db.seed(stores: [TestFixtures.store(id: "s1", name: "Lidl", userId: "u1")])

        await s.session.publish("u1")
        await s.pullStateRepo.set(.inProgress, for: "u1")
        await s.engine.start()

        // Flip the gate. Push job spins up and pushes the pending store.
        await s.pullStateRepo.set(.succeeded, for: "u1")

        // Generous timeouts match the precedent set in
        // `testPushRestartsOnUidChange` (commit 16fa726): macos-15
        // takes longer than local to walk the
        // combine(uid, hid) → cancel-and-restart-jobs →
        // observePendingPush → GRDB ValueObservation → firestore push
        // chain. The 2s default was tight enough to flake under suite
        // contention (e.g., when run alongside UI tests).
        try await waitForCondition(timeout: 5.0) {
            await s.firestore.snapshotPaths().contains("users/u1/stores/s1")
        }

        // After ack, markPushed flips pendingSync=0.
        try await waitForCondition(timeout: 5.0) {
            let count: Int = (try? await s.db.queue.read { conn in
                try Int.fetchOne(conn, sql: "SELECT COUNT(*) FROM stores WHERE pendingSync = 1") ?? -1
            }) ?? -1
            return count == 0
        }

        await s.engine.shutdown()
    }

    func testPushPausesWhenPullStateRegressesAwayFromSucceeded() async throws {
        let s = try await makeSetup()
        try s.db.seed(stores: [TestFixtures.store(id: "s1", userId: "u1")])

        await s.session.publish("u1")
        await s.pullStateRepo.set(.succeeded, for: "u1")
        await s.engine.start()
        try await waitForCondition(timeout: 2.0) {
            await !s.firestore.snapshotPaths().isEmpty
        }

        // Pause — add another row and verify it does NOT push.
        // Generous settle window (same 16fa726 precedent as the sibling
        // tests): the pause propagates via job cancellation, and 100ms was
        // tight enough to flake under full-suite contention — the s2 seed
        // below could land before the push job actually stopped.
        await s.pullStateRepo.set(.failed, for: "u1")
        try await Task.sleep(nanoseconds: 1_000_000_000)
        let pauseSnapshot = await s.firestore.snapshotPaths()

        try s.db.seed(stores: [TestFixtures.store(id: "s2", userId: "u1")])
        try await Task.sleep(nanoseconds: 250_000_000)

        let postSnapshot = await s.firestore.snapshotPaths()
        XCTAssertEqual(pauseSnapshot.count, postSnapshot.count, "No new pushes while paused")
        await s.engine.shutdown()
    }

    func testPushRestartsOnUidChange() async throws {
        let s = try await makeSetup()
        try s.db.seed(stores: [TestFixtures.store(id: "s1", userId: "u_alice")])

        await s.session.publish("u_alice")
        await s.pullStateRepo.set(.succeeded, for: "u_alice")
        await s.engine.start()
        // Generous timeouts: the macos-15 CI runner takes longer than local
        // to propagate combine(uid, hid) → cancel-and-restart-jobs →
        // observePendingPush subscribe → GRDB ValueObservation initial
        // value → firestore push round-trip. The 2s default was tight
        // enough to flake on the uid-switch second wait.
        try await waitForCondition(timeout: 5.0) {
            await s.firestore.snapshotPaths().contains("users/u_alice/stores/s1")
        }

        // Switch to bob: prior uid's push should stop, bob's should start.
        await s.session.publish("u_bob")
        await s.pullStateRepo.set(.succeeded, for: "u_bob")
        try s.db.seed(stores: [TestFixtures.store(id: "s_bob", userId: "u_bob")])

        try await waitForCondition(timeout: 5.0) {
            await s.firestore.snapshotPaths().contains("users/u_bob/stores/s_bob")
        }

        await s.engine.shutdown()
    }

    func testItemAndCategoryPushUseCorrectCollectionPaths() async throws {
        let s = try await makeSetup()
        try s.db.seed(
            items: [TestFixtures.item(id: "i1", userId: "u1")],
            categories: [TestFixtures.category(id: "c1", userId: "u1")]
        )

        await s.session.publish("u1")
        await s.pullStateRepo.set(.succeeded, for: "u1")
        await s.engine.start()

        try await waitForCondition(timeout: 5.0) {
            let paths = await s.firestore.snapshotPaths()
            return paths.contains("users/u1/items/i1") && paths.contains("users/u1/categories/c1")
        }
        await s.engine.shutdown()
    }
}

// MARK: - Test doubles

/// Records every `setDocument` call so tests can assert on paths and values.
/// Also serves as a programmable read source for pull-coordinator tests.
final actor RecordingFirestoreClient: FirestoreClient {
    private var paths: [String] = []
    private var bodies: [(path: String, json: String)] = []
    /// Per-collection-path docs the test wants `fetchAll` to return.
    /// Stored as JSON so the mock decodes lazily into whatever T the
    /// caller asks for.
    private var stubbedDocs: [String: [Data]] = [:]

    func setDocument<T: Encodable & Sendable>(at path: String, value: T) async throws {
        paths.append(path)
        if let data = try? JSONEncoder().encode(value),
           let json = String(data: data, encoding: .utf8) {
            bodies.append((path, json))
        }
    }

    func peekHasDocuments(at collectionPath: String) async throws -> Bool {
        !(stubbedDocs[collectionPath]?.isEmpty ?? true)
    }

    func fetchAll<T: Decodable & Sendable>(_ type: T.Type, at collectionPath: String) async throws -> [T] {
        guard let entries = stubbedDocs[collectionPath] else { return [] }
        return try entries.map { try JSONDecoder().decode(T.self, from: $0) }
    }

    /// Test setup: stage `values` as the cloud contents at `collectionPath`.
    func stub<T: Encodable & Sendable>(_ values: [T], at collectionPath: String) throws {
        let encoded = try values.map { try JSONEncoder().encode($0) }
        stubbedDocs[collectionPath] = encoded
    }

    /// Test setup: stage raw bytes that may not decode cleanly, used by
    /// failure-handling tests to verify the coordinator maps decode
    /// errors to `PullResult.failure`.
    func stubRaw(_ values: [Data], at collectionPath: String) {
        stubbedDocs[collectionPath] = values
    }

    nonisolated func snapshotPaths() async -> [String] {
        await self.readPaths()
    }

    private func readPaths() -> [String] { paths }
}

/// Drivable session provider for tests. Publish a uid via `publish(_:)`
/// to fire the engine's pump. Also conforms to `HouseholdSessionProvider`
/// and mirrors the uid onto the household id so single-member-household
/// behaviour holds in tests (matches `LocalOnlyHouseholdSessionProvider`).
final class TestSession: UserSessionProvider, HouseholdSessionProvider, @unchecked Sendable {
    private let lock = NSLock()
    private var current: String?
    private var continuations: [UUID: AsyncStream<String?>.Continuation] = [:]
    private var hidContinuations: [UUID: AsyncStream<String?>.Continuation] = [:]

    var currentUserId: String? {
        get async { lock.lock(); defer { lock.unlock() }; return current }
    }

    var currentHouseholdId: String? {
        get async { lock.lock(); defer { lock.unlock() }; return current }
    }

    var userIdStream: AsyncStream<String?> {
        AsyncStream { continuation in
            let id = UUID()
            self.lock.lock()
            self.continuations[id] = continuation
            let initial = self.current
            self.lock.unlock()
            continuation.yield(initial)
            continuation.onTermination = { @Sendable _ in
                self.lock.lock()
                self.continuations[id] = nil
                self.lock.unlock()
            }
        }
    }

    var householdIdStream: AsyncStream<String?> {
        AsyncStream { continuation in
            let id = UUID()
            self.lock.lock()
            self.hidContinuations[id] = continuation
            let initial = self.current
            self.lock.unlock()
            continuation.yield(initial)
            continuation.onTermination = { @Sendable _ in
                self.lock.lock()
                self.hidContinuations[id] = nil
                self.lock.unlock()
            }
        }
    }

    func publish(_ uid: String?) async {
        lock.lock()
        current = uid
        let conts = Array(continuations.values)
        let hidConts = Array(hidContinuations.values)
        lock.unlock()
        for c in conts { c.yield(uid) }
        for c in hidConts { c.yield(uid) }
        // Give consumers a moment to react.
        try? await Task.sleep(nanoseconds: 30_000_000)
    }
}

// Note: a sync-predicate `waitForCondition` already exists in
// ShopAtStoreViewModelTests.swift. The variants below take an async
// predicate, named distinctly so Swift's overload resolution doesn't have
// to disambiguate at every call site.
@discardableResult
func waitForCondition(timeout: TimeInterval = 1.0, _ check: @escaping () async -> Bool) async throws -> Bool {
    let deadline = Date().addingTimeInterval(timeout)
    while Date() < deadline {
        if await check() { return true }
        try await Task.sleep(nanoseconds: 20_000_000)
    }
    XCTFail("Timed out waiting for async condition")
    return false
}
