package com.storehop.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.storehop.app.data.dao.CategoryDao
import com.storehop.app.data.dao.ItemDao
import com.storehop.app.data.dao.ItemStoreXrefDao
import com.storehop.app.data.dao.StoreDao
import com.storehop.app.testing.E2E_UID
import com.storehop.app.testing.seedE2EFixtures
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * v0.5.1 cross-store cascade. Marking Milk purchased at Lidl should
 * clear it from Aldi too -- one trip, list cleared everywhere.
 *
 * Verified via the DB-side xref state because the visible "checked off"
 * UI in Shop-at-Store keeps purchased rows visible (struck through)
 * within the session window. The cascade is the data invariant; the
 * E2E here proves the user-driven action triggers it.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CrossStoreCascadeE2ETest {

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

    @Test fun checkOffAtOneStoreCascadesToOthers() {
        // Both Milk xrefs start needed=true.
        runBlocking {
            val before = xrefDao.findForItem("e2e_item_milk")
            assert(before.size == 2)
            assert(before.all { it.isNeeded }) { "Sanity: both xrefs start needed" }
        }

        // Open Lidl, tap the Milk row to mark purchased.
        composeRule.onNodeWithText("Lidl").performClick()
        composeRule.onNodeWithText("Milk").performClick()
        // Wait for the Compose snackbar / cascade to settle.
        composeRule.waitForIdle()

        // DB-side: every xref's isNeeded flipped to false (cascade).
        runBlocking {
            val after = xrefDao.findForItem("e2e_item_milk")
            assert(after.size == 2)
            assert(after.none { it.isNeeded }) {
                "Cross-store cascade failed -- xrefs: " +
                    after.joinToString { "${it.storeId}=${it.isNeeded}" }
            }
        }
    }
}
