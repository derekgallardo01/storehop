package com.storehop.app.billing

/**
 * The current user's Premium entitlement state on this device.
 *
 * v0.8 introduced this as the single source of truth for the "can the
 * user generate an invite code / export CSV?" gate. Three branches:
 *
 *  - [NotEntitled] — default. Free tier: master list, multi-store
 *    tagging, cross-store cascade, sync, Statistics, CSV import, four
 *    languages. **Cannot** generate household invite codes or export
 *    CSV.
 *  - [Premium] — bought via Play Billing (Android) / StoreKit2 (iOS).
 *    Verified against the platform store on every app launch.
 *  - [LegacyUser] — grandfathered. The Firebase account's
 *    `creationTimestamp` predates the v0.8 release, so the user gets
 *    a silent free-Premium-equivalent entitlement that preserves
 *    closed-test cohort goodwill. Functionally identical to [Premium]
 *    from the UI's perspective.
 *
 * Per Apple / Google IAP policy, entitlement state is **per-platform
 * and device-local** — no cloud sync. The inviter-pays model
 * (gate only the Generate Invite button, accepting + using a shared
 * household is free) is what makes the Mike + Amanda case work without
 * requiring both users to pay.
 */
sealed class Entitlement {
    data object NotEntitled : Entitlement()
    data object Premium : Entitlement()
    data object LegacyUser : Entitlement()
}

/**
 * Convenience extension for UI gating: `true` for [Entitlement.Premium]
 * and [Entitlement.LegacyUser], `false` for [Entitlement.NotEntitled].
 * Use this everywhere the UI just needs "is the gate open or closed?"
 * without caring how the entitlement was obtained.
 */
val Entitlement.isUnlocked: Boolean
    get() = this !is Entitlement.NotEntitled
