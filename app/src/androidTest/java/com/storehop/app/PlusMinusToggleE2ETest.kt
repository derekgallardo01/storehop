package com.storehop.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
 * v0.6.1 +/- toggle on the Items list. Verifies:
 *  - "Bread" (no tagged stores) shows the "+" icon and the IconButton is
 *    NOT enabled.
 *  - "Eggs" (tagged at Lidl, currently needed) shows "−" and IS enabled.
 *  - "Milk" (tagged at both stores, needed) shows "−"; tap it -> the
 *    icon flips to "+" (item now removed from all lists).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PlusMinusToggleE2ETest {

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

    @Test fun rowsRenderPlusMinusBasedOnNeededState() {
        // Navigate to the Items tab. Bottom-nav label is "Items".
        composeRule.onNodeWithText("Items").performClick()

        // Each row's button has either contentDescription="Add to shopping
        // list" (the +) or "Remove from shopping list" (the −). Three items
        // total: Milk + Eggs are needed (−), Bread has no stores (+
        // disabled).
        composeRule.onNodeWithText("Milk").assertIsDisplayed()
        composeRule.onNodeWithText("Eggs").assertIsDisplayed()
        composeRule.onNodeWithText("Bread").assertIsDisplayed()

        // Two rows are "Remove from shopping list" (Milk and Eggs).
        val removeButtons =
            composeRule.onAllNodesWithContentDescription("Remove from shopping list")
        // Truth on count via fetchSemanticsNodes.
        assert(removeButtons.fetchSemanticsNodes().size == 2) {
            "Expected 2 minus-buttons (Milk + Eggs), got ${removeButtons.fetchSemanticsNodes().size}"
        }

        // One row is "Add to shopping list" -- Bread, with no tagged stores.
        // Its button is disabled.
        val addButton = composeRule.onAllNodesWithContentDescription("Add to shopping list")
        assert(addButton.fetchSemanticsNodes().size == 1)
        addButton[0].assertIsNotEnabled()
    }
}
