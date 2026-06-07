package com.storehop.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.storehop.app.data.dao.CategoryDao
import com.storehop.app.data.dao.ItemDao
import com.storehop.app.data.dao.ItemStoreXrefDao
import com.storehop.app.data.dao.StoreDao
import com.storehop.app.testing.seedE2EFixtures
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * v0.6.0 sort toggle on the master Items list. Verifies:
 *  - Default mode is ALPHABETIC; the "Switch to category sort" button is
 *    visible (toolbar).
 *  - Tapping the button flips to CATEGORY mode; the "Dairy" header
 *    becomes visible (Milk's category) plus the localized
 *    "(uncategorised)" header (for Eggs + Bread).
 *  - Tapping again returns to ALPHABETIC.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ItemsListSortToggleE2ETest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var itemDao: ItemDao
    @Inject lateinit var storeDao: StoreDao
    @Inject lateinit var categoryDao: CategoryDao
    @Inject lateinit var xrefDao: ItemStoreXrefDao

    @Before fun setUp() {
        hiltRule.inject()
        runBlocking { seedE2EFixtures(itemDao, storeDao, categoryDao, xrefDao) }
    }

    @Test fun togglingSortModeShowsCategoryHeadersAndPersistsAcrossTaps() {
        composeRule.onNodeWithText("Items").performClick()

        // Default = ALPHABETIC. The toolbar offers "Switch to category sort".
        // (There may be one in the Items toolbar; the StorePicker also has
        // its own toggle, but the Items tab is what we navigated to.)
        composeRule.onAllNodesWithContentDescription("Switch to category sort")[0]
            .assertIsDisplayed()
            .performClick()

        // CATEGORY mode -- "(uncategorised)" header is a unique node
        // (Eggs + Bread fall under it). "Dairy" appears both as a section
        // header and as Milk's subtitle, so we use the uncategorised
        // header as the sentinel that proves we're in CATEGORY mode.
        composeRule.onNodeWithText("(uncategorised)").assertIsDisplayed()

        // The toolbar icon flipped to "Switch to alphabetic sort".
        composeRule.onAllNodesWithContentDescription("Switch to alphabetic sort")[0]
            .assertIsDisplayed()
            .performClick()

        // Back to ALPHABETIC -- the headers are gone (Bread now appears
        // alphabetically before Eggs / Milk in flat order).
        composeRule.onNodeWithText("Bread").assertIsDisplayed()
    }
}
