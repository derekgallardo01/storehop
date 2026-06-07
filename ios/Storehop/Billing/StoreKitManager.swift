import Foundation
import StoreKit

/// StoreKit2 wrapper for the v0.8 `premium_lifetime` in-app purchase.
/// Mirrors Android's `BillingManager` (Play Billing Library 7.1.1).
///
/// Started once from `AppContainer.live()`; the rest of the app
/// observes [product] for the upsell price and [entitlement] for the
/// purchase-derived gate state, and triggers purchases through
/// [purchase].
///
/// ## Why StoreKit2 (not StoreKit1)
///
/// StoreKit2's `Transaction.currentEntitlements` AsyncSequence is the
/// modern source of truth for "what does this user currently own."
/// `Transaction.updates` streams live purchase events the moment Apple
/// notarizes them — same shape as Play Billing's
/// `PurchasesUpdatedListener` callback but Swift-native and free of
/// callback bridging.
///
/// ## Lifecycle
///
/// `start()` kicks off two long-lived Tasks:
///  1. `Transaction.updates` listener — re-emits the latest
///     entitlement (and finishes transactions to acknowledge them).
///  2. Initial `Transaction.currentEntitlements` scan — populates the
///     entitlement state on cold launch in case there were transitions
///     the app missed while uninstalled.
///
/// Apple doesn't require a separate "acknowledge" step like Google;
/// calling `Transaction.finish()` is the equivalent — it just removes
/// the transaction from the unfinished queue.
final class StoreKitManager: @unchecked Sendable {
    /// Product ID for the Premium IAP. Must match the App Store Connect
    /// product setup.
    ///
    /// **Build 53 (2026-05-26):** The original `premium_lifetime` ID was
    /// accidentally deleted from App Store Connect on the iOS side while
    /// triaging the build-51 rejection. Apple permanently reserves
    /// deleted product IDs, so the same ID cannot be recreated. We use
    /// `premium_lifetime_v2` for new purchases on iOS only. The Android
    /// app keeps `premium_lifetime` unchanged — entitlements are
    /// per-platform, so this divergence doesn't affect cross-platform
    /// state.
    static let productIdPremium = "premium_lifetime_v2"

    /// Legacy product ID for entitlement-check ONLY. Customers who
    /// already purchased before the deletion have StoreKit transactions
    /// recorded under `premium_lifetime`; those transactions still
    /// exist on Apple's side even though the product is gone from App
    /// Store Connect. Including this ID in the entitlement scan keeps
    /// existing purchasers entitled while routing new purchases through
    /// the new product. Never used as a target for `Product.products(for:)`.
    static let productIdPremiumLegacy = "premium_lifetime"

    /// Set of product IDs that grant Premium entitlement. New purchases
    /// always land on `productIdPremium`; the legacy ID is here to
    /// preserve entitlements from before the v2 cutover.
    static var entitlementGrantingProductIds: Set<String> {
        [productIdPremium, productIdPremiumLegacy]
    }

    private let lock = NSLock()
    private var _product: Product?
    private var productContinuations: [UUID: AsyncStream<Product?>.Continuation] = [:]

    /// Whether the user owns the Premium product right now per
    /// StoreKit. Bridged from `Transaction.currentEntitlements`. Not
    /// the *combined* entitlement (legacy_user etc. live in
    /// EntitlementRepository); this is the StoreKit-only signal.
    private var _hasPremiumPurchase: Bool = false
    private var purchaseContinuations: [UUID: AsyncStream<Bool>.Continuation] = [:]

    private var transactionListenerTask: Task<Void, Never>?

    /// Boot the manager. Idempotent.
    func start() async {
        if transactionListenerTask != nil { return }

        // Initial scan — propagates entitlements from any prior install.
        await refreshFromCurrentEntitlements()

        // Live updates from StoreKit.
        transactionListenerTask = Task.detached { [weak self] in
            for await result in Transaction.updates {
                guard let self else { return }
                await self.handle(transactionResult: result)
            }
        }

        // Product lookup.
        await refreshProduct()
    }

    /// Tear down. Tests / preview paths use this so the long-lived
    /// transaction listener doesn't leak.
    func stop() {
        transactionListenerTask?.cancel()
        transactionListenerTask = nil
    }

    // MARK: - Public API

    /// Cached Premium product details. Null until the first
    /// `Product.products(for:)` call returns. UI reads
    /// `product.displayPrice` to render the App Store-localized price
    /// (e.g. "$7.99", "€7.49") on the upsell button.
    var product: Product? {
        lock.lock()
        defer { lock.unlock() }
        return _product
    }

    /// Stream of product updates. Emits the current value on
    /// subscription, then again whenever it changes (typically once
    /// — when the initial query lands).
    var productStream: AsyncStream<Product?> {
        AsyncStream { continuation in
            let id = UUID()
            self.lock.lock()
            self.productContinuations[id] = continuation
            let initial = self._product
            self.lock.unlock()
            continuation.yield(initial)
            continuation.onTermination = { @Sendable _ in
                self.lock.lock()
                self.productContinuations[id] = nil
                self.lock.unlock()
            }
        }
    }

    /// Snapshot of "user owns Premium per StoreKit" — for callers
    /// that need the current state without subscribing to the stream.
    var hasPremiumPurchase: Bool {
        lock.lock()
        defer { lock.unlock() }
        return _hasPremiumPurchase
    }

    /// Stream of "user owns Premium per StoreKit" bools.
    /// [EntitlementRepository] subscribes to this and combines with
    /// the grandfather flag to produce the user-visible
    /// [Entitlement].
    var hasPremiumPurchaseStream: AsyncStream<Bool> {
        AsyncStream { continuation in
            let id = UUID()
            self.lock.lock()
            self.purchaseContinuations[id] = continuation
            let initial = self._hasPremiumPurchase
            self.lock.unlock()
            continuation.yield(initial)
            continuation.onTermination = { @Sendable _ in
                self.lock.lock()
                self.purchaseContinuations[id] = nil
                self.lock.unlock()
            }
        }
    }

    /// Launch the App Store purchase sheet. Returns the outcome so the
    /// UI can show a snackbar / alert.
    @MainActor
    func purchase() async -> PurchaseOutcome {
        guard let product = self.product else {
            return .failed(reason: "Product not loaded yet — try again in a moment.")
        }
        do {
            let result = try await product.purchase()
            switch result {
            case .success(let verification):
                if let transaction = try? await Self.verified(verification) {
                    await transaction.finish()
                    await refreshFromCurrentEntitlements()
                    return .purchased
                } else {
                    return .failed(reason: "Apple couldn't verify the purchase. Try again.")
                }
            case .userCancelled:
                return .userCancelled
            case .pending:
                return .pending
            @unknown default:
                return .failed(reason: "Unknown purchase result.")
            }
        } catch {
            return .failed(reason: error.localizedDescription)
        }
    }

    /// Re-query StoreKit for past purchases. Wired to the "Restore
    /// purchases" link in Settings → upgrade card. Apple silently
    /// covers cross-device same-Apple-ID purchases via iCloud receipts,
    /// so the user-facing button is mostly a courtesy.
    func restorePurchases() async {
        // `AppStore.sync()` forces StoreKit to refresh the receipt
        // from Apple. Then re-scan current entitlements.
        do {
            try await AppStore.sync()
        } catch {
            // Network errors swallowed — next refresh on launch retries.
        }
        await refreshFromCurrentEntitlements()
    }

    // MARK: - Internals

    private func refreshProduct() async {
        do {
            let products = try await Product.products(for: [Self.productIdPremium])
            let cached = products.first
            lock.lock()
            _product = cached
            let conts = Array(productContinuations.values)
            lock.unlock()
            for c in conts { c.yield(cached) }
        } catch {
            // Product lookup failed (no Internet, no App Store Connect
            // record yet, etc.). UI shows "Unlock" without the price.
        }
    }

    private func refreshFromCurrentEntitlements() async {
        var owns = false
        for await result in Transaction.currentEntitlements {
            if let transaction = try? await Self.verified(result),
               Self.entitlementGrantingProductIds.contains(transaction.productID),
               transaction.revocationDate == nil {
                // Either the live v2 product OR the legacy
                // `premium_lifetime` transaction keeps the user
                // entitled. See `productIdPremiumLegacy` for the
                // build-53 deletion context.
                owns = true
            }
        }
        publish(hasPurchase: owns)
    }

    private func handle(transactionResult: VerificationResult<Transaction>) async {
        guard let transaction = try? await Self.verified(transactionResult) else { return }
        // Acknowledge by finishing the transaction. StoreKit2's
        // equivalent of Play Billing's acknowledgePurchase.
        await transaction.finish()
        if Self.entitlementGrantingProductIds.contains(transaction.productID) {
            publish(hasPurchase: transaction.revocationDate == nil)
        }
    }

    private func publish(hasPurchase: Bool) {
        lock.lock()
        let changed = _hasPremiumPurchase != hasPurchase
        _hasPremiumPurchase = hasPurchase
        let conts = Array(purchaseContinuations.values)
        lock.unlock()
        if changed {
            for c in conts { c.yield(hasPurchase) }
        }
    }

    private static func verified<T>(_ result: VerificationResult<T>) async throws -> T {
        switch result {
        case .verified(let value):
            return value
        case .unverified(_, let error):
            throw error
        }
    }
}

/// One-shot purchase outcomes for UI snackbar / alert. Mirrors
/// Android's `PurchaseEvent` sealed class.
enum PurchaseOutcome: Sendable, Equatable {
    case purchased
    case pending
    case userCancelled
    case failed(reason: String)
}
