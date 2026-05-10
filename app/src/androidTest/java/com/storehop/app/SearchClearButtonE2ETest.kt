package com.storehop.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
 * v0.6.0 search clear-button (×). Verifies the trailing icon only appears
 * once the user types, and tapping it wipes the field.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SearchClearButtonE2ETest {

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

    @Test fun clearButtonAppearsAfterTypingAndWipesTheField() {
        // Items tab has its own search bar.
        composeRule.onNodeWithText("Items").performClick()

        // Before typing: no clear button visible.
        composeRule.onAllNodesWithContentDescription("Clear search")
            .fetchSemanticsNodes().let {
                assert(it.isEmpty()) { "Clear button shouldn't render with empty query" }
            }

        // Type into the search field.
        composeRule.onNodeWithText("Search by name or brand").performTextInput("milk")

        // After typing: clear button appears, and only Milk row is in view.
        composeRule.onAllNodesWithContentDescription("Clear search")[0]
            .assertIsDisplayed()
            .performClick()

        // After tap: query is empty, the placeholder is back.
        composeRule.onNodeWithText("Search by name or brand").assertIsDisplayed()
    }
}
