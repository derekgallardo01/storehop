package com.storehop.app

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Replaces [StorehopApplication] (which is `@HiltAndroidApp` and eagerly
 * boots Firebase + sync on startup) with [HiltTestApplication] for
 * instrumented tests. Required so `@HiltAndroidTest` can build its own
 * test-graph without hitting the real FirebaseAuth getInstance() during
 * Application.onCreate.
 */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
