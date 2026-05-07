package com.storehop.app

import android.app.Application
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

    override fun onCreate() {
        super.onCreate()
        // Touch the lateinit so Hilt resolves it now -- otherwise the session
        // provider stays unconstructed until the first Compose screen reads it,
        // and we'd miss the sign-in side effect that needs to run on launch.
        sessionProvider.userId
        // Watches Room for pending-sync rows and pushes them to Firestore.
        // Cancels and restarts per-uid on sign-in/sign-out.
        syncEngine.start()
    }
}
