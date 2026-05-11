import XCTest
@testable import Storehop

/// Pins the `FirestoreHouseholdRepository` invite-token contract — alphabet,
/// length, TTL constant — without spinning up Firestore.
///
/// The wider invite/accept/leave flows are integration concerns: they exercise
/// real `Firestore.collection(...).getDocument()` / `setData(...)` /
/// `updateData(...)` round-trips that the iOS repo uses concretely (no
/// protocol seam). Those flows are covered by:
///   - Android's `HouseholdRepositoryImplTest` (Mike+Amanda's flow on Kotlin),
///   - The v0.7.0 2-device smoke test in `docs/v0.7.0-smoke-test.md`.
///
/// Adding a Firebase emulator harness to iOS unit tests is tracked as a
/// follow-up; this file pins the parts that can be tested in isolation.
final class HouseholdRepositoryTests: XCTestCase {

    /// Mirrors Android `HouseholdRepositoryImplTest.generateInvite ... 8 chars,
    /// Crockford alphabet`. Tokens that drift outside the alphabet would let
    /// `O`/`0` and `I`/`L`/`1` collide when a user dictates a code by phone.
    func testInviteTokenIsEightCrockfordBase32Characters() {
        let allowed = CharacterSet(charactersIn: FirestoreHouseholdRepository.inviteAlphabet)
        for _ in 0..<200 {
            let token = FirestoreHouseholdRepository.randomInviteToken()
            XCTAssertEqual(token.count, FirestoreHouseholdRepository.inviteTokenLength)
            let unique = CharacterSet(charactersIn: token)
            XCTAssertTrue(
                unique.isSubset(of: allowed),
                "Token \(token) contains characters outside Crockford base32"
            )
        }
    }

    /// The visually-ambiguous characters (0/O/1/I/L) must NOT appear in the
    /// alphabet — they're the entire reason for the Crockford choice.
    func testInviteAlphabetExcludesAmbiguousCharacters() {
        for c in ["0", "O", "1", "I", "L"] {
            XCTAssertFalse(
                FirestoreHouseholdRepository.inviteAlphabet.contains(c),
                "Alphabet must not include \(c) (Crockford base32 rule)"
            )
        }
    }

    /// The 24h TTL is part of the v0.7.0 invite contract (matches Android).
    /// Drift here would either let stale invites accept indefinitely or
    /// fail-close so aggressively the inviter has to regenerate per attempt.
    func testInviteTtlIsExactly24Hours() {
        XCTAssertEqual(
            FirestoreHouseholdRepository.inviteTtlMillis,
            24 * 60 * 60 * 1000
        )
    }

    /// Sanity: two consecutive generations should not be identical. Failing
    /// this in practice is a 1-in-10^12 event, so a failure here means the
    /// RNG seed is pinned somehow — almost certainly a regression.
    func testGeneratedTokensAreNotPinned() {
        let a = FirestoreHouseholdRepository.randomInviteToken()
        let b = FirestoreHouseholdRepository.randomInviteToken()
        XCTAssertNotEqual(a, b)
    }
}
