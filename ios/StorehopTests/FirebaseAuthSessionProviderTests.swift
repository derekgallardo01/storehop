import XCTest
@testable import Storehop

/// Exercises `FirebaseAuthSessionProvider` without spinning up Firebase by
/// driving it through `MockFirebaseAuthClient`. The high-value invariants:
///
///   - On first launch with no user, anonymous sign-in fires and the new
///     uid is published (after the claim path runs).
///   - Cloud has data → pull runs, no claim, PullState=SUCCEEDED.
///   - Cloud is empty → claim path runs, PullState=SUCCEEDED.
///   - Pull failure → PullState=FAILED, push stays paused.
///   - Sign-out propagates nil immediately (no claim attempt).
///   - Re-publishing the same uid with PullState=SUCCEEDED is a no-op
///     (no redundant sync).
final class FirebaseAuthSessionProviderTests: XCTestCase {

    private struct Setup {
        let auth: MockFirebaseAuthClient
        let migrationDao: LocalOnlyMigrationDao
        let pullStateRepo: InMemoryPullStateRepository
        let coordinator: ScriptablePullCoordinator
        let session: FirebaseAuthSessionProvider
        let db: StorehopDatabase
    }

    private func makeSetup(initialUid: String? = nil) async throws -> Setup {
        let db = try StorehopDatabase.inMemoryForTests()
        let auth = MockFirebaseAuthClient(initialUid: initialUid, isAnonymous: false)
        let migrationDao = LocalOnlyMigrationDao(writer: db.queue)
        let pullStateRepo = InMemoryPullStateRepository()
        let coordinator = ScriptablePullCoordinator()
        let session = FirebaseAuthSessionProvider(
            authClient: auth,
            migrationDao: migrationDao,
            householdMemberDao: HouseholdMemberDao(writer: db.queue),
            pullCoordinator: coordinator,
            pullStateRepo: pullStateRepo,
            clock: MutableClock(nowMs: 0)
        )
        return Setup(
            auth: auth,
            migrationDao: migrationDao,
            pullStateRepo: pullStateRepo,
            coordinator: coordinator,
            session: session,
            db: db
        )
    }

    // MARK: - First launch

    func testFirstLaunchWithNoUserKicksOffAnonymousSignIn() async throws {
        let s = try await makeSetup(initialUid: nil)
        s.coordinator.peekResult = .success(false)

        await s.session.start()
        await s.auth.completeAnonymousSignIn(uid: "anon_1")

        // Wait for the auth pump to process the new uid.
        try await waitForUid("anon_1", in: s.session)
        let final = await s.pullStateRepo.current(for: "anon_1")
        XCTAssertEqual(final, .succeeded)

        await s.session.shutdown()
    }

    // MARK: - Cloud branches

    func testCloudHasDataPullsAndSkipsClaim() async throws {
        let s = try await makeSetup(initialUid: nil)
        // Seed a local-only row so we can assert claim did NOT run.
        try s.db.seed(stores: [TestFixtures.store(id: "s_local", userId: DatabaseSeeder.localOnlyUserId)])

        s.coordinator.peekResult = .success(true)
        s.coordinator.pullResult = .success

        await s.session.start()
        await s.auth.completeAnonymousSignIn(uid: "uid_alice")
        try await waitForUid("uid_alice", in: s.session)

        let stillLocalOnly = try await s.migrationDao.countLocalOnlyStores()
        XCTAssertEqual(stillLocalOnly, 1, "Cloud-has-data branch must NOT run the claim path")
        let state = await s.pullStateRepo.current(for: "uid_alice")
        XCTAssertEqual(state, .succeeded)
        await s.session.shutdown()
    }

    func testCloudEmptyRunsClaimPathAndPublishesUid() async throws {
        let s = try await makeSetup(initialUid: nil)
        try s.db.seed(stores: [TestFixtures.store(id: "s_local", userId: DatabaseSeeder.localOnlyUserId)])

        s.coordinator.peekResult = .success(false)

        await s.session.start()
        await s.auth.completeAnonymousSignIn(uid: "uid_alice")
        try await waitForUid("uid_alice", in: s.session)

        let claimedUid = try await s.db.queue.read { conn in
            try String.fetchOne(conn, sql: "SELECT userId FROM stores WHERE id = 's_local'")
        }
        XCTAssertEqual(claimedUid, "uid_alice", "Claim path must restamp the local-only row")
        let state = await s.pullStateRepo.current(for: "uid_alice")
        XCTAssertEqual(state, .succeeded)
        await s.session.shutdown()
    }

    func testPullFailureSetsPullStateFailedKeepingPushPaused() async throws {
        let s = try await makeSetup(initialUid: nil)
        s.coordinator.peekResult = .success(true)
        s.coordinator.pullResult = .failure(reason: "network down")

        await s.session.start()
        await s.auth.completeAnonymousSignIn(uid: "uid_alice")
        try await waitForUid("uid_alice", in: s.session)

        let state = await s.pullStateRepo.current(for: "uid_alice")
        XCTAssertEqual(state, .failed, "Pull failure must leave PullState at FAILED so push stays paused")
        await s.session.shutdown()
    }

    func testPeekFailureFailsClosed() async throws {
        let s = try await makeSetup(initialUid: nil)
        s.coordinator.peekResult = .failure(reason: "peek timeout")

        await s.session.start()
        await s.auth.completeAnonymousSignIn(uid: "uid_alice")
        try await waitForUid("uid_alice", in: s.session)

        let state = await s.pullStateRepo.current(for: "uid_alice")
        XCTAssertEqual(state, .failed, "Peek throw must fail closed (FAILED), not succeed")
        await s.session.shutdown()
    }

    // MARK: - Sign-out

    func testSignOutPublishesNilImmediatelyWithoutClaimAttempt() async throws {
        let s = try await makeSetup(initialUid: nil)
        s.coordinator.peekResult = .success(false)
        await s.session.start()
        await s.auth.completeAnonymousSignIn(uid: "uid_alice")
        try await waitForUid("uid_alice", in: s.session)

        await s.auth.simulateSignOut()
        try await waitForUid(nil, in: s.session)

        await s.session.shutdown()
    }

    // MARK: - Idempotent re-publish

    func testRePublishingSameUidWithSucceededIsNoOp() async throws {
        let s = try await makeSetup(initialUid: "uid_alice")
        await s.pullStateRepo.set(.succeeded, for: "uid_alice")

        s.coordinator.peekResult = .success(true)  // would fail the claim invariant if it ran
        await s.session.start()

        // Auth listener fires the same uid again — should not re-run sync.
        await s.auth.simulateAuthStateRefire(uid: "uid_alice")
        try await Task.sleep(nanoseconds: 50_000_000) // 50ms slack
        XCTAssertEqual(s.coordinator.peekCallCount, 0, "No sync should run when uid unchanged and PullState=SUCCEEDED")
        await s.session.shutdown()
    }

    // MARK: - Helpers

    private func waitForUid(_ expected: String?, in session: FirebaseAuthSessionProvider, timeout: TimeInterval = 1.0) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            let current = await session.currentUserId
            if current == expected { return }
            try await Task.sleep(nanoseconds: 5_000_000) // 5ms
        }
        XCTFail("Timed out waiting for uid \(expected ?? "nil")")
    }
}

// MARK: - Test doubles

/// Scriptable PullCoordinator stub. Tests set `peekResult` and `pullResult`
/// per scenario; the test asserts on `peekCallCount` and
/// `pullForHouseholdCalls` to verify gating + retry invocations.
final class ScriptablePullCoordinator: PullCoordinator, @unchecked Sendable {
    enum PeekResult {
        case success(Bool)
        case failure(reason: String)
    }
    var peekResult: PeekResult = .success(false)
    var pullResult: PullResult = .success
    var peekCallCount: Int = 0
    /// Captures every household id passed to `pullForHousehold` in call
    /// order. SettingsViewModel retry-pull tests assert against this so
    /// the retry banner is wired to the same household path the auth
    /// listener uses.
    var pullForHouseholdCalls: [String] = []

    func peek(householdId: String) async throws -> Bool {
        peekCallCount += 1
        switch peekResult {
        case .success(let b): return b
        case .failure(let reason): throw NSError(domain: "test", code: 0, userInfo: [NSLocalizedDescriptionKey: reason])
        }
    }

    func pullForHousehold(_ householdId: String) async -> PullResult {
        pullForHouseholdCalls.append(householdId)
        return pullResult
    }
}

/// In-memory FirebaseAuthClient. Simulates anonymous sign-in completion,
/// sign-out, and the auth-state stream.
final class MockFirebaseAuthClient: FirebaseAuthClient, @unchecked Sendable {
    private let lock = NSLock()
    private var uid: String?
    private var anonymous: Bool
    private var continuations: [UUID: AsyncStream<String?>.Continuation] = [:]
    private var pendingAnonContinuation: CheckedContinuation<String, Error>?

    init(initialUid: String?, isAnonymous: Bool) {
        self.uid = initialUid
        self.anonymous = isAnonymous
    }

    var currentUserId: String? {
        get async { lock.lock(); defer { lock.unlock() }; return uid }
    }

    var currentUserIsAnonymous: Bool {
        get async { lock.lock(); defer { lock.unlock() }; return anonymous }
    }

    var currentAccount: AccountInfo {
        get async {
            lock.lock(); defer { lock.unlock() }
            return AccountInfo(
                uid: uid, isAnonymous: anonymous,
                email: nil, displayName: nil, photoURL: nil
            )
        }
    }

    var accountStream: AsyncStream<AccountInfo> {
        AsyncStream { continuation in
            // Tests don't drive account changes through this stream (they
            // use authStateStream + completeAnonymousSignIn). Emit the
            // current snapshot once and stay open.
            self.lock.lock()
            let initial = AccountInfo(
                uid: uid, isAnonymous: anonymous,
                email: nil, displayName: nil, photoURL: nil
            )
            self.lock.unlock()
            continuation.yield(initial)
        }
    }

    var authStateStream: AsyncStream<String?> {
        AsyncStream { continuation in
            let id = UUID()
            self.lock.lock()
            self.continuations[id] = continuation
            let initial = self.uid
            self.lock.unlock()
            continuation.yield(initial)
            continuation.onTermination = { @Sendable _ in
                self.lock.lock()
                self.continuations[id] = nil
                self.lock.unlock()
            }
        }
    }

    @discardableResult
    func signInAnonymously() async throws -> String {
        try await withCheckedThrowingContinuation { continuation in
            lock.lock()
            pendingAnonContinuation = continuation
            lock.unlock()
        }
    }

    func completeAnonymousSignIn(uid: String) async {
        lock.lock()
        self.uid = uid
        self.anonymous = true
        let continuation = pendingAnonContinuation
        pendingAnonContinuation = nil
        let conts = Array(continuations.values)
        lock.unlock()
        continuation?.resume(returning: uid)
        for c in conts { c.yield(uid) }
    }

    func simulateSignOut() async {
        lock.lock()
        uid = nil
        anonymous = false
        let conts = Array(continuations.values)
        lock.unlock()
        for c in conts { c.yield(nil) }
    }

    func simulateAuthStateRefire(uid: String) async {
        lock.lock()
        let conts = Array(continuations.values)
        lock.unlock()
        for c in conts { c.yield(uid) }
    }

    func signOut() throws {
        lock.lock(); uid = nil; anonymous = false; lock.unlock()
    }

    func makeGoogleCredential(idToken: String) -> AnyAuthCredential {
        AnyAuthCredential(payload: ("google", idToken))
    }

    @discardableResult
    func linkAnonymousWithGoogle(credential: AnyAuthCredential) async throws -> String {
        // Tests don't exercise the link path here; GoogleSignIn flow is
        // tested separately. Provide a default that never throws.
        lock.lock(); let current = uid ?? "linked"; uid = current; anonymous = false; lock.unlock()
        return current
    }

    @discardableResult
    func signInWithGoogle(credential: AnyAuthCredential) async throws -> String {
        lock.lock(); uid = "google_uid"; anonymous = false; lock.unlock()
        return "google_uid"
    }
}
