import SwiftUI
import UIKit
import AuthenticationServices

struct SettingsView: View {
    let onDismiss: () -> Void

    @Environment(AppContainer.self) private var container
    @State private var viewModel: SettingsViewModel?

    var body: some View {
        Group {
            if let viewModel {
                content(viewModel: viewModel)
            } else {
                ProgressView()
            }
        }
        .onAppear {
            if viewModel == nil {
                viewModel = SettingsViewModel(
                    authClient: container.firebaseAuthClient,
                    googleSignIn: container.googleSignInUseCase,
                    appleSignIn: container.signInWithAppleUseCase,
                    prefs: container.userPreferences,
                    session: container.session,
                    pullCoordinator: container.pullCoordinator,
                    pullStateRepo: container.pullStateRepository
                )
            }
            viewModel?.bind()
        }
        .onDisappear { viewModel?.teardown() }
    }

    private func content(viewModel: SettingsViewModel) -> some View {
        Form {
            // Cloud sync banner — visible when last pull failed.
            if viewModel.pullState == .failed {
                Section {
                    CloudSyncBanner(
                        reason: viewModel.pullFailureReason,
                        onRetry: viewModel.retryPull
                    )
                        .listRowBackground(Color.clear)
                        .listRowInsets(EdgeInsets())
                }
            }

            // Account card.
            Section(header: Text(L("account_title"))) {
                AccountCard(
                    account: viewModel.account,
                    busy: viewModel.busy,
                    onSignInWithGoogle: {
                        if let presenter = topViewController() {
                            viewModel.signInWithGoogle(presenter: presenter)
                        }
                    },
                    prepareAppleNonce: { viewModel.prepareAppleNonce() },
                    onSignInWithAppleResult: { result, rawNonce in
                        viewModel.handleAppleSignInResult(result: result, rawNonce: rawNonce)
                    },
                    onSignOut: viewModel.signOut
                )
                if let error = viewModel.error {
                    Text(error)
                        .foregroundStyle(.red)
                        .font(StorehopTypography.bodySmall)
                }
                // v0.7.0: Settings → Household tap-target sits directly
                // under the Account section since invites are only useful
                // for signed-in users (an anonymous user can still tap
                // through and see "Just you").
                NavigationLink {
                    HouseholdView()
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L("household_title"))
                            .font(StorehopTypography.titleMedium)
                        Text(L("household_settings_subtitle"))
                            .font(StorehopTypography.bodySmall)
                            .foregroundStyle(StorehopColors.onSurfaceVariant)
                    }
                }
            }

            // Theme picker.
            Section(header: Text(L("settings_theme_title"))) {
                Picker("", selection: themeBinding(viewModel: viewModel)) {
                    Text(L("settings_theme_system")).tag(ThemeMode.system)
                    Text(L("settings_theme_light")).tag(ThemeMode.light)
                    Text(L("settings_theme_dark")).tag(ThemeMode.dark)
                }
                .pickerStyle(.inline)
                .labelsHidden()
            }

            // Language picker.
            Section(header: Text(L("settings_language_title"))) {
                Picker("", selection: localeBinding(viewModel: viewModel)) {
                    Text(L("settings_language_system")).tag("")
                    Text(L("settings_language_english")).tag("en")
                    Text(L("settings_language_pt_pt")).tag("pt-PT")
                    Text(L("settings_language_es")).tag("es")
                    Text(L("settings_language_it")).tag("it")
                }
                .pickerStyle(.inline)
                .labelsHidden()
            }

            // CSV import / export.
            DataSettingsSection()

            // v0.7.1: Force-sync-now — drains every pendingSync = 1
            // row + the user-prefs doc so the user can safely uninstall.
            ForceSyncSection()

            // v0.8: Premium upsell. Hidden when entitlement is unlocked
            // (premium or legacy user); visible to non-entitled with the
            // App-Store-localized price.
            UpgradeToPremiumCard()

            // Statistics.
            Section {
                NavigationLink {
                    StatisticsView()
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L("statistics_title"))
                            .font(StorehopTypography.titleMedium)
                        Text(L("statistics_settings_link_subtitle"))
                            .font(StorehopTypography.bodySmall)
                            .foregroundStyle(StorehopColors.onSurfaceVariant)
                    }
                }
            }

            // About: version + privacy link. Mirrors Android's AboutCard so
            // the two ports surface the same metadata.
            Section(header: Text(L("settings_section_about"))) {
                AboutSection()
            }
        }
        .navigationTitle(L("title_settings"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(L("action_done"), action: onDismiss)
            }
        }
    }

    private func themeBinding(viewModel: SettingsViewModel) -> Binding<ThemeMode> {
        Binding(
            get: { viewModel.themeMode },
            set: { viewModel.setThemeMode($0) }
        )
    }

    private func localeBinding(viewModel: SettingsViewModel) -> Binding<String> {
        Binding(
            get: { viewModel.localeTag },
            set: { viewModel.setLocale($0) }
        )
    }
}

// MARK: - Subviews

private struct AccountCard: View {
    let account: AccountInfo
    let busy: Bool
    let onSignInWithGoogle: () -> Void
    /// Vended by the view model; returns a fresh raw + SHA256 nonce pair
    /// per attempt. The raw value lives in `currentRawNonce` until
    /// Apple's sheet returns, at which point we hand it back to
    /// `onSignInWithAppleResult` so Firebase can verify the round-trip.
    let prepareAppleNonce: () -> AppleNonce
    let onSignInWithAppleResult: (Result<ASAuthorization, Error>, String) -> Void
    let onSignOut: () -> Void

    // Sign in with Apple button styling flips based on the current
    // appearance — Apple's HIG requires `.white` on dark backgrounds and
    // `.black` on light. SwiftUI's environment gives us this directly.
    @Environment(\.colorScheme) private var colorScheme

    /// Set inside the button's `onRequest` (right before the sheet
    /// presents). The nonce regenerates per-attempt so a replay of one
    /// sign-in's token can't be reused for a later one.
    @State private var currentRawNonce: String = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if account.isAnonymous || account.uid == nil {
                anonymousBody
            } else {
                signedInBody
            }
        }
    }

    private var anonymousBody: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 12) {
                Image(systemName: "person.crop.circle.dashed")
                    .font(.title2)
                    .foregroundStyle(StorehopColors.onSurfaceVariant)
                VStack(alignment: .leading, spacing: 2) {
                    Text(L("account_anonymous_label"))
                        .font(StorehopTypography.titleMedium)
                    Text(L("account_anonymous_description"))
                        .font(StorehopTypography.bodySmall)
                        .foregroundStyle(StorehopColors.onSurfaceVariant)
                }
            }
            Button(action: onSignInWithGoogle) {
                HStack {
                    Image(systemName: "person.badge.shield.checkmark")
                    Text(L("action_sign_in_with_google"))
                    if busy { Spacer(); ProgressView() }
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
            }
            .buttonStyle(.borderedProminent)
            .tint(StorehopColors.primary)
            .disabled(busy)
            // v0.8.1.3-ios (build 52): Sign in with Apple. Required by
            // App Store Review Guideline 4.8 as an equivalent option
            // whenever a third-party social login is offered. SwiftUI's
            // first-party `SignInWithAppleButton` gives the HIG-compliant
            // visual + localized label automatically; we only wire the
            // request scope + completion handler.
            SignInWithAppleButton(
                .signIn,
                onRequest: { request in
                    request.requestedScopes = [.fullName, .email]
                    let nonce = prepareAppleNonce()
                    currentRawNonce = nonce.raw
                    request.nonce = nonce.sha256
                },
                onCompletion: { result in
                    onSignInWithAppleResult(result, currentRawNonce)
                    currentRawNonce = ""
                }
            )
            .signInWithAppleButtonStyle(colorScheme == .dark ? .white : .black)
            .frame(maxWidth: .infinity, minHeight: 44)
            .disabled(busy)
            Text(L("account_sign_in_helper"))
                .font(StorehopTypography.bodySmall)
                .foregroundStyle(StorehopColors.onSurfaceVariant)
        }
    }

    private var signedInBody: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 12) {
                Image(systemName: "person.crop.circle.fill.badge.checkmark")
                    .font(.title2)
                    .foregroundStyle(StorehopColors.primary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(account.displayName ?? L("account_signed_in_default"))
                        .font(StorehopTypography.titleMedium)
                    if let email = account.email {
                        Text(email)
                            .font(StorehopTypography.bodySmall)
                            .foregroundStyle(StorehopColors.onSurfaceVariant)
                    }
                }
            }
            Button(role: .destructive, action: onSignOut) {
                HStack {
                    Image(systemName: "rectangle.portrait.and.arrow.right")
                    Text(L("action_sign_out"))
                    if busy { Spacer(); ProgressView() }
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
            }
            .buttonStyle(.bordered)
            .disabled(busy)
            Text(L("account_sign_out_helper"))
                .font(StorehopTypography.bodySmall)
                .foregroundStyle(StorehopColors.onSurfaceVariant)
        }
    }
}

/// About card: version + privacy + source links. The two link rows open
/// the URLs in the user's default browser via `UIApplication.open(_:)`.
private struct AboutSection: View {
    private var versionString: String {
        let v = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        let b = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
        return String(format: L("settings_about_version_format %@ %@"), v, b)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(versionString)
                .font(StorehopTypography.bodyMedium)
                .padding(.vertical, 8)
            Link(destination: URL(string: "https://derekgallardo01.github.io/storehop/privacy-policy")!) {
                HStack {
                    Text(L("settings_about_privacy"))
                        .font(StorehopTypography.bodyLarge)
                    Spacer()
                    Image(systemName: "chevron.right")
                        .foregroundStyle(StorehopColors.onSurfaceVariant)
                }
                .padding(.vertical, 8)
            }
        }
    }
}

private struct CloudSyncBanner: View {
    /// v0.8.1.2: optional diagnostic reason from the underlying Firestore
    /// error. Tap-to-reveal so the banner stays tidy in the common case
    /// (transient network blip → "Retry" usually clears it) but the
    /// detailed message is one tap away for debugging.
    let reason: String?
    let onRetry: () -> Void

    @State private var showDetails: Bool = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: "exclamationmark.icloud.fill")
                    .foregroundStyle(StorehopColors.onPrimaryContainer)
                VStack(alignment: .leading, spacing: 4) {
                    Text(L("cloud_sync_incomplete"))
                        .font(StorehopTypography.titleSmall)
                    Text(L("cloud_sync_retry_explanation"))
                        .font(StorehopTypography.bodySmall)
                }
                Spacer()
                Button(L("action_retry"), action: onRetry)
                    .buttonStyle(.bordered)
                    .tint(StorehopColors.primary)
            }
            if reason != nil {
                Button {
                    withAnimation(.easeInOut(duration: 0.2)) { showDetails.toggle() }
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: showDetails ? "chevron.up" : "chevron.down")
                            .font(.caption2)
                        Text(showDetails
                             ? L("cloud_sync_hide_details")
                             : L("cloud_sync_show_details"))
                            .font(StorehopTypography.labelSmall)
                    }
                    .foregroundStyle(StorehopColors.onPrimaryContainer)
                }
                .buttonStyle(.plain)
                if showDetails, let reason {
                    Text(reason)
                        .font(StorehopTypography.bodySmall.monospaced())
                        .foregroundStyle(StorehopColors.onPrimaryContainer)
                        .textSelection(.enabled)
                        .padding(.top, 2)
                }
            }
        }
        .foregroundStyle(StorehopColors.onPrimaryContainer)
        .padding(12)
        .background(StorehopColors.primaryContainer, in: RoundedRectangle(cornerRadius: StorehopShape.cornerMedium))
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
    }
}

// MARK: - Top view-controller helper

/// Walks the active scene's window hierarchy to find the topmost view
/// controller — used as the presenter for `GIDSignIn.sharedInstance.signIn`.
@MainActor
private func topViewController() -> UIViewController? {
    let keyWindow = UIApplication.shared.connectedScenes
        .compactMap { $0 as? UIWindowScene }
        .flatMap(\.windows)
        .first(where: \.isKeyWindow)
    guard var current = keyWindow?.rootViewController else { return nil }
    while let presented = current.presentedViewController {
        current = presented
    }
    return current
}
