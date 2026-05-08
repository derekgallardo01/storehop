import Foundation
import UIKit
import FirebaseCore
import GoogleSignIn

/// "Continue with Google" flow. Mirrors Android `GoogleSignInUseCase`:
///
///   1. Present the Google Sign-In sheet via `GIDSignIn`.
///   2. Build a Firebase credential from the returned ID token.
///   3. If currently signed in anonymously, **link** the credential to the
///      existing Firebase account so the uid is preserved (no data
///      migration needed). If link fails because the Google account is
///      already attached to another Firebase user, fall back to a plain
///      `signIn(with:)`.
///
/// The fallback's data semantics: the prior anonymous uid's local rows
/// become orphans (invisible under the new uid). Acceptable for v1; a
/// "carry my data" path can come later.
struct GoogleSignInUseCase: Sendable {
    let authClient: any FirebaseAuthClient

    /// Launches the Sign In with Google sheet and runs the link-or-sign-in
    /// branch. Caller passes the presenting `UIViewController`; SwiftUI
    /// callers grab it from their scene's `windowScene`.
    @discardableResult
    func signIn(presenter: UIViewController) async throws -> SignInOutcome {
        // Configure GIDSignIn with the Firebase web client ID. Done lazily
        // here rather than at app launch so a missing/malformed config
        // surfaces at the moment of use rather than as a silent crash.
        try ensureGoogleSignInConfigured()

        let gidUser = try await Self.presentGoogleSignIn(on: presenter)
        guard let idToken = gidUser.idToken?.tokenString else {
            throw GoogleSignInError.missingIdToken
        }
        let credential = authClient.makeGoogleCredential(idToken: idToken)

        if await authClient.currentUserIsAnonymous {
            do {
                let uid = try await authClient.linkAnonymousWithGoogle(credential: credential)
                return .linkedFromAnonymous(uid: uid)
            } catch {
                // Google account already attached to another Firebase user.
                // Fall back to a plain sign-in. Anon uid's local rows go
                // orphan; LocalOnlyMigrationDao's claim path will restamp
                // them under the new uid the next time PullCoordinator runs.
                let uid = try await authClient.signInWithGoogle(credential: credential)
                return .signedInAfterLinkFailed(uid: uid)
            }
        } else {
            let uid = try await authClient.signInWithGoogle(credential: credential)
            return .signedInFresh(uid: uid)
        }
    }

    private func ensureGoogleSignInConfigured() throws {
        if GIDSignIn.sharedInstance.configuration != nil { return }
        guard let clientId = FirebaseApp.app()?.options.clientID else {
            throw GoogleSignInError.firebaseClientIdMissing
        }
        GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientId)
    }

    @MainActor
    private static func presentGoogleSignIn(on presenter: UIViewController) async throws -> GIDGoogleUser {
        try await withCheckedThrowingContinuation { continuation in
            GIDSignIn.sharedInstance.signIn(withPresenting: presenter) { result, error in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }
                guard let user = result?.user else {
                    continuation.resume(throwing: GoogleSignInError.missingUser)
                    return
                }
                continuation.resume(returning: user)
            }
        }
    }
}

enum SignInOutcome: Sendable, Equatable {
    /// Anonymous → Google upgrade succeeded; uid preserved.
    case linkedFromAnonymous(uid: String)
    /// Link failed (Google account already attached); signed in fresh and
    /// the prior anon uid's rows are now orphans (claim-on-next-uid).
    case signedInAfterLinkFailed(uid: String)
    /// User wasn't anonymous; signed in plainly.
    case signedInFresh(uid: String)
}

enum GoogleSignInError: Error, Equatable {
    case missingIdToken
    case missingUser
    case firebaseClientIdMissing
}
