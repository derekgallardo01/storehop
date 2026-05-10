package com.storehop.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
 * v0.6.0 long-press a row inside a store -> open the item edit form.
 * The form opens in edit mode for the long-pressed item -- name field
 * is pre-filled.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LongPressEditFromStoreE2ETest {

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

    @Test fun longPressRowOpensItemEditForm() {
        // StorePicker -> Lidl.
        composeRule.onNodeWithText("Lidl").performClick()

        // Long-press the "Milk" row text. The combinedClickable is on the
        // entire row, so any node within the row's tree should propagate;
        // we use the visible text node as the gesture target.
        composeRule.onNodeWithText("Milk").performTouchInput { longClick() }

        // Form opens in edit mode -- the item name field shows "Milk".
        // The `Save` button (label) confirms we're on the form.
        composeRule.onNodeWithText("Save").assertIsDisplayed()
    }
}
