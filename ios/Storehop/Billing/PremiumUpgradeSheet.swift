import SwiftUI

/// Explanatory sheet that surfaces when an un-entitled user taps a
/// Premium-gated action (Export Items / Export Categories / Generate
/// Invite). Replaces the previous "tap a locked button → drop straight
/// into Apple's bare StoreKit dialog" UX, which left users with no
/// explanation of what they were about to buy.
///
/// Mirrors the upsell tone of the existing `UpgradeToPremiumCard`
/// (which lives inline in Settings) but in a dedicated sheet so the
/// user gets the "Why am I being prompted?" answer before Apple's
/// payment chrome appears.
///
/// Three CTAs:
///   - Unlock — calls `storeKit.purchase()`. Apple's chrome then
///     handles the Apple ID prompt, password / Face ID, etc.
///   - Restore Purchases — for users who bought on another device of
///     the same Apple ID.
///   - Cancel — dismiss; user stays un-entitled.
///
/// Anonymous users can still buy: StoreKit only needs the Apple ID,
/// not the Firebase identity. The VIP-allowlist branch in
/// `EntitlementRepository` is independent of StoreKit and only fires
/// for known beta-tester emails after Google Sign-In.
struct PremiumUpgradeSheet: View {
    let priceLabel: String?
    let onUnlock: () async -> Void
    let onRestore: () async -> Void
    let onDismiss: () -> Void

    @State private var inFlight = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    header
                    benefitList
                    if let errorMessage {
                        Text(errorMessage)
                            .font(StorehopTypography.bodySmall)
                            .foregroundStyle(.red)
                    }
                    actions
                    Spacer(minLength: 8)
                }
                .padding(20)
            }
            .navigationTitle(L("premium_upgrade_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("action_cancel"), action: onDismiss)
                        .disabled(inFlight)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            Image(systemName: "sparkles")
                .font(.largeTitle)
                .foregroundStyle(StorehopColors.primary)
            Text(L("premium_upgrade_subtitle"))
                .font(StorehopTypography.bodyLarge)
                .foregroundStyle(StorehopColors.onBackground)
        }
    }

    private var benefitList: some View {
        VStack(alignment: .leading, spacing: 12) {
            benefitRow(
                icon: "person.2.fill",
                title: L("premium_benefit_household_title"),
                body: L("premium_benefit_household_body")
            )
            benefitRow(
                icon: "square.and.arrow.up.fill",
                title: L("premium_benefit_export_title"),
                body: L("premium_benefit_export_body")
            )
        }
    }

    private func benefitRow(icon: String, title: String, body: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon)
                .foregroundStyle(StorehopColors.primary)
                .frame(width: 24, alignment: .leading)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(StorehopTypography.titleSmall)
                Text(body).font(StorehopTypography.bodySmall)
                    .foregroundStyle(StorehopColors.onSurfaceVariant)
            }
        }
    }

    private var actions: some View {
        VStack(spacing: 12) {
            Button {
                runAction { await onUnlock() }
            } label: {
                HStack {
                    Spacer()
                    if inFlight {
                        ProgressView()
                            .progressViewStyle(.circular)
                            .tint(StorehopColors.onPrimary)
                    } else {
                        Text(unlockButtonLabel)
                            .font(StorehopTypography.titleSmall)
                            .foregroundStyle(StorehopColors.onPrimary)
                    }
                    Spacer()
                }
                .padding(.vertical, 14)
                .background(StorehopColors.primary,
                            in: RoundedRectangle(cornerRadius: StorehopShape.cornerLarge))
            }
            .buttonStyle(.plain)
            .disabled(inFlight)

            Button {
                runAction { await onRestore() }
            } label: {
                Text(L("premium_upgrade_restore_button"))
                    .font(StorehopTypography.labelMedium)
                    .foregroundStyle(StorehopColors.primary)
            }
            .buttonStyle(.plain)
            .disabled(inFlight)

            Text(L("premium_upgrade_footnote"))
                .font(StorehopTypography.bodySmall)
                .foregroundStyle(StorehopColors.onSurfaceVariant)
                .multilineTextAlignment(.center)
                .padding(.top, 4)
        }
    }

    private var unlockButtonLabel: String {
        if let priceLabel {
            return String(format: L("premium_upgrade_unlock_button %@"), priceLabel)
        }
        return L("premium_upgrade_unlock_button_no_price")
    }

    private func runAction(_ block: @escaping () async -> Void) {
        guard !inFlight else { return }
        inFlight = true
        errorMessage = nil
        Task { @MainActor in
            await block()
            inFlight = false
        }
    }
}
