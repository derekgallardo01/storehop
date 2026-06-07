import Foundation

/// The current user's Premium entitlement state on this device.
///
/// v0.8 introduced this as the single source of truth for the "can the
/// user generate an invite code / export CSV?" gate.
///
///  - `.notEntitled` — default. Free tier: master list, multi-store
///    tagging, cross-store cascade, sync, Statistics, CSV import, four
///    languages. **Cannot** generate household invite codes or export
///    CSV.
///  - `.premium` — bought via StoreKit2. Verified against
///    `Transaction.currentEntitlements` on every app launch.
///  - `.legacyUser` — grandfathered. Firebase account
///    `creationDate` predates the v0.8 release OR the email is in the
///    explicit `PREMIUM_VIP_EMAILS` allowlist. Functionally identical
///    to `.premium` from the UI's perspective.
///
/// Per Apple / Google IAP policy, entitlement state is **per-platform
/// and device-local** — no cloud sync. The inviter-pays model (gate
/// only the Generate Invite button; accepting + using a shared
/// household is unconditionally free) is what makes the Mike + Amanda
/// case work without forcing both users to pay independently.
///
/// Mirrors Android `Entitlement` sealed class 1:1.
enum Entitlement: Equatable, Sendable {
    case notEntitled
    case premium
    case legacyUser
}

extension Entitlement {
    /// Convenience for UI gating: `true` for `.premium` and `.legacyUser`,
    /// `false` for `.notEntitled`. Use this everywhere the UI just
    /// needs "is the gate open or closed?" without caring how the
    /// entitlement was obtained.
    var isUnlocked: Bool {
        switch self {
        case .notEntitled: return false
        case .premium, .legacyUser: return true
        }
    }

    /// Stable cache string for persistence in UserDefaults. Decodes
    /// back via `Entitlement(cacheString:)`.
    var cacheString: String {
        switch self {
        case .notEntitled: return "NOT_ENTITLED"
        case .premium: return "PREMIUM"
        case .legacyUser: return "LEGACY_USER"
        }
    }

    init(cacheString: String) {
        switch cacheString {
        case "PREMIUM": self = .premium
        case "LEGACY_USER": self = .legacyUser
        default: self = .notEntitled
        }
    }
}
