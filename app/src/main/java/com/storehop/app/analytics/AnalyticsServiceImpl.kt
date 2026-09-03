package com.storehop.app.analytics

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.posthog.PostHog
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import com.storehop.app.BuildConfig
import com.storehop.app.data.prefs.UserPreferencesRepository
import com.storehop.app.data.util.UserSessionProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fans out to Firebase Analytics + PostHog, gated on consent. See
 * [AnalyticsService]. All logging is guarded by [enabled]; identity and
 * collection state are kept in sync with the consent pref and the signed-in
 * uid via two long-lived collectors started in [start].
 */
@Singleton
class AnalyticsServiceImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val firebase: FirebaseAnalytics,
    private val userPrefs: UserPreferencesRepository,
    private val session: UserSessionProvider,
) : AnalyticsService {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Cached consent — mirrors the pref so per-event logging is a cheap read. */
    @Volatile private var enabled: Boolean = true

    /** True once PostHog has been set up with a non-blank key. */
    @Volatile private var posthogReady: Boolean = false

    override fun start() {
        // PostHog is optional: only set it up when a key is configured
        // (posthog.properties). Empty key => PostHog stays off, Firebase runs.
        val key = BuildConfig.POSTHOG_API_KEY
        if (key.isNotBlank()) {
            PostHogAndroid.setup(
                context,
                PostHogAndroidConfig(apiKey = key, host = BuildConfig.POSTHOG_HOST).apply {
                    // We track screens ourselves (route templates, no ids), so
                    // let PostHog not auto-capture Activity/screen churn. Session
                    // replay stays OFF — it would capture on-screen item names.
                    captureScreenViews = false
                    captureDeepLinks = false
                    captureApplicationLifecycleEvents = true
                    sessionReplay = false
                },
            )
            posthogReady = true
        }

        // Keep collection + opt-in state in sync with the consent pref.
        userPrefs.analyticsEnabled
            .onEach { applyConsent(it) }
            .launchIn(scope)

        // Set the pseudonymous analytics identity to the signed-in uid.
        session.userId
            .onEach { uid -> applyIdentity(uid) }
            .launchIn(scope)
    }

    private fun applyConsent(consented: Boolean) {
        enabled = consented
        firebase.setAnalyticsCollectionEnabled(consented)
        if (posthogReady) {
            if (consented) PostHog.optIn() else PostHog.optOut()
        }
    }

    private fun applyIdentity(uid: String?) {
        firebase.setUserId(uid)
        if (posthogReady) {
            if (uid != null) PostHog.identify(uid) else PostHog.reset()
        }
    }

    override fun screenView(route: String) {
        if (!enabled) return
        firebase.logEvent(
            FirebaseAnalytics.Event.SCREEN_VIEW,
            Bundle().apply { putString(FirebaseAnalytics.Param.SCREEN_NAME, route) },
        )
        if (posthogReady) PostHog.screen(route)
    }

    // --- Funnel events ---

    override fun itemAdded(hasBrand: Boolean, storeCount: Int, isStaple: Boolean, isPriority: Boolean, isBuyToday: Boolean) =
        track(
            "item_add",
            mapOf(
                "has_brand" to hasBrand,
                "store_count" to storeCount,
                "is_staple" to isStaple,
                "is_priority" to isPriority,
                "is_buy_today" to isBuyToday,
            ),
        )

    override fun itemQuickAdded(existing: Boolean) =
        track("item_quick_add", mapOf("existing" to existing))

    override fun itemNeededAllStores(needed: Boolean) =
        track("item_needed_all", mapOf("needed" to needed))

    override fun itemsBulkTagged(itemCount: Int, storeCount: Int) =
        track("item_bulk_tag", mapOf("item_count" to itemCount, "store_count" to storeCount))

    override fun itemPurchased() = track("item_purchase")
    override fun itemPurchaseUndone() = track("item_purchase_undo")

    override fun listShared(itemCount: Int, sectionCount: Int) =
        track("list_share", mapOf("item_count" to itemCount, "section_count" to sectionCount))

    override fun signIn() = track("sign_in_google")
    override fun signOut() = track("sign_out")
    override fun premiumPurchaseStarted() = track("premium_purchase_start")
    override fun premiumPurchased() = track("premium_purchase")
    override fun premiumRestored() = track("premium_restore")
    override fun storeAdded(isOneOff: Boolean) = track("store_add", mapOf("is_one_off" to isOneOff))
    override fun categoryAdded() = track("category_add")

    /**
     * Single fan-out. Firebase params must be String/Long/Double, so booleans
     * are logged as 0/1 and ints as Long; PostHog takes the raw map.
     */
    private fun track(event: String, props: Map<String, Any> = emptyMap()) {
        if (!enabled) return
        firebase.logEvent(event, props.toFirebaseBundle())
        if (posthogReady) PostHog.capture(event = event, properties = props)
    }

    private fun Map<String, Any>.toFirebaseBundle(): Bundle = Bundle().apply {
        for ((k, v) in this@toFirebaseBundle) {
            when (v) {
                is Boolean -> putLong(k, if (v) 1L else 0L)
                is Int -> putLong(k, v.toLong())
                is Long -> putLong(k, v)
                is Double -> putDouble(k, v)
                is Float -> putDouble(k, v.toDouble())
                else -> putString(k, v.toString())
            }
        }
    }
}
