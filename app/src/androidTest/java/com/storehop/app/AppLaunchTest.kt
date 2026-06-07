package com.storehop.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke E2E: MainActivity launches under the Hilt test graph (in-memory
 * DB, mocked Firebase, LocalOnlyUserSessionProvider) and the StorePicker
 * empty state is reachable. Fastest possible test that proves the
 * androidTest infrastructure is wired correctly.
 *
 * If THIS fails, every other E2E test will fail too -- treat it as the
 * canary.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AppLaunchTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<MainActivity>()

    @Before fun setUp() { hiltRule.inject() }

    @Test fun mainActivityLaunchesAndShowsShopTab() {
        // The bottom-nav "Shop" tab label is always present.
        composeRule.onNodeWithText("Shop").assertIsDisplayed()
        // ...and the "Items" tab label too.
        composeRule.onNodeWithText("Items").assertIsDisplayed()
    }
}
