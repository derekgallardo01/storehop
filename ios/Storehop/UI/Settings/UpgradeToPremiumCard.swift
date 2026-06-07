import SwiftUI
import StoreKit

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
    @State private var product: Product?
    @State private var purchaseInFlight = false
    @State private var restoreInFlight = false
    @State private var purchaseAlert: PurchaseAlert?

    var body: some View {
        Group {
            if entitlement.isUnlocked {
                // Hide the entire card when the gate is already lifted —
                // showing "you have Premium" copy to grandfathered users
                // would just look like noise.
                EmptyView()
            } else {
                Section(header: Text(L("premium_card_title"))) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("• " + L("premium_card_body_household"))
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                        Text("• " + L("premium_card_body_export"))
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 4)

                    Button(action: { Task { await launchPurchase() } }) {
                        HStack {
                            Text(unlockButtonLabel)
                            if purchaseInFlight {
                                Spacer()
                                ProgressView()
                            }
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    // Build 54: disable when StoreKit hasn't returned the
                    // Product yet. Prior builds had this button always
                    // enabled — if `Product.products(for:)` was still
                    // pending (or had failed because the IAP wasn't
                    // attached to the App Store Connect submission, which
                    // is what the build-53 reviewer hit), tapping fired
                    // `purchase()` against a nil product, which returned
                    // `.failed` and we discarded it silently. From the
                    // reviewer's perspective: an unresponsive button.
                    .disabled(product == nil || purchaseInFlight)

                    Button(action: { Task { await restorePurchases() } }) {
                        Text(L("premium_restore_purchases"))
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .disabled(restoreInFlight)
                }
            }
        }
        .task { await observeEntitlement() }
        .task { await observePrice() }
        // Surface every non-success / non-cancellation outcome from
        // `StoreKit.purchase()`. Without this, prior builds silently
        // swallowed the result — see the .disabled comment above.
        .alert(item: $purchaseAlert) { alert in
            Alert(
                title: Text(alert.title),
                message: alert.message.map { Text($0) },
                dismissButton: .default(Text("OK"))
            )
        }
    }

    private var unlockButtonLabel: String {
        if let price = localizedPrice {
            return String(format: L("premium_cta_unlock %@"), price)
        }
        return L("premium_cta_unlock_loading")
    }

    private func observeEntitlement() async {
        guard let repo = container.entitlementRepository else { return }
        for await ent in repo.entitlementStream {
            await MainActor.run { entitlement = ent }
        }
    }

    private func observePrice() async {
        guard let storeKit = container.storeKitManager else { return }
        for await loadedProduct in storeKit.productStream {
            await MainActor.run {
                product = loadedProduct
                localizedPrice = loadedProduct?.displayPrice
            }
        }
    }

    @MainActor
    private func launchPurchase() async {
        guard let storeKit = container.storeKitManager else { return }
        purchaseInFlight = true
        let outcome = await storeKit.purchase()
        purchaseInFlight = false
        switch outcome {
        case .purchased:
            // Entitlement flips via the Transaction.updates observer
            // in EntitlementRepository; no extra UI work here. We
            // still post a confirmation alert because the App Review
            // sandbox transitions can be subtle and reviewers expect
            // visible feedback that the purchase succeeded.
            purchaseAlert = PurchaseAlert(
                title: "Purchase complete",
                message: "Storehop Premium is now unlocked. Thank you for supporting Storehop."
            )
        case .userCancelled:
            // User dismissed the Apple sheet — silent, matches the
            // Google / Apple sign-in cancel UX.
            break
        case .pending:
            purchaseAlert = PurchaseAlert(
                title: "Purchase pending",
                message: "Your purchase is awaiting approval (likely Ask to Buy). You will be notified when it completes."
            )
        case .failed(let reason):
            purchaseAlert = PurchaseAlert(
                title: "Purchase failed",
                message: reason
            )
        }
    }

    @MainActor
    private func restorePurchases() async {
        guard let repo = container.entitlementRepository else { return }
        restoreInFlight = true
        await repo.restorePurchases()
        restoreInFlight = false
    }
}

/// Identifiable alert payload for `.alert(item:)`. Lives at file scope so
/// the View struct stays a pure value type that SwiftUI can re-evaluate
/// freely.
private struct PurchaseAlert: Identifiable {
    let id = UUID()
    let title: String
    let message: String?
}
