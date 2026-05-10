package com.storehop.app.testing

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.storehop.app.MainActivity
import com.storehop.app.data.dao.CategoryDao
import com.storehop.app.data.dao.ItemDao
import com.storehop.app.data.dao.ItemStoreXrefDao
import com.storehop.app.data.dao.StoreDao
import dagger.hilt.android.testing.HiltAndroidRule
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Base class for the E2E suite. Establishes:
 *  - Hilt DI rule (must be order=0).
 *  - Compose-rule-on-MainActivity (order=1, runs after Hilt injection).
 *
 * Subclasses inject the DAOs they need (`@Inject lateinit var itemDao: ItemDao`),
 * call `hiltRule.inject()` in `@Before`, then optionally `seedFixtures()`
 * to lay down the canonical test data before triggering UI interactions.
 *
 * The in-memory DB is fresh for each test (provided by [TestDatabaseModule]).
 */
@RunWith(AndroidJUnit4::class)
abstract class E2EBaseTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<MainActivity>()
}
