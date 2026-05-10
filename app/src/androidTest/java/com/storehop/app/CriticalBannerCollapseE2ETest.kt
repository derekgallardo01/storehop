package com.storehop.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.storehop.app.data.dao.CategoryDao
import com.storehop.app.data.dao.ItemDao
import com.storehop.app.data.dao.ItemStoreXrefDao
import com.storehop.app.data.dao.StoreDao
import com.storehop.app.data.entity.Item
import com.storehop.app.data.entity.ItemStoreXref
import com.storehop.app.testing.E2E_UID
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
 * v0.6.0 in-store critical-items banner collapse/expand. Default state
 * is collapsed (chevron-down + count headline only). Tap to expand --
 * the comma-list of priority item names appears + chevron flips up.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CriticalBannerCollapseE2ETest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var itemDao: ItemDao
    @Inject lateinit var storeDao: StoreDao
    @Inject lateinit var categoryDao: CategoryDao
    @Inject lateinit var xrefDao: ItemStoreXrefDao

    @Before fun setUp() {
        hiltRule.inject()
        runBlocking {
            val ids = seedE2EFixtures(itemDao, storeDao, categoryDao, xrefDao)
            // Add a priority item to Lidl so the banner has content.
            itemDao.upsert(
                Item(
                    id = "e2e_item_coffee", name = "Coffee", brand = null,
                    categoryId = null, notes = null, quantity = null,
                    isNeeded = true, isStaple = false, isPriority = true,
                    imageUrl = null, lastPurchasedAt = null,
                    userId = E2E_UID, createdAt = 1_000L, updatedAt = 1_000L, deletedAt = null,
                ),
            )
            xrefDao.upsert(
                ItemStoreXref(
                    itemId = "e2e_item_coffee", storeId = ids.storeLidlId, userId = E2E_UID,
                    isNeeded = true, lastPurchasedAt = null,
                    createdAt = 1_000L, updatedAt = 1_000L, deletedAt = null,
                ),
            )
        }
    }

    @Test fun bannerStartsCollapsedAndExpandsOnTap() {
        composeRule.onNodeWithText("Lidl").performClick()

        // Collapsed: the chevron's content description is the localized
        // "Show critical items by store" (reuses the StorePicker banner's
        // strings -- the in-store banner shares those keys for v0.6.0).
        composeRule.onAllNodesWithContentDescription("Show critical items by store")[0]
            .assertIsDisplayed()
            .performClick()

        // Expanded: chevron content description flips to "Hide". We don't
        // assert on the comma list's "Coffee" text directly because the
        // row in the per-aisle list ALSO has "Coffee" -- two nodes matching
        // would fail onNodeWithText. The chevron flip is the unambiguous
        // proof of expansion.
        composeRule.onAllNodesWithContentDescription("Hide critical items by store")[0]
            .assertIsDisplayed()
    }
}
