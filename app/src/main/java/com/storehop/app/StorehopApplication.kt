package com.storehop.app

import android.app.Application
import com.storehop.app.billing.BillingManager
import com.storehop.app.billing.EntitlementRepository
import com.storehop.app.data.util.UserSessionProvider
import com.storehop.app.sync.SyncEngine
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class StorehopApplication : Application() {

    /**
     * Eagerly injected so [com.storehop.app.auth.FirebaseAuthSessionProvider]
     * is constructed on app start. Its init block hooks the FirebaseAuth
     * listener, kicks off anonymous sign-in if needed, and starts the gating
     * coroutine that runs the local-only / orphan-uid claim migrations
     * before publishing each new uid to the rest of the app.
     */
    @Inject lateinit var sessionProvider: UserSessionProvider
    @Inject lateinit var syncEngine: SyncEngine

    /**
     * v0.8: Play Billing wiring + entitlement state. BillingManager opens
     * the Play connection on launch; EntitlementRepository combines the
     * Play purchase state with the grandfather flag to publish a single
     * [com.storehop.app.billing.Entitlement] flow the UI gates observe.
     */
    @Inject lateinit var billingManager: BillingManager
    @Inject lateinit var entitlementRepository: EntitlementRepository

    override fun onCreate() {
        super.onCreate()
        // Touch the lateinit so Hilt resolves it now -- otherwise the session
        // provider stays unconstructed until the first Compose screen reads it,
        // and we'd miss the sign-in side effect that needs to run on launch.
        sessionProvider.userId
        // Watches Room for pending-sync rows and pushes them to Firestore.
        // Cancels and restarts per-uid on sign-in/sign-out.
        syncEngine.start()
        // Opens the Play Billing connection + caches product details.
        billingManager.start()
        // Combines purchases + grandfather flag → publishes Entitlement.
        entitlementRepository.start()
    }
}
