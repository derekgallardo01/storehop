package com.storehop.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
 * v0.6.1 inline "New category…" affordance from the item edit form.
 * Verifies the dialog opens, accepts a name, and the new category is
 * auto-selected on the form.
 *
 * Uses "Bread" because it has no category yet -- the form's category
 * field reads "None" before the action.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class InlineNewCategoryE2ETest {

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

    @Test fun creatingNewCategoryFromItemFormAutoSelectsIt() {
        // Items tab -> tap Bread row -> form opens.
        composeRule.onNodeWithText("Items").performClick()
        composeRule.onNodeWithText("Bread").performClick()

        // The category picker label reads the localized "(none)" by
        // default; tap to open the bottom sheet, then tap "New category…".
        composeRule.onNodeWithText("(none)").performClick()
        composeRule.onNodeWithText("New category…").performClick()

        // The dialog's TextField is labeled with the localized "Category
        // name". Type the new name + tap Add.
        composeRule.onNodeWithText("Category name").performTextInput("Cleaning")
        composeRule.onNodeWithText("Add").performClick()

        // Back on the form, the category field reads the new name. The
        // dialog auto-dismissed.
        composeRule.onNodeWithText("Cleaning").assertIsDisplayed()
    }
}
