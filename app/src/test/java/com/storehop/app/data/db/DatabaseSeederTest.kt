package com.storehop.app.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import com.google.common.truth.Truth.assertThat
import com.storehop.app.data.util.LocalOnlyUserSessionProvider
import com.storehop.app.testing.createTestDb
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DatabaseSeederTest {

    private lateinit var db: StorehopDatabase

    @Before fun setup() { db = createTestDb(seeded = true) }
    @After fun tearDown() { db.close() }

    @Test fun `seeds 14 stores 21 categories and 70+ store_category_orders`() {
        assertThat(countRows("stores")).isEqualTo(14)
        assertThat(countRows("categories")).isEqualTo(21)
        // 5 grocery stores × 13 cats = 65, plus the specialty stores ≈ 80.
        assertThat(countRows("store_category_order")).isAtLeast(70)
    }

    @Test fun `seeded store IDs are deterministic and stable`() {
        val storeIds = queryStrings("SELECT id FROM stores ORDER BY id")
        assertThat(storeIds).containsExactly(
            "store_aldi", "store_auchan", "store_continente", "store_flavers",
            "store_glovo", "store_leroy_merlin", "store_liberty_store", "store_lidl",
            "store_mega_store", "store_normal", "store_oriental_market",
            "store_pharmacia", "store_pingo_doce", "store_wells",
        ).inOrder()
    }

    @Test fun `seeded category IDs are deterministic and stable`() {
        val catIds = queryStrings("SELECT id FROM categories ORDER BY id").toSet()
        assertThat(catIds).containsAtLeast(
            "cat_produce", "cat_bakery", "cat_dairy_eggs", "cat_meat_fish",
            "cat_frozen", "cat_pantry", "cat_household", "cat_other",
        )
    }

    @Test fun `every seeded row carries isSeeded=1 and userId=local-only`() {
        assertThat(countRows("stores", "isSeeded = 1 AND userId = 'local-only'"))
            .isEqualTo(14)
        assertThat(countRows("categories", "isSeeded = 1 AND userId = 'local-only'"))
            .isEqualTo(21)
        assertThat(LocalOnlyUserSessionProvider.LOCAL_ONLY).isEqualTo("local-only")
    }

    @Test fun `Lidl has the expected aisle order beginning with produce at displayOrder=0`() {
        val rows = queryStrings(
            """
            SELECT categoryId FROM store_category_order
            WHERE storeId = 'store_lidl' AND deletedAt IS NULL
            ORDER BY displayOrder
            """.trimIndent(),
        )
        assertThat(rows.first()).isEqualTo("cat_produce")
        assertThat(rows.take(4)).containsExactly(
            "cat_produce", "cat_bakery", "cat_dairy_eggs", "cat_meat_fish",
        ).inOrder()
    }

    private fun countRows(table: String, where: String? = null): Int {
        val sql = "SELECT COUNT(*) FROM $table" + (where?.let { " WHERE $it" } ?: "")
        db.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql)).use { c ->
            c.moveToFirst()
            return c.getInt(0)
        }
    }

    private fun queryStrings(sql: String): List<String> {
        val out = mutableListOf<String>()
        db.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql)).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }
}
