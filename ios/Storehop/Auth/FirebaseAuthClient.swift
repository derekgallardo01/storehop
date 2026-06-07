import Foundation

/// Thin abstraction over the parts of `FirebaseAuth` and the credential
/// flows that `FirebaseAuthSessionProvider` and `GoogleSignInUseCase`
/// actually use. Lets us write testable session-provider logic without
/// pulling Firebase into XCTest.
///
/// `LiveFirebaseAuthClient` (below) wraps the real Firebase SDK. Tests use
/// `MockFirebaseAuthClient` (in StorehopTests) which is in-memory.
/// User-facing account info for Settings. Populated from Firebase's
/// current user; identity-provider data (email, displayName, photo URL)
/// is only present after a Google sign-in.
struct AccountInfo: Sendable, Equatable {
    let uid: String?
    let isAnonymous: Bool
    let email: String?
    let displayName: String?
    let photoURL: String?

    static let none = AccountInfo(
        uid: nil, isAnonymous: true, email: nil, displayName: nil, photoURL: nil
    )
}

protocol FirebaseAuthClient: Sendable {
    /// Current uid, or nil if no Firebase user is signed in. Reading this
    /// must NOT block on network; Firebase caches the credential to disk
    /// so returning users get a uid synchronously on cold launch.
    var currentUserId: String? { get async }

    /// Full account info for the current user. Used by Settings to render
    /// the Account card (anon vs Google + name + email).
    var currentAccount: AccountInfo { get async }

    /// Stream of account-info changes — fires on sign-in, sign-out,
    /// anonymous→Google upgrade. Emits the current value on subscribe.
    var accountStream: AsyncStream<AccountInfo> { get }

    /// True iff the current user is anonymous (uid is from
    /// `signInAnonymously`, not a linked identity provider). Drives the
    /// link-vs-sign-in branch in Google sign-in.
    var currentUserIsAnonymous: Bool { get async }

    /// Hot stream of uid changes. Emits the current uid (or nil) on
    /// subscribe, then again on each Firebase auth state transition.
    var authStateStream: AsyncStream<String?> { get }

    /// Kicks off `signInAnonymously()` and resolves with the new uid.
    /// Throws on network failure or hosed Firebase config.
    @discardableResult
    func signInAnonymously() async throws -> String

    /// Sign out — clears the current Firebase user. Followed by
    /// `signInAnonymously()` so the app never sits without a uid.
    func signOut() throws

    /// Build a Firebase credential from a Google ID token. Returns an
    /// opaque token the caller can pass back to `linkAnonymousWithGoogle`
    /// or `signInWithGoogle`.
    func makeGoogleCredential(idToken: String) -> AnyAuthCredential

    /// Link `credential` to the current anonymous user. Preserves the uid.
    /// Throws if the credential is already attached to another Firebase
    /// account — caller falls back to `signInWithGoogle`.
    @discardableResult
    func linkAnonymousWithGoogle(credential: AnyAuthCredential) async throws -> String

    /// Plain sign-in with Google. Replaces the current uid with the one
    /// associated with `credential`. Used when the link path failed.
    @discardableResult
    func signInWithGoogle(credential: AnyAuthCredential) async throws -> String

    /// Build a Firebase credential from an Apple ID token + the raw nonce
    /// that was hashed into the original ASAuthorizationAppleIDRequest.
    /// Firebase needs both halves: the ID token contains the SHA256 of
    /// the nonce; Firebase verifies that the supplied `rawNonce` hashes
    /// to that value as a replay-attack guard.
    func makeAppleCredential(idToken: String, rawNonce: String) -> AnyAuthCredential

    /// Link `credential` to the current anonymous user. Preserves the uid.
    /// Throws if the Apple ID is already attached to another Firebase
    /// account — caller falls back to `signInWithApple`.
    @discardableResult
    func linkAnonymousWithApple(credential: AnyAuthCredential) async throws -> String

    /// Plain sign-in with Apple. Replaces the current uid with the one
    /// associated with `credential`. Used when the link path failed.
    @discardableResult
    func signInWithApple(credential: AnyAuthCredential) async throws -> String
}

/// Opaque wrapper so callers can pass Firebase credentials around without
/// importing FirebaseAuth into their files. The live impl boxes
/// `FirebaseAuth.AuthCredential` here; tests box a record-only stub.
///
/// `@unchecked Sendable` because Firebase's `AuthCredential` isn't formally
/// Sendable but its construct-once-pass-around-once usage pattern is safe
/// in practice — we never mutate the payload after construction.
struct AnyAuthCredential: @unchecked Sendable {
    let payload: Any
}
