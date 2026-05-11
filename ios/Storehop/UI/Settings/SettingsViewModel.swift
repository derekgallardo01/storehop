import Foundation
import Observation
import UIKit

@Observable
@MainActor
final class SettingsViewModel {
    var account: AccountInfo = .none
    var themeMode: ThemeMode = .system
    var localeTag: String = ""  // "" = follow system
    var pullState: PullState = .succeeded
    var busy: Bool = false
    var error: String?

    private let authClient: any FirebaseAuthClient
    private let googleSignIn: GoogleSignInUseCase
    private let prefs: any UserPreferencesRepository
    private let session: any UserSessionProvider
    private let pullCoordinator: any PullCoordinator
    private let pullStateRepo: any PullStateRepository

    private var accountTask: Task<Void, Never>?
    private var themeTask: Task<Void, Never>?
    private var localeTask: Task<Void, Never>?
    private var pullStateTask: Task<Void, Never>?

    init(
        authClient: any FirebaseAuthClient,
        googleSignIn: GoogleSignInUseCase,
        prefs: any UserPreferencesRepository,
        session: any UserSessionProvider,
        pullCoordinator: any PullCoordinator,
        pullStateRepo: any PullStateRepository
    ) {
        self.authClient = authClient
        self.googleSignIn = googleSignIn
        self.prefs = prefs
        self.session = session
        self.pullCoordinator = pullCoordinator
        self.pullStateRepo = pullStateRepo
    }

    func bind() {
        accountTask = Task { @MainActor [weak self] in
            guard let self else { return }
            for await info in self.authClient.accountStream {
                self.account = info
            }
        }
        themeTask = Task { @MainActor [weak self] in
            guard let self else { return }
            for await mode in self.prefs.themeModeStream {
                self.themeMode = mode
            }
        }
        localeTask = Task { @MainActor [weak self] in
            guard let self else { return }
            for await tag in self.prefs.localeTagStream {
                self.localeTag = tag
            }
        }
        // Re-bind pull-state observation on each uid change.
        pullStateTask = Task { @MainActor [weak self] in
            guard let self else { return }
            var inner: Task<Void, Never>?
            for await uid in self.session.userIdStream {
                inner?.cancel()
                if let uid {
                    inner = Task { @MainActor [weak self] in
                        guard let self else { return }
                        for await state in self.pullStateRepo.observe(uid) {
                            self.pullState = state
                        }
                    }
                } else {
                    self.pullState = .succeeded
                }
            }
            inner?.cancel()
        }
    }

    func teardown() {
        accountTask?.cancel()
        themeTask?.cancel()
        localeTask?.cancel()
        pullStateTask?.cancel()
    }

    // MARK: - Theme + locale

    func setThemeMode(_ mode: ThemeMode) {
        prefs.setThemeMode(mode)
    }

    /// `tag` of "" means "follow system."
    func setLocale(_ tag: String) {
        prefs.setLocaleTag(tag)
    }

    // MARK: - Auth actions

    /// Caller passes the presenting `UIViewController` so the Google
    /// Sign-In sheet attaches to the right window. SwiftUI callers grab
    /// it from `windowScene` — see `UIApplication+TopController`.
    func signInWithGoogle(presenter: UIViewController) {
        guard !busy else { return }
        busy = true
        error = nil
        Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                _ = try await self.googleSignIn.signIn(presenter: presenter)
                // Wait until the gated session.userId flow flips to the
                // post-sign-in uid — `FirebaseAuthSessionProvider` holds
                // back the publish until its claim migration finishes, so
                // by the time we clear `busy` the Shop screen's next
                // query already sees the populated list. No flicker.
                let targetUid = await self.authClient.currentUserId
                if let targetUid {
                    for await uid in self.session.userIdStream where uid == targetUid {
                        break
                    }
                }
                self.busy = false
            } catch is GoogleSignInError {
                self.busy = false
                self.error = String(localized: "error_could_not_sign_in")
            } catch {
                self.busy = false
                // Cancellation by the user (sheet dismissed) is silent —
                // GIDSignInError.canceled. Other failures show the generic
                // copy.
                let nsError = error as NSError
                if nsError.code == -5 /* GIDSignInError.canceled */ {
                    return
                }
                self.error = String(localized: "error_could_not_sign_in")
            }
        }
    }

    /// Sign out and immediately sign back in anonymously so the app keeps
    /// working without an authenticated user. Loses access to the prior
    /// Google-linked uid's cloud data on this device; the cloud data is
    /// safe under the Google account and reappears on next sign-in.
    func signOut() {
        guard !busy else { return }
        busy = true
        error = nil
        Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                try self.authClient.signOut()
                _ = try await self.authClient.signInAnonymously()
                let targetUid = await self.authClient.currentUserId
                if let targetUid {
                    for await uid in self.session.userIdStream where uid == targetUid {
                        break
                    }
                }
                self.busy = false
            } catch {
                self.busy = false
                self.error = String(localized: "error_could_not_sign_out")
            }
        }
    }

    func clearError() { error = nil }

    /// Re-run the cloud pull for the current uid. Wired to the cloud-sync
    /// Retry banner. No-op when there's no signed-in uid (banner shouldn't
    /// be visible in that case anyway).
    func retryPull() {
        Task { @MainActor [weak self] in
            guard let self else { return }
            guard let uid = await self.session.currentUserId else { return }
            // v0.7.0: single-member households mirror uid → householdId.
            // Until Phase 2's HouseholdSessionProvider is wired into this
            // VM, mirror uid so the retry button keeps working. Phase 2
            // FirebaseAuthSessionProvider always publishes a household
            // alongside the uid, so this aliasing is correct for all
            // production users.
            let householdId = uid
            await self.pullStateRepo.set(.inProgress, for: uid)
            let result = await self.pullCoordinator.pullForHousehold(householdId)
            switch result {
            case .success:
                await self.pullStateRepo.set(.succeeded, for: uid)
            case .failure:
                await self.pullStateRepo.set(.failed, for: uid)
            }
        }
    }
}
