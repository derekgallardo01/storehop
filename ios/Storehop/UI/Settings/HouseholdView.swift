import SwiftUI
import UIKit

/// v0.7.0 Phase 3b: Settings → Household. Mirrors the Android
/// HouseholdScreen layout: member list + Generate invite + Join with code
/// + Leave household (hidden when in a personal household). English
/// strings only; other locales follow once the Localizable.xcstrings
/// drift-check passes.
struct HouseholdView: View {
    @Environment(AppContainer.self) private var container
    @State private var viewModel: HouseholdViewModel?
    @State private var entitlement: Entitlement = .notEntitled
    @State private var localizedPrice: String?
    /// v0.8.1.2: same `PremiumUpgradeSheet` pattern as
    /// `DataSettingsSection` — un-entitled users tapping Generate Invite
    /// now get an explanatory sheet before Apple's bare StoreKit chrome.
    @State private var showUpgradeSheet: Bool = false

    var body: some View {
        Group {
            if let viewModel {
                content(viewModel: viewModel)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(L("household_title"))
        .onAppear {
            if viewModel == nil {
                viewModel = HouseholdViewModel(
                    repository: container.householdRepository,
                    userSession: container.session,
                    householdSession: container.householdSession
                )
            }
            // Rebind on every appear; bind() is idempotent.
            viewModel?.bind()
        }
        .onDisappear { viewModel?.teardown() }
        .task { await observeEntitlement() }
        .task { await observePrice() }
    }

    private func observeEntitlement() async {
        guard let repo = container.entitlementRepository else { return }
        for await ent in repo.entitlementStream {
            await MainActor.run { entitlement = ent }
        }
    }

    private func observePrice() async {
        guard let storeKit = container.storeKitManager else { return }
        for await product in storeKit.productStream {
            await MainActor.run { localizedPrice = product?.displayPrice }
        }
    }

    private var generateInviteButtonLabel: String {
        if entitlement.isUnlocked {
            return L("household_generate_invite")
        }
        if let price = localizedPrice {
            return String(format: L("premium_locked_invite_label %@"), price)
        }
        return L("premium_locked_invite_label_loading")
    }

    @MainActor
    private func launchPurchase() async {
        guard let storeKit = container.storeKitManager else { return }
        _ = await storeKit.purchase()
    }

    @ViewBuilder
    private func content(viewModel: HouseholdViewModel) -> some View {
        Form {
            // Members section. Shows "Just you" for single-member households
            // (cleaner empty state than listing the one row).
            Section(header: Text(L("household_members_header"))) {
                if viewModel.isPersonalHousehold || viewModel.members.count <= 1 {
                    Text(L("household_members_just_you"))
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(viewModel.members, id: \.uid) { member in
                        HStack {
                            Text(member.displayName ?? member.uid)
                            Spacer()
                            if member.isOwner {
                                Text(L("household_members_owner_badge"))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }

            // Invite section. Generate button opens a sheet with the code.
            // v0.8: gated behind Premium. The Join card below stays free
            // — inviter-pays model means accepting + using a shared
            // household is unconditionally free.
            Section(header: Text(L("household_generate_invite_header"))) {
                Text(L("household_invite_explanation"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if !entitlement.isUnlocked {
                    Text(L("premium_locked_invite_explainer"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Button(generateInviteButtonLabel) {
                    if entitlement.isUnlocked {
                        viewModel.generateInvite()
                    } else {
                        showUpgradeSheet = true
                    }
                }
            }

            // Join section. Inline error from typed InviteResult.
            Section(header: Text(L("household_join_with_code"))) {
                TextField(
                    L("household_invite_code_label"),
                    text: bindingForTokenInput(viewModel: viewModel)
                )
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled(true)
                if let errorKey = viewModel.joinErrorKey {
                    Text(String(localized: errorKey, bundle: currentLanguageBundle()))
                        .font(.caption)
                        .foregroundStyle(.red)
                }
                Button(L("household_join_button")) {
                    viewModel.acceptInvite()
                }
                .disabled(viewModel.tokenInput.trimmingCharacters(in: .whitespaces).isEmpty)
            }

            // Leave section. Hidden when in a personal household.
            if !viewModel.isPersonalHousehold {
                Section {
                    Button(role: .destructive) {
                        viewModel.requestLeaveConfirmation()
                    } label: {
                        Text(L("household_leave"))
                            .frame(maxWidth: .infinity, alignment: .center)
                    }
                }
            }
        }
        .sheet(item: viewModel.pendingInviteBinding) { invite in
            InviteCodeSheet(invite: invite) { viewModel.dismissPendingInvite() }
        }
        .alert(
            L("household_leave_confirm_title"),
            isPresented: viewModel.leaveConfirmationBinding
        ) {
            Button(L("household_leave_confirm_button"), role: .destructive) {
                viewModel.confirmLeave()
            }
            Button(L("household_leave_cancel_button"), role: .cancel) {
                viewModel.cancelLeave()
            }
        } message: {
            Text(L("household_leave_confirm_message"))
        }
        .alert(
            L("household_invite_dialog_title"),
            isPresented: viewModel.failedEventBinding
        ) {
            Button("OK", role: .cancel) { viewModel.acknowledgeFailure() }
        } message: {
            Text(viewModel.failureMessage ?? "")
        }
        .sheet(isPresented: $showUpgradeSheet) {
            PremiumUpgradeSheet(
                priceLabel: localizedPrice,
                onUnlock: {
                    await launchPurchase()
                    showUpgradeSheet = false
                },
                onRestore: {
                    await restorePurchases()
                    showUpgradeSheet = false
                },
                onDismiss: { showUpgradeSheet = false }
            )
        }
    }

    @MainActor
    private func restorePurchases() async {
        guard let storeKit = container.storeKitManager else { return }
        await storeKit.restorePurchases()
    }

    private func bindingForTokenInput(viewModel: HouseholdViewModel) -> Binding<String> {
        Binding(
            get: { viewModel.tokenInput },
            set: { viewModel.tokenInput = $0 }
        )
    }
}

/// Bottom-sheet that displays the freshly-generated invite code. Monospace
/// + bold so the 8 characters are legible at a glance; copy button writes
/// to the system pasteboard.
private struct InviteCodeSheet: View {
    let invite: InviteCode
    let onDismiss: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Text(L("household_invite_dialog_title"))
                .font(.headline)
                .padding(.top, 24)
            Text(L("household_invite_dialog_message"))
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal)
            Text(invite.token)
                .font(.system(size: 32, weight: .bold, design: .monospaced))
                .padding(.vertical, 16)
            HStack {
                Button(L("household_invite_copy")) {
                    UIPasteboard.general.string = invite.token
                    onDismiss()
                }
                .buttonStyle(.borderedProminent)
                Button(L("household_invite_dismiss")) { onDismiss() }
                    .buttonStyle(.bordered)
            }
            .padding(.bottom, 24)
        }
        .presentationDetents([.medium])
    }
}

