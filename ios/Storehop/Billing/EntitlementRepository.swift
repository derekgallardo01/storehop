import Foundation
import FirebaseAuth

/// Single source of truth for the user's [Entitlement] on this device.
///
/// Combines three inputs:
///   1. **StoreKit purchase state** ([StoreKitManager.hasPremiumPurchaseStream])
///      — `Transaction.currentEntitlements` says the user owns
///      `premium_lifetime` and the transaction isn't revoked.
///   2. **Grandfather flag** ([UserPreferencesRepository.legacyUserGranted])
///      — set once-per-uid by the grandfather check when either the
///      Firebase account `creationDate` predates `V0_8_RELEASE_DATE_MS`
///      OR the email is in `PREMIUM_VIP_EMAILS`.
///   3. **Cached entitlement** in UserDefaults — read at startup to
///      seed the UI fast on cold launch before StoreKit's first
///      `Transaction.currentEntitlements` scan completes.
///
/// Started once from `AppContainer.live()`. UI observes
/// [entitlementStream] reactively; gates lift the moment a purchase
/// completes (StoreKit's Transaction.updates → StoreKitManager
/// re-emits hasPremiumPurchase → combine() recomputes → entitlement
/// flips to `.premium`).
///
/// Per Apple / Google IAP policy this state is **per-platform and
/// device-local** — no cloud sync, ever. The Mike + Amanda household
/// case is handled by the inviter-pays UI gate (only Generate-Invite
/// is gated; accepting + using a shared household is unconditionally
/// free).
///
/// Mirrors Android `EntitlementRepository` line-for-line.
final class EntitlementRepository: @unchecked Sendable {
    private let storeKit: StoreKitManager
    private let session: any UserSessionProvider
    private let userPrefs: any UserPreferencesRepository

    private let lock = NSLock()
    private var _entitlement: Entitlement = .notEntitled
    private var entitlementContinuations: [UUID: AsyncStream<Entitlement>.Continuation] = [:]

    private var combineTask: Task<Void, Never>?
    private var grandfatherTask: Task<Void, Never>?

    init(
        storeKit: StoreKitManager,
        session: any UserSessionProvider,
        userPrefs: any UserPreferencesRepository
    ) {
        self.storeKit = storeKit
        self.session = session
        self.userPrefs = userPrefs
    }

    /// Idempotent — safe to call from app start. Launches two
    /// long-lived tasks:
    ///   1. Combine StoreKit purchase state + legacy flag → publish
    ///      entitlement on every change; also write the cached value
    ///      to UserDefaults so the next cold launch starts in the
    ///      right state.
    ///   2. Watch uid changes; run the grandfather check at most once
    ///      per uid (a separate UserDefaults flag tracks who's been
    ///      checked).
    func start() {
        // Seed from cache first so the first frame doesn't flicker
        // through .notEntitled while StoreKit's scan settles.
        let cached = userPrefs.cachedEntitlement
        _entitlement = Entitlement(cacheString: cached)

        combineTask?.cancel()
        combineTask = Task { [weak self] in
            guard let self else { return }
            await self.runCombineLoop()
        }

        grandfatherTask?.cancel()
        grandfatherTask = Task { [weak self] in
            guard let self else { return }
            for await uid in self.session.userIdStream {
                if let uid {
                    await self.runGrandfatherCheckIfNeeded(uid: uid)
                }
            }
        }
    }

    func stop() {
        combineTask?.cancel()
        combineTask = nil
        grandfatherTask?.cancel()
        grandfatherTask = nil
    }

    // MARK: - Public state

    var entitlement: Entitlement {
        lock.lock()
        defer { lock.unlock() }
        return _entitlement
    }

    /// Stream of entitlement changes for UI gates. Emits the current
    /// value immediately on subscription, then again whenever it
    /// flips.
    var entitlementStream: AsyncStream<Entitlement> {
        AsyncStream { continuation in
            let id = UUID()
            self.lock.lock()
            self.entitlementContinuations[id] = continuation
            let initial = self._entitlement
            self.lock.unlock()
            continuation.yield(initial)
            continuation.onTermination = { @Sendable _ in
                self.lock.lock()
                self.entitlementContinuations[id] = nil
                self.lock.unlock()
            }
        }
    }

    /// User-initiated re-query of StoreKit purchases. Wired to the
    /// "Restore purchases" link on the upsell card.
    func restorePurchases() async {
        await storeKit.restorePurchases()
    }

    // MARK: - Internals

    /// Joins StoreKit's hasPremiumPurchase signal with the
    /// legacyUserGranted UserDefaults bool and publishes the
    /// resulting Entitlement.
    private func runCombineLoop() async {
        let purchaseStream = storeKit.hasPremiumPurchaseStream

        // We need to react to BOTH inputs. UserDefaults doesn't have a
        // first-class change stream for arbitrary keys, but
        // UserPreferencesRepository exposes legacy_user_granted via a
        // KVO-style snapshot — except the entitlement keys aren't part
        // of the snapshot (they're explicitly excluded from cloud sync).
        // Simplest: poll the legacy flag on every purchase emission +
        // re-read on every grandfather-check completion.

        for await hasPurchase in purchaseStream {
            recompute(hasPurchase: hasPurchase)
        }
    }

    /// Forces a recompute after the grandfather task writes the legacy
    /// flag. Called from runGrandfatherCheckIfNeeded.
    private func recomputeWithCurrentPurchase() async {
        // We don't have the latest hasPremiumPurchase value cached in
        // this class; pull it off the manager directly.
        // StoreKitManager surfaces the latest via the .first emission
        // of its stream — cheap since AsyncStream replays the current
        // value on subscribe.
        for await hasPurchase in storeKit.hasPremiumPurchaseStream {
            recompute(hasPurchase: hasPurchase)
            return // take first
        }
    }

    private func recompute(hasPurchase: Bool) {
        let isLegacy = userPrefs.legacyUserGranted
        let new: Entitlement = {
            // Purchase precedence: a verified StoreKit purchase wins
            // over the legacy flag for clarity. Both grant unlocked
            // status, but reporting `.premium` when there IS a purchase
            // matches Apple's authoritative signal.
            if hasPurchase { return .premium }
            if isLegacy { return .legacyUser }
            return .notEntitled
        }()

        lock.lock()
        let changed = _entitlement != new
        _entitlement = new
        let conts = Array(entitlementContinuations.values)
        lock.unlock()

        if changed {
            userPrefs.setCachedEntitlement(new.cacheString)
            for c in conts { c.yield(new) }
        }
    }

    private func runGrandfatherCheckIfNeeded(uid: String) async {
        if userPrefs.legacyCheckDoneForUid == uid {
            return // already checked this uid.
        }
        let currentUser = Auth.auth().currentUser
        let email = currentUser?.email?.lowercased()
        let creationDate = currentUser?.metadata.creationDate

        // Recompute legacy_user from scratch (clears if previous uid
        // was VIP and this one isn't — fixes the sticky-flag bug from
        // the v0.8 Android follow-up).
        let isVip = email.map { Self.premiumVipEmails.contains($0) } ?? false
        let isPreV08: Bool = {
            guard let creationDate else { return false }
            let creationMs = Int64(creationDate.timeIntervalSince1970 * 1000)
            return creationMs < Self.v08ReleaseDateMs
        }()
        let shouldGrant = isVip || isPreV08

        userPrefs.setLegacyUserGranted(shouldGrant)
        userPrefs.setLegacyCheckDoneForUid(uid)

        // Trigger a recompute so the UI flips immediately rather than
        // waiting for the next StoreKit emission.
        await recomputeWithCurrentPurchase()
    }

    // MARK: - Constants

    /// Cutoff for the date-based grandfather check: any Firebase
    /// account created **before** this epoch-ms timestamp is treated
    /// as a legacy user. Should match Android's
    /// `EntitlementRepository.V0_8_RELEASE_DATE_MS` so the two
    /// platforms grandfather the same cohort.
    static let v08ReleaseDateMs: Int64 = 1_747_000_000_000  // ~2026-05-12 00:00 UTC

    /// Explicit VIP allowlist — these accounts get free Premium
    /// regardless of when their Firebase account was created. The
    /// holders below all participated in beta testing and were
    /// promised free access. Lower-cased for case-insensitive match
    /// against Firebase's email.
    ///
    /// **Privacy note**: same as Android — committed to a public repo
    /// → indexable email addresses. Move to a BuildConfig-style
    /// injection from a gitignored file if the list grows.
    static let premiumVipEmails: Set<String> = [
        "derekgallardo01@gmail.com", // dev account
        "mikehaynes@gmail.com",      // Mike — beta tester since v0.3.x
        "amandafrost79@gmail.com",   // Amanda — Mike's household partner
    ]
}
