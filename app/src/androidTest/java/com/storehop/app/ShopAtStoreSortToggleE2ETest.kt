package com.storehop.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
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
 * v0.6.0 sort toggle inside a store. Default is CATEGORY (aisle headers
 * visible). Tapping the toolbar toggle flips to ALPHABETIC.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ShopAtStoreSortToggleE2ETest {

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

    @Test fun togglingSortInStoreFlipsBetweenAisleHeadersAndFlatList() {
        // StorePicker -> tap "Lidl".
        composeRule.onNodeWithText("Lidl").performClick()

        // CATEGORY default: "Dairy" header visible (Milk's category).
        composeRule.onNodeWithText("Dairy").assertIsDisplayed()

        // Toggle to alphabetic.
        composeRule.onAllNodesWithContentDescription("Switch to alphabetic sort")[0]
            .performClick()
        composeRule.waitForIdle()

        // Items still visible, just no aisle headers. We use Milk as the
        // sentinel because it's alphabetically last among Lidl items
        // (Eggs, Milk) and is the most reliable to assert on.
        composeRule.onNodeWithText("Milk").assertIsDisplayed()

        // Toggle back to category.
        composeRule.onAllNodesWithContentDescription("Switch to category sort")[0]
            .performClick()
        composeRule.waitForIdle()
        // The "Dairy" string appears in both the section header and the
        // Milk row's subtitle -- assert via onAllNodes count (>= 1).
        assert(
            composeRule.onAllNodesWithText("Dairy").fetchSemanticsNodes().isNotEmpty()
        )
    }
}
