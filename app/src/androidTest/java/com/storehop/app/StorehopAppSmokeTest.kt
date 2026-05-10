package com.storehop.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bootstraps the instrumented test suite. The instrumented runner
 * ([HiltTestRunner]) swaps in [dagger.hilt.android.testing.HiltTestApplication]
 * so this test can verify the Hilt + AndroidJUnit + emulator wiring without
 * the eager-init side effects of [StorehopApplication] (FirebaseAuth,
 * SyncEngine).
 *
 * Future instrumented tests can pile on by:
 *  - Annotating with `@HiltAndroidTest`.
 *  - Adding `@get:Rule val hiltRule = HiltAndroidRule(this)`.
 *  - Calling `hiltRule.inject()` in `@Before` and `@Inject lateinit var ...`
 *    for any DI deps they want.
 *
 * This file is intentionally minimal -- the goal is "the suite builds and
 * a smoke test passes," not deep integration coverage. Bigger flows (e.g.
 * exercising the Shop tab end-to-end) come post-v0.6.0 multi-user work.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class StorehopAppSmokeTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)

    @Before fun setUp() { hiltRule.inject() }

    @Test fun applicationContextHasExpectedPackage() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertThat(ctx.packageName).isEqualTo("com.storehop.app")
    }
}
