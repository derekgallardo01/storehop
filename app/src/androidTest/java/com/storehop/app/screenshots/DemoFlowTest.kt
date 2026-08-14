package com.storehop.app.screenshots

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.storehop.app.MainActivity
import com.storehop.app.data.demo.DemoDataSeeder
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * The guided ~30–45s walkthrough for the landing-page video. Seeds the curated
 * demo dataset (offline test graph), then drives one deliberately-paced story so
 * a concurrent `adb shell screenrecord` (started by
 * `scripts/record-android-video.ps1`) films smooth motion:
 *
 *   Shop overview (Buy Today / Critical banners) → open a store → check items
 *   off → Items library → open an item → Settings → Statistics.
 *
 * Fragile steps are wrapped so a single miss doesn't abort the recording.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DemoFlowTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var seeder: DemoDataSeeder

    @Before fun setUp() {
        hiltRule.inject()
        runBlocking { seeder.seed() }
    }

    @Test fun walkthrough() {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Continente").fetchSemanticsNodes().isNotEmpty()
        }
        beat(2500) // linger on the Shop overview + banners

        // Reveal the Buy Today breakdown (best-effort).
        runCatching {
            composeRule.onNodeWithText("Toilet Paper").assertExists()
        }

        // Open a store and shop it.
        composeRule.onNodeWithText("Continente").performClick()
        beat(2500)

        // Check a couple of items off (best-effort taps on the rows).
        runCatching { composeRule.onNodeWithText("Bananas").performClick(); beat(1300) }
        runCatching { composeRule.onNodeWithText("Sourdough Bread").performClick(); beat(1600) }
        beat(800)
        Espresso.pressBack()
        beat(1600)

        // Master Items library.
        composeRule.onNodeWithText("Items").performClick()
        beat(2200)

        // Open an item to show the edit form.
        runCatching {
            composeRule.onNodeWithText("Coffee").performScrollTo().performClick()
            beat(2400)
            Espresso.pressBack()
            beat(1400)
        }

        // Back to Shop — the Buy Today banner is front and centre.
        composeRule.onNodeWithText("Shop").performClick()
        beat(2400)

        // Settings → Statistics finish.
        composeRule.onNodeWithContentDescription("Settings").performClick()
        beat(1800)
        runCatching {
            composeRule.onNodeWithText("Statistics").performClick()
            beat(3200) // hold on the stats to end the clip
        }
    }

    /** A deliberate pause so the recorded motion reads as a guided demo. */
    private fun beat(ms: Long) {
        composeRule.waitForIdle()
        Thread.sleep(ms)
    }
}
