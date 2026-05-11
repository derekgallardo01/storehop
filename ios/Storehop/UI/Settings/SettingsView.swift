import SwiftUI
import UIKit

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
                let vm = SettingsViewModel(
                    authClient: container.firebaseAuthClient,
                    googleSignIn: container.googleSignInUseCase,
                    prefs: container.userPreferences,
                    session: container.session,
                    pullCoordinator: container.pullCoordinator,
                    pullStateRepo: container.pullStateRepository
                )
                vm.bind()
                viewModel = vm
            }
        }
        .onDisappear { viewModel?.teardown() }
    }

    private func content(viewModel: SettingsViewModel) -> some View {
        Form {
            // Cloud sync banner — visible when last pull failed.
            if viewModel.pullState == .failed {
                Section {
                    CloudSyncBanner(onRetry: viewModel.retryPull)
                        .listRowBackground(Color.clear)
                        .listRowInsets(EdgeInsets())
                }
            }

            // Account card.
            Section(header: Text(String(localized: "account_title"))) {
                AccountCard(
                    account: viewModel.account,
                    busy: viewModel.busy,
                    onSignIn: {
                        if let presenter = topViewController() {
                            viewModel.signInWithGoogle(presenter: presenter)
                        }
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
                        Text(String(localized: "household_title"))
                            .font(StorehopTypography.titleMedium)
                        Text(String(localized: "household_settings_subtitle"))
                            .font(StorehopTypography.bodySmall)
                            .foregroundStyle(StorehopColors.onSurfaceVariant)
                    }
                }
            }

            // Theme picker.
            Section(header: Text(String(localized: "settings_theme_title"))) {
                Picker("", selection: themeBinding(viewModel: viewModel)) {
                    Text(String(localized: "settings_theme_system")).tag(ThemeMode.system)
                    Text(String(localized: "settings_theme_light")).tag(ThemeMode.light)
                    Text(String(localized: "settings_theme_dark")).tag(ThemeMode.dark)
                }
                .pickerStyle(.inline)
                .labelsHidden()
            }

            // Language picker.
            Section(header: Text(String(localized: "settings_language_title"))) {
                Picker("", selection: localeBinding(viewModel: viewModel)) {
                    Text(String(localized: "settings_language_system")).tag("")
                    Text(String(localized: "settings_language_english")).tag("en")
                    Text(String(localized: "settings_language_pt_pt")).tag("pt-PT")
                    Text(String(localized: "settings_language_es")).tag("es")
                    Text(String(localized: "settings_language_it")).tag("it")
                }
                .pickerStyle(.inline)
                .labelsHidden()
            }

            // CSV import / export.
            DataSettingsSection()

            // Statistics.
            Section {
                NavigationLink {
                    StatisticsView()
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(String(localized: "statistics_title"))
                            .font(StorehopTypography.titleMedium)
                        Text(String(localized: "statistics_settings_link_subtitle"))
                            .font(StorehopTypography.bodySmall)
                            .foregroundStyle(StorehopColors.onSurfaceVariant)
                    }
                }
            }

            // About: version + privacy link. Mirrors Android's AboutCard so
            // the two ports surface the same metadata.
            Section(header: Text(String(localized: "settings_section_about"))) {
                AboutSection()
            }
        }
        .navigationTitle(String(localized: "title_settings"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(String(localized: "action_done"), action: onDismiss)
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
    let onSignIn: () -> Void
    let onSignOut: () -> Void

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
                    Text(String(localized: "account_anonymous_label"))
                        .font(StorehopTypography.titleMedium)
                    Text(String(localized: "account_anonymous_description"))
                        .font(StorehopTypography.bodySmall)
                        .foregroundStyle(StorehopColors.onSurfaceVariant)
                }
            }
            Button(action: onSignIn) {
                HStack {
                    Image(systemName: "person.badge.shield.checkmark")
                    Text(String(localized: "action_sign_in_with_google"))
                    if busy { Spacer(); ProgressView() }
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
            }
            .buttonStyle(.borderedProminent)
            .tint(StorehopColors.primary)
            .disabled(busy)
            Text(String(localized: "account_sign_in_helper"))
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
                    Text(account.displayName ?? String(localized: "account_signed_in_default"))
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
                    Text(String(localized: "action_sign_out"))
                    if busy { Spacer(); ProgressView() }
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
            }
            .buttonStyle(.bordered)
            .disabled(busy)
            Text(String(localized: "account_sign_out_helper"))
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
        return String(format: String(localized: "settings_about_version_format %@ %@"), v, b)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(versionString)
                .font(StorehopTypography.bodyMedium)
                .padding(.vertical, 8)
            Link(destination: URL(string: "https://derekgallardo01.github.io/storehop/privacy-policy")!) {
                HStack {
                    Text(String(localized: "settings_about_privacy"))
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
    let onRetry: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "exclamationmark.icloud.fill")
                .foregroundStyle(StorehopColors.onPrimaryContainer)
            VStack(alignment: .leading, spacing: 4) {
                Text(String(localized: "cloud_sync_incomplete"))
                    .font(StorehopTypography.titleSmall)
                Text(String(localized: "cloud_sync_retry_explanation"))
                    .font(StorehopTypography.bodySmall)
            }
            Spacer()
            Button(String(localized: "action_retry"), action: onRetry)
                .buttonStyle(.bordered)
                .tint(StorehopColors.primary)
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
