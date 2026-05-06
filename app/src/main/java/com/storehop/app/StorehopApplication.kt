package com.storehop.app

import android.app.Application
import com.storehop.app.auth.SignInBootstrapper
import com.storehop.app.sync.SyncEngine
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class StorehopApplication : Application() {

    @Inject lateinit var signInBootstrapper: SignInBootstrapper
    @Inject lateinit var syncEngine: SyncEngine

    override fun onCreate() {
        super.onCreate()
        // Triggers anonymous sign-in via the FirebaseAuthSessionProvider DI graph
        // (constructing it has the side effect of `signInAnonymously()`), then
        // claims any pre-Firebase `local-only` rows under the new uid the first
        // time we see one.
        signInBootstrapper.start()
        // Watches Room for pending-sync rows and pushes them to Firestore.
        // Cancels and restarts per-uid on sign-in/sign-out.
        syncEngine.start()
    }
}
