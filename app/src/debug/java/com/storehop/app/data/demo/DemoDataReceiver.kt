package com.storehop.app.data.demo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * DEBUG-only receiver so the demo dataset can be loaded/cleared from a script
 * without touching the UI — handy for seeding a real device build before a
 * screen recording:
 *
 * ```
 * adb shell am broadcast -a com.storehop.app.action.LOAD_DEMO_DATA  -p com.storehop.app
 * adb shell am broadcast -a com.storehop.app.action.CLEAR_DEMO_DATA -p com.storehop.app
 * ```
 *
 * Lives in `src/debug`, registered only by `src/debug/AndroidManifest.xml`, so
 * it is absent from the release build. (The instrumented screenshot/flow tests
 * don't use this — they inject [DemoDataSeeder] directly.)
 */
@AndroidEntryPoint
class DemoDataReceiver : BroadcastReceiver() {

    @Inject lateinit var seeder: DemoDataSeeder

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val load = intent.action == ACTION_LOAD
        val pending = goAsync()
        scope.launch {
            try {
                if (load) seeder.seed() else seeder.clear()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_LOAD = "com.storehop.app.action.LOAD_DEMO_DATA"
        const val ACTION_CLEAR = "com.storehop.app.action.CLEAR_DEMO_DATA"
    }
}
