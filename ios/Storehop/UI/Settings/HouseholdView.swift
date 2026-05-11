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

    var body: some View {
        Group {
            if let viewModel {
                content(viewModel: viewModel)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(String(localized: "household_title"))
        .onAppear {
            if viewModel == nil {
                let vm = HouseholdViewModel(
                    repository: container.householdRepository,
                    userSession: container.session,
                    householdSession: container.householdSession
                )
                vm.bind()
                viewModel = vm
            }
        }
        .onDisappear { viewModel?.teardown() }
    }

    @ViewBuilder
    private func content(viewModel: HouseholdViewModel) -> some View {
        Form {
            // Members section. Shows "Just you" for single-member households
            // (cleaner empty state than listing the one row).
            Section(header: Text(String(localized: "household_members_header"))) {
                if viewModel.isPersonalHousehold || viewModel.members.count <= 1 {
                    Text(String(localized: "household_members_just_you"))
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(viewModel.members, id: \.uid) { member in
                        HStack {
                            Text(member.displayName ?? member.uid)
                            Spacer()
                            if member.isOwner {
                                Text(String(localized: "household_members_owner_badge"))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }

            // Invite section. Generate button opens a sheet with the code.
            Section(header: Text(String(localized: "household_generate_invite_header"))) {
                Text(String(localized: "household_invite_explanation"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Button(String(localized: "household_generate_invite")) {
                    viewModel.generateInvite()
                }
            }

            // Join section. Inline error from typed InviteResult.
            Section(header: Text(String(localized: "household_join_with_code"))) {
                TextField(
                    String(localized: "household_invite_code_label"),
                    text: bindingForTokenInput(viewModel: viewModel)
                )
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled(true)
                if let errorKey = viewModel.joinErrorKey {
                    Text(String(localized: errorKey))
                        .font(.caption)
                        .foregroundStyle(.red)
                }
                Button(String(localized: "household_join_button")) {
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
                        Text(String(localized: "household_leave"))
                            .frame(maxWidth: .infinity, alignment: .center)
                    }
                }
            }
        }
        .sheet(item: viewModel.pendingInviteBinding) { invite in
            InviteCodeSheet(invite: invite) { viewModel.dismissPendingInvite() }
        }
        .alert(
            String(localized: "household_leave_confirm_title"),
            isPresented: viewModel.leaveConfirmationBinding
        ) {
            Button(String(localized: "household_leave_confirm_button"), role: .destructive) {
                viewModel.confirmLeave()
            }
            Button(String(localized: "household_leave_cancel_button"), role: .cancel) {
                viewModel.cancelLeave()
            }
        } message: {
            Text(String(localized: "household_leave_confirm_message"))
        }
        .alert(
            String(localized: "household_invite_dialog_title"),
            isPresented: viewModel.failedEventBinding
        ) {
            Button("OK", role: .cancel) { viewModel.acknowledgeFailure() }
        } message: {
            Text(viewModel.failureMessage ?? "")
        }
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
            Text(String(localized: "household_invite_dialog_title"))
                .font(.headline)
                .padding(.top, 24)
            Text(String(localized: "household_invite_dialog_message"))
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal)
            Text(invite.token)
                .font(.system(size: 32, weight: .bold, design: .monospaced))
                .padding(.vertical, 16)
            HStack {
                Button(String(localized: "household_invite_copy")) {
                    UIPasteboard.general.string = invite.token
                    onDismiss()
                }
                .buttonStyle(.borderedProminent)
                Button(String(localized: "household_invite_dismiss")) { onDismiss() }
                    .buttonStyle(.bordered)
            }
            .padding(.bottom, 24)
        }
        .presentationDetents([.medium])
    }
}

