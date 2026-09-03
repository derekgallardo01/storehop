package com.storehop.app.analytics

/**
 * App analytics facade. Fans out to Firebase Analytics (GA4) and PostHog (EU
 * cloud), gated on the user's consent pref ([com.storehop.app.data.prefs.UserPreferencesRepository.analyticsEnabled]).
 *
 * **Content-safety by design:** every method takes only counts / boolean flags /
 * opaque ids — never item names, brands, notes, or any user content. Grocery
 * item names can be sensitive (e.g. medications), so they must never leave the
 * device. Screen tracking logs the static nav route *template* only.
 *
 * All calls are no-ops when consent is off. [start] must be called once from
 * `Application.onCreate()`.
 */
interface AnalyticsService {

    /** Initialize the sinks (PostHog setup) and begin observing consent + user id. */
    fun start()

    /** A screen was shown. Pass the static nav route template only (e.g.
     *  `"shop/store/{storeId}"`), never the substituted id. */
    fun screenView(route: String)

    // --- Funnel events (action-only) ---

    fun itemAdded(hasBrand: Boolean, storeCount: Int, isStaple: Boolean, isPriority: Boolean, isBuyToday: Boolean)
    fun itemQuickAdded(existing: Boolean)
    /** +/- on the master Items list (needed=true is "+"). */
    fun itemNeededAllStores(needed: Boolean)
    fun itemsBulkTagged(itemCount: Int, storeCount: Int)
    /** Checked an item off while shopping a store. */
    fun itemPurchased()
    fun itemPurchaseUndone()
    fun listShared(itemCount: Int, sectionCount: Int)
    fun signIn()
    fun signOut()
    fun premiumPurchaseStarted()
    fun premiumPurchased()
    fun premiumRestored()
    fun storeAdded(isOneOff: Boolean)
    fun categoryAdded()
}
