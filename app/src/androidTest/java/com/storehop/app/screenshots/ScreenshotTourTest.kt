package com.storehop.app.screenshots

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.storehop.app.MainActivity
import com.storehop.app.data.demo.DemoDataSeeder
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.inject.Inject

/**
 * Marketing screenshot tour. Seeds the curated [DemoDataSeeder] dataset into the
 * offline test graph (no Firebase, deterministic data), then walks the app and
 * captures a full-screen PNG of each screen into the app's external files dir:
 *
 *   /sdcard/Android/data/com.storehop.app/files/screenshots/NN_<screen>.png
 *
 * `scripts/capture-android-screenshots.ps1` sets the emulator light/dark and
 * device, runs this class, then pulls the PNGs into the right output folder —
 * so this test stays theme/device-agnostic and just emits `NN_<name>.png`.
 *
 * Best-effort screens are wrapped so one flaky navigation can't sink the tour.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ScreenshotTourTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var seeder: DemoDataSeeder

    private var counter = 1
    private val outDir: File by lazy {
        File(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "screenshots",
        ).apply { mkdirs() }
    }

    @Before fun setUp() {
        hiltRule.inject()
        // Fresh output each run.
        outDir.listFiles()?.forEach { it.delete() }
        runBlocking { seeder.seed() }
    }

    @Test fun captureAllScreens() {
        // Wait for the seeded stores to render on the Shop tab before shooting.
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Continente").fetchSemanticsNodes().isNotEmpty()
        }

        // 1) Shop / Store Picker (Buy Today + Critical banners visible).
        capture("shop")

        // 2) Shop-at-Store.
        composeRule.onNodeWithText("Continente").performClick()
        settle()
        capture("shop_at_store")
        Espresso.pressBack()
        settle()

        // 3) Items list.
        composeRule.onNodeWithText("Items").performClick()
        settle()
        capture("items")

        // 4) Item form — prefer a populated Edit form; fall back to Add.
        val editShot = runCatching {
            composeRule.onNodeWithText("Whole Milk").performScrollTo().performClick()
            settle()
            capture("item_form")
            Espresso.pressBack()
            settle()
        }.isSuccess
        if (!editShot) {
            composeRule.onNodeWithContentDescription("Add item").performClick()
            settle()
            capture("item_form")
            Espresso.pressBack()
            settle()
        }

        // 5) Manage categories (best-effort): Items overflow → Manage categories.
        runCatching {
            composeRule.onNodeWithContentDescription("More options").performClick()
            settle()
            composeRule.onNodeWithText("Manage categories").performClick()
            settle()
            capture("manage_categories")
            Espresso.pressBack()
            settle()
        }

        // 6) Settings — gear from the Items tab.
        composeRule.onNodeWithContentDescription("Settings").performClick()
        settle()
        capture("settings")

        // 7) Statistics (best-effort) — the featured card at the top of Settings.
        runCatching {
            composeRule.onNodeWithText("Statistics").performClick()
            settle()
            capture("statistics")
            Espresso.pressBack()
            settle()
        }

        // 8) Household (best-effort) — link card in Settings.
        runCatching {
            composeRule.onNodeWithText("Household").performScrollTo().performClick()
            settle()
            capture("household")
            Espresso.pressBack()
            settle()
        }
        Espresso.pressBack() // leave Settings, back to Items.
        settle()

        // 9) Edit Aisle Order (best-effort): Shop → first store's overflow → Edit aisles.
        runCatching {
            composeRule.onNodeWithText("Shop").performClick()
            settle()
            composeRule.onAllNodesWithContentDescription("Store options")[0].performClick()
            settle()
            composeRule.onNodeWithText("Edit aisles").performClick()
            settle()
            capture("edit_aisle_order")
            Espresso.pressBack()
            settle()
        }

        // Sanity: we produced at least the core screens.
        composeRule.onNodeWithText("Shop").assertIsDisplayed()
    }

    /** Let animations, Room flows, and image loads settle before capturing. */
    private fun settle() {
        composeRule.waitForIdle()
        Thread.sleep(700)
    }

    private fun capture(name: String) {
        composeRule.waitForIdle()
        Thread.sleep(300)
        val bmp: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val file = File(outDir, "%02d_%s.png".format(counter++, name))
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }
}
