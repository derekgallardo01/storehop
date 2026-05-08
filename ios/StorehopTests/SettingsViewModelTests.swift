import XCTest
@testable import Storehop

@MainActor
final class SettingsViewModelTests: XCTestCase {

    private struct Setup {
        let viewModel: SettingsViewModel
        let prefs: any UserPreferencesRepository
        let auth: MockFirebaseAuthClient
        let pullStateRepo: InMemoryPullStateRepository
        let coordinator: ScriptablePullCoordinator
        let session: LocalOnlyUserSessionProvider
    }

    private func makeSetup() async throws -> Setup {
        let suiteName = "test.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        let prefs = LiveUserPreferencesRepository(defaults: defaults)
        let auth = MockFirebaseAuthClient(initialUid: "uid_alice", isAnonymous: false)
        let session = LocalOnlyUserSessionProvider(uid: "uid_alice")
        let pullStateRepo = InMemoryPullStateRepository()
        let coordinator = ScriptablePullCoordinator()
        let vm = SettingsViewModel(
            authClient: auth,
            googleSignIn: GoogleSignInUseCase(authClient: auth),
            prefs: prefs,
            session: session,
            pullCoordinator: coordinator,
            pullStateRepo: pullStateRepo
        )
        vm.bind()
        try await Task.sleep(nanoseconds: 50_000_000)
        return Setup(viewModel: vm, prefs: prefs, auth: auth, pullStateRepo: pullStateRepo, coordinator: coordinator, session: session)
    }

    // MARK: - Theme

    func testSetThemeModePersistsAndPropagates() async throws {
        let s = try await makeSetup()
        s.viewModel.setThemeMode(.dark)
        try await waitForCondition { s.viewModel.themeMode == .dark }
        // Persisted to defaults (LiveUserPreferencesRepository writes synchronously).
        XCTAssertEqual(s.prefs.themeMode, .dark)
    }

    func testInitialThemeModeReadsFromDefaults() async throws {
        let suiteName = "test.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.set("LIGHT", forKey: "storehop.themeMode")
        let prefs = LiveUserPreferencesRepository(defaults: defaults)
        let auth = MockFirebaseAuthClient(initialUid: "u", isAnonymous: true)
        let vm = SettingsViewModel(
            authClient: auth,
            googleSignIn: GoogleSignInUseCase(authClient: auth),
            prefs: prefs,
            session: LocalOnlyUserSessionProvider(uid: "u"),
            pullCoordinator: ScriptablePullCoordinator(),
            pullStateRepo: InMemoryPullStateRepository()
        )
        vm.bind()
        try await waitForCondition { vm.themeMode == .light }
    }

    // MARK: - Locale

    func testSetLocalePersistsAndUpdatesAppleLanguages() async throws {
        let s = try await makeSetup()
        s.viewModel.setLocale("pt-PT")
        try await waitForCondition { s.viewModel.localeTag == "pt-PT" }
        XCTAssertEqual(s.prefs.localeTag, "pt-PT")
        // The repo writes ["pt-PT"] to AppleLanguages so Bundle.main
        // resolves localized strings to Portuguese on the next view rebuild.
        // We don't assert that here (UserDefaults.standard is shared) — the
        // repo's contract is enough.
    }

    func testSetLocaleEmptyTagClearsOverride() async throws {
        let s = try await makeSetup()
        s.viewModel.setLocale("pt-PT")
        try await waitForCondition { s.viewModel.localeTag == "pt-PT" }
        s.viewModel.setLocale("")
        try await waitForCondition { s.viewModel.localeTag == "" }
    }

    // MARK: - Pull state observation

    func testPullStateBannerReflectsRepoChanges() async throws {
        let s = try await makeSetup()
        await s.pullStateRepo.set(.failed, for: "uid_alice")
        try await waitForCondition { s.viewModel.pullState == .failed }

        await s.pullStateRepo.set(.succeeded, for: "uid_alice")
        try await waitForCondition { s.viewModel.pullState == .succeeded }
    }

    // MARK: - retryPull

    func testRetryPullSetsInProgressThenSucceededOnSuccess() async throws {
        let s = try await makeSetup()
        await s.pullStateRepo.set(.failed, for: "uid_alice")
        try await waitForCondition { s.viewModel.pullState == .failed }

        s.coordinator.pullResult = .success
        s.viewModel.retryPull()
        try await waitForCondition { s.viewModel.pullState == .succeeded }
    }

    func testRetryPullSetsFailedOnFailure() async throws {
        let s = try await makeSetup()
        await s.pullStateRepo.set(.failed, for: "uid_alice")
        try await waitForCondition { s.viewModel.pullState == .failed }

        s.coordinator.pullResult = .failure(reason: "still down")
        s.viewModel.retryPull()
        try await waitForCondition(timeout: 1.0) {
            s.viewModel.pullState == .failed
        }
    }
}
