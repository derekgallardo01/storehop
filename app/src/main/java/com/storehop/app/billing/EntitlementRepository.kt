package com.storehop.app.billing

import android.util.Log
import com.android.billingclient.api.Purchase
import com.google.firebase.auth.FirebaseAuth
import com.storehop.app.data.prefs.UserPreferencesRepository
import com.storehop.app.data.util.UserSessionProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the user's [Entitlement] on this device.
 *
 * Combines three inputs:
 *   1. **Play Billing purchases** ([BillingManager.purchases]) — a
 *      PURCHASED+acknowledged `premium_lifetime` row means Premium.
 *   2. **Grandfather flag** ([UserPreferencesRepository.legacyUserGranted])
 *      — written once-per-uid when the Firebase account creation
 *      timestamp predates the v0.8 release date. Preserves goodwill for
 *      the closed-test cohort that's been beta-testing for free.
 *   3. **Cached entitlement** in DataStore — read at start() to seed the
 *      UI fast on cold launch, before BillingClient finishes connecting
 *      (~500 ms). Updated on every change.
 *
 * Started once from [com.storehop.app.StorehopApplication.onCreate]. The
 * UI observes [entitlement] reactively; gates lift the moment a purchase
 * completes (BillingManager's PurchasesUpdatedListener fires →
 * [BillingManager.purchases] re-emits → [combine] recomputes → entitlement
 * flips to Premium).
 *
 * Per Apple / Google IAP policy this state is **per-platform and
 * device-local** — no cloud sync, ever. The Mike + Amanda household case
 * is handled by the inviter-pays UI gate (only Generate-Invite is
 * gated; accepting + using a shared household is unconditionally free).
 */
@Singleton
class EntitlementRepository @Inject constructor(
    private val billingManager: BillingManager,
    private val sessionProvider: UserSessionProvider,
    private val auth: FirebaseAuth,
    private val userPrefs: UserPreferencesRepository,
    private val applicationScope: CoroutineScope,
) {
    private val _entitlement = MutableStateFlow<Entitlement>(Entitlement.NotEntitled)
    val entitlement: StateFlow<Entitlement> = _entitlement.asStateFlow()

    /**
     * Idempotent — safe to call from application onCreate. Launches two
     * long-lived coroutines:
     *   1. Combines purchases + legacy flag → updates entitlement on
     *      every change; writes the cached value to DataStore so the
     *      next cold launch starts in the right state.
     *   2. Watches uid changes; runs the grandfather check at most once
     *      per uid (a separate DataStore flag tracks who's been checked).
     */
    fun start() {
        applicationScope.launch {
            // Seed UI fast from the cached value before Billing connects.
            _entitlement.value = readCachedEntitlement()

            combine(
                billingManager.purchases,
                userPrefs.legacyUserGranted,
            ) { purchases, isLegacy ->
                computeEntitlement(purchases, isLegacy)
            }.collect { ent ->
                if (_entitlement.value != ent) {
                    Log.i(TAG, "Entitlement transition: ${_entitlement.value} -> $ent")
                    _entitlement.value = ent
                    userPrefs.setCachedEntitlement(ent.toCacheString())
                }
            }
        }

        applicationScope.launch {
            sessionProvider.userId.collect { uid ->
                if (uid != null) {
                    runGrandfatherCheckIfNeeded(uid)
                }
            }
        }
    }

    private suspend fun readCachedEntitlement(): Entitlement {
        return when (userPrefs.cachedEntitlement.first()) {
            "PREMIUM" -> Entitlement.Premium
            "LEGACY_USER" -> Entitlement.LegacyUser
            else -> Entitlement.NotEntitled
        }
    }

    private fun Entitlement.toCacheString(): String = when (this) {
        Entitlement.Premium -> "PREMIUM"
        Entitlement.LegacyUser -> "LEGACY_USER"
        Entitlement.NotEntitled -> "NOT_ENTITLED"
    }

    private fun computeEntitlement(purchases: List<Purchase>, isLegacy: Boolean): Entitlement {
        // Legacy beats nothing-bought (so a grandfather who happens to
        // also buy still reads as Premium below — the .Premium branch
        // wins thanks to the order). Purchase precedence is the cleaner
        // contract: if the user has both, the purchase receipt is more
        // authoritative and survives a clear-data + restore.
        val ownsPremium = purchases.any { purchase ->
            purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
                purchase.products.contains(BillingManager.PRODUCT_ID_PREMIUM)
        }
        return when {
            ownsPremium -> Entitlement.Premium
            isLegacy -> Entitlement.LegacyUser
            else -> Entitlement.NotEntitled
        }
    }

    private suspend fun runGrandfatherCheckIfNeeded(uid: String) {
        if (userPrefs.legacyCheckDoneForUid.first() == uid) {
            return // already checked this uid; don't repeat.
        }
        val creationTimestamp = auth.currentUser?.metadata?.creationTimestamp
        if (creationTimestamp == null) {
            // No metadata (rare — e.g. very-fresh anon user). Mark
            // checked so we don't loop on every auth tick, and try again
            // on the next uid change.
            userPrefs.setLegacyCheckDoneForUid(uid)
            return
        }
        if (creationTimestamp < V0_8_RELEASE_DATE_MS) {
            Log.i(TAG, "Granting legacy_user entitlement to uid=$uid (account created $creationTimestamp)")
            userPrefs.setLegacyUserGranted(true)
        } else {
            Log.i(TAG, "Account too new for legacy grandfather: uid=$uid, created=$creationTimestamp")
        }
        userPrefs.setLegacyCheckDoneForUid(uid)
    }

    /**
     * User-initiated re-query of Play purchases. Wired to the
     * "Restore purchases" link in Settings → upgrade card.
     */
    fun restorePurchases() {
        billingManager.restorePurchases()
    }

    companion object {
        /**
         * Cutoff for the grandfather check: any Firebase account created
         * **before** this epoch-ms timestamp is treated as a legacy user
         * and gets free-Premium-equivalent access without paying.
         *
         * Set this to the actual v0.8 ship moment when finalising the
         * release. Until then, the current value treats every existing
         * tester as legacy.
         */
        const val V0_8_RELEASE_DATE_MS: Long = 1_747_000_000_000L  // ~2026-05-12 00:00 UTC

        private const val TAG = "EntitlementRepository"
    }
}
