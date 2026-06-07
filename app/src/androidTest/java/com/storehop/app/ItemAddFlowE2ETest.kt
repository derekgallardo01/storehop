package com.storehop.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * E2E for the basic Add Item flow: Items tab -> + FAB -> name -> save ->
 * appears in list. Exercises the form's bare-minimum happy path.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ItemAddFlowE2ETest {

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

    @Test fun addItemViaFormAppearsInList() {
        // Items tab -> + FAB.
        composeRule.onNodeWithText("Items").performClick()
        composeRule.onNodeWithContentDescription("Add item").performClick()

        // Type a name + tap Save.
        composeRule.onNodeWithText("Name").performTextInput("Yogurt")
        composeRule.onNodeWithText("Save").performClick()

        // Back on the list, the new row is visible.
        composeRule.onNodeWithText("Yogurt").assertIsDisplayed()
    }
}
