import Foundation
// See FirebaseImageUploader for the @preconcurrency rationale.
@preconcurrency import FirebaseAuth

/// Concrete `FirebaseAuthClient` backed by the Firebase iOS SDK.
///
/// Holds an `Auth` instance (the singleton from `Auth.auth()`) and translates
/// its callback-based API into Swift Concurrency. The `authStateStream` wraps
/// `addStateDidChangeListener` and tears down the listener when the stream
/// terminates, so a subscriber going out of scope doesn't leak listeners.
final class LiveFirebaseAuthClient: FirebaseAuthClient, @unchecked Sendable {
    private let auth: Auth

    init(auth: Auth = Auth.auth()) {
        self.auth = auth
    }

    var currentUserId: String? {
        get async { auth.currentUser?.uid }
    }

    var currentUserIsAnonymous: Bool {
        get async { auth.currentUser?.isAnonymous ?? false }
    }

    var currentAccount: AccountInfo {
        get async {
            guard let user = auth.currentUser else { return .none }
            return AccountInfo(
                uid: user.uid,
                isAnonymous: user.isAnonymous,
                email: user.email,
                displayName: user.displayName,
                photoURL: user.photoURL?.absoluteString
            )
        }
    }

    var accountStream: AsyncStream<AccountInfo> {
        AsyncStream { continuation in
            // Initial snapshot.
            let initial: AccountInfo
            if let user = auth.currentUser {
                initial = AccountInfo(
                    uid: user.uid, isAnonymous: user.isAnonymous,
                    email: user.email, displayName: user.displayName,
                    photoURL: user.photoURL?.absoluteString
                )
            } else {
                initial = .none
            }
            continuation.yield(initial)

            let handle = auth.addStateDidChangeListener { _, user in
                let info: AccountInfo
                if let user {
                    info = AccountInfo(
                        uid: user.uid, isAnonymous: user.isAnonymous,
                        email: user.email, displayName: user.displayName,
                        photoURL: user.photoURL?.absoluteString
                    )
                } else {
                    info = .none
                }
                continuation.yield(info)
            }
            continuation.onTermination = { @Sendable _ in
                self.auth.removeStateDidChangeListener(handle)
            }
        }
    }

    var authStateStream: AsyncStream<String?> {
        AsyncStream { continuation in
            // Emit current uid synchronously so subscribers don't see a
            // transient nil on cold launch when Firebase has the cached
            // credential ready.
            continuation.yield(auth.currentUser?.uid)

            let handle = auth.addStateDidChangeListener { _, user in
                continuation.yield(user?.uid)
            }

            continuation.onTermination = { @Sendable _ in
                self.auth.removeStateDidChangeListener(handle)
            }
        }
    }

    @discardableResult
    func signInAnonymously() async throws -> String {
        let result = try await auth.signInAnonymously()
        return result.user.uid
    }

    func signOut() throws {
        try auth.signOut()
    }

    func makeGoogleCredential(idToken: String) -> AnyAuthCredential {
        let credential = GoogleAuthProvider.credential(withIDToken: idToken, accessToken: "")
        return AnyAuthCredential(payload: credential)
    }

    @discardableResult
    func linkAnonymousWithGoogle(credential: AnyAuthCredential) async throws -> String {
        guard let auth = self.auth.currentUser else {
            throw FirebaseAuthClientError.noCurrentUser
        }
        guard let firebaseCredential = credential.payload as? AuthCredential else {
            throw FirebaseAuthClientError.invalidCredentialPayload
        }
        let result = try await auth.link(with: firebaseCredential)
        return result.user.uid
    }

    @discardableResult
    func signInWithGoogle(credential: AnyAuthCredential) async throws -> String {
        guard let firebaseCredential = credential.payload as? AuthCredential else {
            throw FirebaseAuthClientError.invalidCredentialPayload
        }
        let result = try await auth.signIn(with: firebaseCredential)
        return result.user.uid
    }

    func makeAppleCredential(idToken: String, rawNonce: String) -> AnyAuthCredential {
        // `OAuthProvider.appleCredential(idToken:rawNonce:fullName:)` is
        // the recommended path on iOS 13+ — it correctly populates the
        // Apple provider ID under the hood and avoids the older string-
        // based `OAuthProvider.credential(withProviderID:)` form. We
        // don't pass fullName here because the Apple HIG-required name
        // capture happens once at first sign-in and is best surfaced
        // via Firebase user profile metadata separately if we need it.
        let credential = OAuthProvider.appleCredential(
            withIDToken: idToken,
            rawNonce: rawNonce,
            fullName: nil
        )
        return AnyAuthCredential(payload: credential)
    }

    @discardableResult
    func linkAnonymousWithApple(credential: AnyAuthCredential) async throws -> String {
        guard let auth = self.auth.currentUser else {
            throw FirebaseAuthClientError.noCurrentUser
        }
        guard let firebaseCredential = credential.payload as? AuthCredential else {
            throw FirebaseAuthClientError.invalidCredentialPayload
        }
        let result = try await auth.link(with: firebaseCredential)
        return result.user.uid
    }

    @discardableResult
    func signInWithApple(credential: AnyAuthCredential) async throws -> String {
        guard let firebaseCredential = credential.payload as? AuthCredential else {
            throw FirebaseAuthClientError.invalidCredentialPayload
        }
        let result = try await auth.signIn(with: firebaseCredential)
        return result.user.uid
    }
}

enum FirebaseAuthClientError: Error, Equatable {
    case noCurrentUser
    case invalidCredentialPayload
}
