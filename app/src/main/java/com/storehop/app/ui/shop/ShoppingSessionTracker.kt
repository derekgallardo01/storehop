package com.storehop.app.ui.shop

import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-process-scoped anchor for the "active shopping trip."
 *
 * Every Shop-at-Store ViewModel reads `sessionStartMs()` and feeds it to the
 * DAO query, so any item the user purchases after this anchor stays visible
 * struck-through at *every* store it's tagged to -- not just the store where
 * the purchase was made. This gives the cross-store sync a visible
 * confirmation (you bought milk at Lidl, walk into Continente, milk is
 * struck-through there too) instead of silently vanishing.
 *
 * The anchor is set lazily on first access, then shared by every
 * subsequent Shop-at-Store screen within the same app process. Killing
 * and relaunching the app resets it -- previously purchased items fall
 * outside the new window and the lists read clean. We deliberately do
 * not reset on background/foreground or tab switches: that would re-clean
 * the list mid-trip, which is exactly the failure mode this is fixing.
 */
@Singleton
class ShoppingSessionTracker @Inject constructor() {

    @Volatile
    private var startMs: Long? = null

    /**
     * Returns the session anchor, lazily initializing on first call. Thread-
     * safe via double-checked locking so two simultaneous Shop-at-Store
     * screens (the saved + restored Shop tab back stack) agree on the value.
     */
    fun sessionStartMs(): Long {
        startMs?.let { return it }
        return synchronized(this) {
            startMs ?: System.currentTimeMillis().also { startMs = it }
        }
    }

    /**
     * Clear the anchor so the next `sessionStartMs()` call picks a fresh
     * timestamp. Reserved for a future "End shopping trip" UI affordance --
     * unused in v0.2 but kept here so the call site is obvious when we add it.
     */
    fun reset() {
        synchronized(this) { startMs = null }
    }
}
