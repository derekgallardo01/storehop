import XCTest
@testable import Storehop

/// Pins HouseholdViewModel's repository-bridging behaviour: each typed
/// `InviteResult` variant maps to the right `joinErrorKey` / `failureMessage`
/// / `pendingInvite` / `tokenInput` mutation, client-side validation runs
/// before the repo is touched, and the personal-vs-shared household derived
/// state tracks the session.
///
/// Mirrors Android `HouseholdViewModelTest` (kotlin) one-for-one.
@MainActor
final class HouseholdViewModelTests: XCTestCase {

    private struct Setup {
        let vm: HouseholdViewModel
        let repo: RecordingHouseholdRepository
    }

    private func makeSetup(
        uid: String = "u_alice",
        householdId: String? = nil,
        members: [HouseholdMember] = []
    ) async throws -> Setup {
        let repo = RecordingHouseholdRepository(members: members)
        let userSession = LocalOnlyUserSessionProvider(uid: uid)
        let householdSession = LocalOnlyHouseholdSessionProvider(
            initialHouseholdId: householdId ?? uid
        )
        let vm = HouseholdViewModel(
            repository: repo,
            userSession: userSession,
            householdSession: householdSession
        )
        vm.bind()
        // Allow the bind() tasks to consume the initial stream values.
        try await Task.sleep(nanoseconds: 50_000_000)
        return Setup(vm: vm, repo: repo)
    }

    // MARK: - Derived isPersonalHousehold

    func testIsPersonalHouseholdTrueWhenHouseholdEqualsUid() async throws {
        let s = try await makeSetup(uid: "u_alice", householdId: "u_alice")
        XCTAssertEqual(s.vm.currentUid, "u_alice")
        XCTAssertEqual(s.vm.householdId, "u_alice")
        XCTAssertTrue(s.vm.isPersonalHousehold)
    }

    func testIsPersonalHouseholdFalseWhenHouseholdDiffersFromUid() async throws {
        let s = try await makeSetup(uid: "u_amanda", householdId: "mike-hid")
        XCTAssertEqual(s.vm.currentUid, "u_amanda")
        XCTAssertEqual(s.vm.householdId, "mike-hid")
        XCTAssertFalse(s.vm.isPersonalHousehold)
    }

    func testMembersListReflectsRepoStream() async throws {
        let mike = HouseholdMember(
            uid: "u_mike", householdId: "mike-hid", displayName: "Mike",
            joinedAt: 1, isOwner: true,
            createdAt: 1, updatedAt: 1, deletedAt: nil, pendingSync: false
        )
        let s = try await makeSetup(
            uid: "u_amanda", householdId: "mike-hid", members: [mike]
        )
        XCTAssertEqual(s.vm.members.count, 1)
        XCTAssertEqual(s.vm.members.first?.uid, "u_mike")
    }

    // MARK: - generateInvite

    func testGenerateInviteSetsPendingInviteOnSuccess() async throws {
        let s = try await makeSetup()
        s.repo.generateResult = .success(InviteCode(token: "ABCDEFGH", expiresAt: 100))
        s.vm.generateInvite()
        try await waitForCondition { s.vm.pendingInvite?.token == "ABCDEFGH" }
        XCTAssertEqual(s.repo.generateCallCount, 1)
        XCTAssertNil(s.vm.failureMessage)
    }

    func testGenerateInviteSurfacesFailureMessageOnError() async throws {
        let s = try await makeSetup()
        s.repo.generateResult = .failure(
            NSError(
                domain: "test", code: 0,
                userInfo: [NSLocalizedDescriptionKey: "network down"]
            )
        )
        s.vm.generateInvite()
        try await waitForCondition { s.vm.failureMessage?.contains("network down") == true }
        XCTAssertNil(s.vm.pendingInvite)
    }

    func testDismissPendingInviteClearsTheSheetItem() async throws {
        let s = try await makeSetup()
        s.repo.generateResult = .success(InviteCode(token: "ABCDEFGH", expiresAt: 100))
        s.vm.generateInvite()
        try await waitForCondition { s.vm.pendingInvite != nil }
        s.vm.dismissPendingInvite()
        XCTAssertNil(s.vm.pendingInvite)
    }

    // MARK: - acceptInvite

    func testAcceptInviteRejectsShortTokenWithoutHittingRepo() async throws {
        let s = try await makeSetup()
        s.vm.tokenInput = "SHORT"
        s.vm.acceptInvite()
        // Synchronous client-side validation — no await needed.
        XCTAssertEqual(s.vm.joinErrorKey, "household_error_invalid_token")
        XCTAssertEqual(s.repo.acceptedTokens.count, 0)
    }

    func testAcceptInviteUppercasesAndStripsBeforeSubmit() async throws {
        let s = try await makeSetup()
        s.repo.acceptResult = .success(householdId: "mike-hid")
        s.vm.tokenInput = "  abcdefgh  "
        s.vm.acceptInvite()
        try await waitForCondition { s.repo.acceptedTokens == ["ABCDEFGH"] }
        try await waitForCondition { s.vm.tokenInput.isEmpty }
        XCTAssertNil(s.vm.joinErrorKey)
    }

    func testAcceptInviteMapsNotFoundToInlineKey() async throws {
        let s = try await makeSetup()
        s.repo.acceptResult = .notFound
        s.vm.tokenInput = "AAAAAAAA"
        s.vm.acceptInvite()
        try await waitForCondition {
            s.vm.joinErrorKey == "household_error_invite_not_found"
        }
    }

    func testAcceptInviteMapsExpiredToInlineKey() async throws {
        let s = try await makeSetup()
        s.repo.acceptResult = .expired
        s.vm.tokenInput = "BBBBBBBB"
        s.vm.acceptInvite()
        try await waitForCondition {
            s.vm.joinErrorKey == "household_error_invite_expired"
        }
    }

    func testAcceptInviteMapsAlreadyUsedToInlineKey() async throws {
        let s = try await makeSetup()
        s.repo.acceptResult = .alreadyUsed
        s.vm.tokenInput = "CCCCCCCC"
        s.vm.acceptInvite()
        try await waitForCondition {
            s.vm.joinErrorKey == "household_error_invite_used"
        }
    }

    func testAcceptInviteMapsFailedToFailureAlert() async throws {
        let s = try await makeSetup()
        s.repo.acceptResult = .failed(reason: "permission denied")
        s.vm.tokenInput = "DDDDDDDD"
        s.vm.acceptInvite()
        try await waitForCondition { s.vm.failureMessage == "permission denied" }
        // Inline join-card error stays clear — the user sees the alert,
        // not a sticky in-form error.
        XCTAssertNil(s.vm.joinErrorKey)
    }

    // MARK: - leaveHousehold

    func testRequestLeaveConfirmationFlipsTheAlertFlag() async throws {
        let s = try await makeSetup()
        XCTAssertFalse(s.vm.showLeaveConfirmation)
        s.vm.requestLeaveConfirmation()
        XCTAssertTrue(s.vm.showLeaveConfirmation)
    }

    func testCancelLeaveClearsTheAlertWithoutCallingRepo() async throws {
        let s = try await makeSetup()
        s.vm.requestLeaveConfirmation()
        s.vm.cancelLeave()
        XCTAssertFalse(s.vm.showLeaveConfirmation)
        XCTAssertEqual(s.repo.leaveCallCount, 0)
    }

    func testConfirmLeaveCallsRepoAndClearsAlert() async throws {
        let s = try await makeSetup(uid: "u_amanda", householdId: "mike-hid")
        s.vm.requestLeaveConfirmation()
        s.vm.confirmLeave()
        try await waitForCondition { s.repo.leaveCallCount == 1 }
        XCTAssertFalse(s.vm.showLeaveConfirmation)
    }

    func testConfirmLeaveSurfacesErrorOnRepoThrow() async throws {
        let s = try await makeSetup(uid: "u_amanda", householdId: "mike-hid")
        s.repo.leaveError = NSError(
            domain: "test", code: 1,
            userInfo: [NSLocalizedDescriptionKey: "boom"]
        )
        s.vm.confirmLeave()
        try await waitForCondition { s.vm.failureMessage == "boom" }
    }

    // MARK: - Failure ack

    func testAcknowledgeFailureClearsTheFailureMessage() async throws {
        let s = try await makeSetup()
        s.repo.generateResult = .failure(
            NSError(domain: "test", code: 0, userInfo: [NSLocalizedDescriptionKey: "err"])
        )
        s.vm.generateInvite()
        try await waitForCondition { s.vm.failureMessage != nil }
        s.vm.acknowledgeFailure()
        XCTAssertNil(s.vm.failureMessage)
    }
}

// MARK: - Test doubles

/// Scriptable HouseholdRepository for VM tests. Records each call and
/// returns canned results so the VM's branch coverage is observable
/// without running Firestore.
final class RecordingHouseholdRepository: HouseholdRepository, @unchecked Sendable {
    var generateResult: Result<InviteCode, Error> = .success(
        InviteCode(token: "ABCDEFGH", expiresAt: 0)
    )
    var acceptResult: InviteResult = .success(householdId: "h_default")
    var leaveError: Error?

    private(set) var generateCallCount: Int = 0
    private(set) var acceptedTokens: [String] = []
    private(set) var leaveCallCount: Int = 0
    private let members: [HouseholdMember]

    init(members: [HouseholdMember] = []) {
        self.members = members
    }

    func observeMembers() -> AsyncStream<[HouseholdMember]> {
        let snapshot = members
        return AsyncStream { continuation in
            continuation.yield(snapshot)
            // Don't finish — mirrors the live Firestore stream so the
            // bind() task stays alive for the test's duration.
        }
    }

    func generateInvite() async throws -> InviteCode {
        generateCallCount += 1
        switch generateResult {
        case .success(let code): return code
        case .failure(let err): throw err
        }
    }

    func acceptInvite(token: String) async -> InviteResult {
        acceptedTokens.append(token)
        return acceptResult
    }

    func leaveHousehold() async throws {
        leaveCallCount += 1
        if let err = leaveError { throw err }
    }
}
