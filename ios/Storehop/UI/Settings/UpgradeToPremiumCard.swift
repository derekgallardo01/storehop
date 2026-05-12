import SwiftUI

/// v0.8: upsell card for the `premium_lifetime` IAP. Visible only when
/// the user is `.notEntitled`. `.premium` and `.legacyUser` hide it
/// because the gate is already lifted for those users.
///
/// Renders the value props (household invites + CSV export), the
/// App-Store-localized price on the CTA, and a "Restore purchases"
/// link underneath for users who paid on another device of the same
/// Apple ID. Mirrors Android's `UpgradeToPremiumCard` Composable.
struct UpgradeToPremiumCard: View {
    @Environment(AppContainer.self) private var container

    @State private var entitlement: Entitlement = .notEntitled
    @State private var localizedPrice: String?
    @State private var restoreInFlight = false

    var body: some View {
        Group {
            if entitlement.isUnlocked {
                // Hide the entire card when the gate is already lifted —
                // showing "you have Premium" copy to grandfathered users
                // would just look like noise.
                EmptyView()
            } else {
                Section(header: Text(String(localized: "premium_card_title"))) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("• " + String(localized: "premium_card_body_household"))
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                        Text("• " + String(localized: "premium_card_body_export"))
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 4)

                    Button(action: { Task { await launchPurchase() } }) {
                        Text(unlockButtonLabel)
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)

                    Button(action: { Task { await restorePurchases() } }) {
                        Text(String(localized: "premium_restore_purchases"))
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .disabled(restoreInFlight)
                }
            }
        }
        .task { await observeEntitlement() }
        .task { await observePrice() }
    }

    private var unlockButtonLabel: String {
        if let price = localizedPrice {
            return String(format: String(localized: "premium_cta_unlock %@"), price)
        }
        return String(localized: "premium_cta_unlock_loading")
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

    @MainActor
    private func launchPurchase() async {
        guard let storeKit = container.storeKitManager else { return }
        _ = await storeKit.purchase()
    }

    @MainActor
    private func restorePurchases() async {
        guard let repo = container.entitlementRepository else { return }
        restoreInFlight = true
        await repo.restorePurchases()
        restoreInFlight = false
    }
}
