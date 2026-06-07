import Foundation
import FirebaseFirestore

/// v0.7.0 Phase 3: the Settings → Household screen API for iOS.
///
/// Three flows mediate the multi-user sharing experience:
///  - generate an invite code (Mike shares it out-of-band with Amanda)
///  - accept an invite code (Amanda joins Mike's household; her local DB
///    wipes + re-pulls from Mike's household path)
///  - leave a household (drops local data, returns to a personal household
///    with hid = uid)
///
/// Plus a live view of the current members for the Household screen's
/// member list. Mirrors Android `HouseholdRepository` 1:1.
protocol HouseholdRepository: Sendable {

    /// Live stream of every member of the current household. Empty when
    /// the user isn't signed in. For single-member households this emits
    /// a list of size 1.
    func observeMembers() -> AsyncStream<[HouseholdMember]>

    /// Generate a fresh 8-character invite code, write it to Firestore at
    /// `/invites/{token}` with expiresAt = now + 24h, and return the
    /// token. Throws if the user isn't signed in.
    ///
    /// Tokens use Crockford base32 (excludes 0/O/1/I/L) so users can
    /// dictate codes over a phone call without confusion.
    func generateInvite() async throws -> InviteCode

    /// Atomically validate the invite, write a membership row, wipe local
    /// household-scoped data, and switch to the new household. Returns a
    /// typed result so the UI renders precise inline errors.
    func acceptInvite(token: String) async -> InviteResult

    /// Leave the current household. Drops local rows, removes the local
    /// membership, creates a fresh personal household so the user lands
    /// on hid = uid rather than a null scope. No-op when already in a
    /// personal household.
    func leaveHousehold() async throws
}

/// A freshly-generated invite code, ready to share.
struct InviteCode: Equatable, Sendable {
    /// The 8-character Crockford base32 token.
    let token: String
    /// Epoch millis when the token expires (now + 24h on generation).
    let expiresAt: Int64
}

/// Result of an invite-accept attempt. Mirrors Android's sealed class.
enum InviteResult: Equatable, Sendable {
    case success(householdId: String)
    case notFound
    case expired
    case alreadyUsed
    case failed(reason: String)
}

/// Production implementation. Wires Firestore + the household DAO + the
/// pull-write DAO (for the wipe) + the household switcher (which re-runs
/// the sync gate after invite-accept / leave).
final class FirestoreHouseholdRepository: HouseholdRepository, @unchecked Sendable {
    private let firestore: Firestore
    private let householdMemberDao: HouseholdMemberDao
    private let pullWriteDao: PullWriteDao
    private let userSession: any UserSessionProvider
    private let householdSession: any HouseholdSessionProvider
    private let householdSwitcher: any HouseholdSwitcher
    private let clock: any Clock

    init(
        firestore: Firestore,
        householdMemberDao: HouseholdMemberDao,
        pullWriteDao: PullWriteDao,
        userSession: any UserSessionProvider,
        householdSession: any HouseholdSessionProvider,
        householdSwitcher: any HouseholdSwitcher,
        clock: any Clock
    ) {
        self.firestore = firestore
        self.householdMemberDao = householdMemberDao
        self.pullWriteDao = pullWriteDao
        self.userSession = userSession
        self.householdSession = householdSession
        self.householdSwitcher = householdSwitcher
        self.clock = clock
    }

    func observeMembers() -> AsyncStream<[HouseholdMember]> {
        // Bridge from the household stream — every time the active household
        // flips, re-key the DAO observation. The DAO returns a throwing
        // AsyncValueObservation (GRDB convention); on iteration error we
        // emit an empty list and let the next householdIdStream tick
        // restart things rather than poisoning the AsyncStream.
        AsyncStream { continuation in
            let task = Task {
                for await hid in householdSession.householdIdStream {
                    guard let hid else {
                        continuation.yield([])
                        continue
                    }
                    do {
                        for try await members in householdMemberDao.observeMembersOf(householdId: hid) {
                            continuation.yield(members)
                        }
                    } catch {
                        continuation.yield([])
                    }
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    func generateInvite() async throws -> InviteCode {
        let uid = try await userSession.requireSignedIn()
        let hid = try await householdSession.requireHouseholdId()
        let now = clock.nowMs()
        let expiresAt = now + Self.inviteTtlMillis
        let token = Self.randomInviteToken()
        try await firestore.collection(Self.invitesCollection)
            .document(token)
            .setData([
                "householdId": hid,
                "createdBy": uid,
                "createdAt": now,
                "expiresAt": expiresAt,
                "accepted": false,
                "acceptedBy": NSNull(),
                "acceptedAt": NSNull(),
            ])
        return InviteCode(token: token, expiresAt: expiresAt)
    }

    func acceptInvite(token: String) async -> InviteResult {
        do {
            let uid = try await userSession.requireSignedIn()
            let currentHousehold = try await householdSession.requireHouseholdId()
            let doc = firestore.collection(Self.invitesCollection).document(token)
            let snap = try await doc.getDocument()
            guard snap.exists else { return .notFound }
            let expiresAt = snap.get("expiresAt") as? Int64 ?? 0
            if expiresAt < clock.nowMs() { return .expired }
            if (snap.get("accepted") as? Bool) == true { return .alreadyUsed }
            guard let newHouseholdId = snap.get("householdId") as? String else {
                return .failed(reason: "Invite document missing householdId")
            }

            let now = clock.nowMs()

            // Stamp the invite accepted first. Security rules block any
            // change other than the four whitelisted fields; concurrent
            // accepts surface as AlreadyUsed on a retry.
            try await doc.updateData([
                "accepted": true,
                "acceptedBy": uid,
                "acceptedAt": now,
                "updatedAt": now,
            ])

            // Wipe local rows under the user's PRIOR household before the
            // switch. The prior membership row stays soft-deleted (keeps
            // sync auditing) — only nuke it when leaving a SHARED
            // household, not the user's own personal one.
            try await pullWriteDao.wipeAllForHousehold(householdId: currentHousehold)
            if currentHousehold != uid {
                try await householdMemberDao.softDelete(
                    uid: uid, householdId: currentHousehold, now: now
                )
            }

            // Insert new membership. SyncEngine pushes to
            // /memberships/{uid}/households/{hid} on the next tick.
            try await householdMemberDao.upsert(
                HouseholdMember(
                    uid: uid,
                    householdId: newHouseholdId,
                    displayName: nil,
                    joinedAt: now,
                    isOwner: false,
                    createdAt: now,
                    updatedAt: now,
                    deletedAt: nil,
                    pendingSync: true
                )
            )

            // Flip the active household: re-runs sync gate against the new
            // path, then publishes the household id.
            await householdSwitcher.switchToHousehold(newHouseholdId)

            return .success(householdId: newHouseholdId)
        } catch {
            return .failed(reason: error.localizedDescription)
        }
    }

    func leaveHousehold() async throws {
        let uid = try await userSession.requireSignedIn()
        let currentHousehold = try await householdSession.requireHouseholdId()
        if currentHousehold == uid {
            // Already in a personal household — leaving is a no-op.
            return
        }
        let now = clock.nowMs()

        // Best-effort cloud soft-delete first. If the round-trip fails we
        // still finish the local wipe + switch — the user sees Leave work
        // immediately and sync will catch the cloud membership on the
        // next push tick via observePendingPush.
        do {
            try await firestore.collection(Self.membershipsCollection)
                .document(uid)
                .collection(Self.householdsCollection)
                .document(currentHousehold)
                .updateData([
                    "deletedAt": now,
                    "updatedAt": now,
                ])
        } catch {
            // Swallow — see comment above.
        }

        try await pullWriteDao.wipeAllForHousehold(householdId: currentHousehold)
        try await householdMemberDao.softDelete(
            uid: uid, householdId: currentHousehold, now: now
        )

        // Insert / revive the personal household so the user lands in
        // hid = uid rather than a null scope.
        try await householdMemberDao.upsert(
            HouseholdMember(
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
        )

        await householdSwitcher.switchToHousehold(uid)
    }

    /// 8-character Crockford base32 (no 0/O/1/I/L) so users can dictate the
    /// code over a phone call without confusion. ~10^12 entropy is ample
    /// given the 24-hour TTL + single-use rule.
    ///
    /// Internal (not private) so HouseholdRepositoryTests can exercise the
    /// alphabet + length contract without spinning up Firestore.
    static func randomInviteToken() -> String {
        let alphabet = Array(inviteAlphabet)
        var out = ""
        out.reserveCapacity(inviteTokenLength)
        for _ in 0..<inviteTokenLength {
            let idx = Int.random(in: 0..<alphabet.count)
            out.append(alphabet[idx])
        }
        return out
    }

    private static let invitesCollection = "invites"
    private static let membershipsCollection = "memberships"
    private static let householdsCollection = "households"
    static let inviteTokenLength = 8
    static let inviteTtlMillis: Int64 = 24 * 60 * 60 * 1000

    // Crockford base32: 0-9 + A-Z minus the visually-ambiguous 0/O/1/I/L.
    static let inviteAlphabet = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
}
