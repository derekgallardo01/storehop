import Foundation
import AuthenticationServices
import CryptoKit

/// "Continue with Apple" flow. Required since iOS build 52 to satisfy
/// App Store Review Guideline 4.8 — whenever a third-party social login
/// is offered (we have Google Sign-In), Apple requires Sign in with
/// Apple as an equivalent option.
///
/// Presentation is owned by SwiftUI's `SignInWithAppleButton` (Apple's
/// HIG requires that specific button appearance for App Store approval).
/// This use case owns the two halves SwiftUI can't:
///   1. **Nonce generation** — Firebase requires a raw nonce + Apple's
///      ID token to construct the credential. Apple gets the SHA256
///      hash of the raw nonce; Firebase later verifies the supplied raw
///      nonce hashes to the value embedded in the ID token. The view
///      calls `prepareNonce()` once before the button's `onRequest`
///      fires, stashes the hashed value on the request, and keeps the
///      raw value around to hand back to `completeSignIn(...)` after
///      Apple round-trips.
///   2. **Firebase exchange** — given the Apple ID credential + the raw
///      nonce that produced it, build a Firebase credential and run the
///      same anonymous→link-or-signin dance Google uses.
struct SignInWithAppleUseCase: Sendable {
    let authClient: any FirebaseAuthClient

    /// Generates a fresh random nonce. Returns both the raw value (keep
    /// in view-local state; pass to `completeSignIn` after Apple returns)
    /// and the SHA256 hash (put on the `ASAuthorizationAppleIDRequest`
    /// before submitting).
    func prepareNonce() -> AppleNonce {
        let raw = Self.randomNonceString()
        return AppleNonce(raw: raw, sha256: Self.sha256(raw))
    }

    /// Take Apple's returned ID credential plus the raw nonce that was
    /// hashed into the request and finish the Firebase side of sign-in.
    /// Mirrors `GoogleSignInUseCase.signIn(presenter:)`'s final block.
    @discardableResult
    func completeSignIn(
        with appleCredential: ASAuthorizationAppleIDCredential,
        rawNonce: String
    ) async throws -> SignInOutcome {
        guard let idTokenData = appleCredential.identityToken,
              let idToken = String(data: idTokenData, encoding: .utf8) else {
            throw SignInWithAppleError.missingIdToken
        }

        let credential = authClient.makeAppleCredential(
            idToken: idToken,
            rawNonce: rawNonce
        )

        if await authClient.currentUserIsAnonymous {
            do {
                let uid = try await authClient.linkAnonymousWithApple(credential: credential)
                return .linkedFromAnonymous(uid: uid)
            } catch {
                // Apple ID already attached to another Firebase user.
                // Fall back to a plain sign-in. Prior anon uid's local
                // rows go orphan; `LocalOnlyMigrationDao` restamps them
                // under the new uid on the next pull.
                let uid = try await authClient.signInWithApple(credential: credential)
                return .signedInAfterLinkFailed(uid: uid)
            }
        } else {
            let uid = try await authClient.signInWithApple(credential: credential)
            return .signedInFresh(uid: uid)
        }
    }

    // MARK: - Nonce helpers

    /// Cryptographically-random alphanumeric string per Apple's recommendation:
    /// https://firebase.google.com/docs/auth/ios/apple
    private static func randomNonceString(length: Int = 32) -> String {
        precondition(length > 0)
        let charset: [Character] = Array("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-._")
        var result = ""
        var remainingLength = length
        while remainingLength > 0 {
            let randoms: [UInt8] = (0..<16).map { _ in
                var random: UInt8 = 0
                let status = SecRandomCopyBytes(kSecRandomDefault, 1, &random)
                if status != errSecSuccess {
                    fatalError("SecRandomCopyBytes failed with status \(status)")
                }
                return random
            }
            for random in randoms where remainingLength > 0 {
                if Int(random) < charset.count {
                    result.append(charset[Int(random)])
                    remainingLength -= 1
                }
            }
        }
        return result
    }

    private static func sha256(_ input: String) -> String {
        let inputData = Data(input.utf8)
        let hashed = SHA256.hash(data: inputData)
        return hashed.compactMap { String(format: "%02x", $0) }.joined()
    }
}

/// Paired raw + SHA256 nonce. The raw value lives in view-local state
/// between `prepareNonce()` and `completeSignIn(with:rawNonce:)`.
struct AppleNonce: Sendable, Equatable {
    let raw: String
    let sha256: String
}

enum SignInWithAppleError: Error, Equatable {
    case missingIdToken
    case unexpectedCredentialType
    case missingNonce
}
