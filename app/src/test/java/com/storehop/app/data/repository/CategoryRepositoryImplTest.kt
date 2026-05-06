package com.storehop.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.storehop.app.data.db.StorehopDatabase
import com.storehop.app.data.util.IdGenerator
import com.storehop.app.testing.FakeSessionProvider
import com.storehop.app.testing.createTestDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class CategoryRepositoryImplTest {

    private lateinit var db: StorehopDatabase
    private lateinit var repo: CategoryRepositoryImpl

    @Before fun setup() {
        // Seeded so we can collide a user category with a seeded one.
        db = createTestDb(seeded = true)
        repo = CategoryRepositoryImpl(
            db = db,
            dao = db.categoryDao(),
            itemDao = db.itemDao(),
            scoDao = db.storeCategoryOrderDao(),
            ids = object : IdGenerator { override fun newId(): String = UUID.randomUUID().toString() },
            clock = Clock.fixed(Instant.ofEpochMilli(50_000L), ZoneOffset.UTC),
            session = FakeSessionProvider("local-only"),
        )
    }
    @After fun tearDown() { db.close() }

    @Test fun `addCategory creates a user-added category alongside the seeded set`() = runTest {
        repo.addCategory(name = "Wine", icon = null)
        val all = repo.observeAll(includeArchived = false).first()
        assertThat(all).hasSize(22)
        val wine = all.single { it.name == "Wine" }
        assertThat(wine.isSeeded).isFalse()
        assertThat(wine.nameKey).isNull()
    }

    @Test fun `addCategory rejects a duplicate name (case-insensitive) with IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repo.addCategory("Produce", icon = null) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repo.addCategory("produce", icon = null) }
        }
    }

    @Test fun `addCategory rejects an empty or whitespace-only name`() {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repo.addCategory("", icon = null) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repo.addCategory("\t  ", icon = null) }
        }
    }

    @Test fun `softDelete cascades clearing items categoryId and tombstoning store_category_orders`() = runTest {
        // Tag two items to cat_produce. Lidl already has a SCO row for cat_produce.
        listOf("milk", "bread").forEach { id ->
            db.itemDao().upsert(
                com.storehop.app.data.entity.Item(
                    id = id, name = id, categoryId = "cat_produce", notes = null, quantity = null,
                    isNeeded = true, lastPurchasedAt = null,
                    userId = "local-only", createdAt = 1L, updatedAt = 1L, deletedAt = null,
                ),
            )
        }
        // Pre-condition: there's at least one live SCO pointing at cat_produce.
        val producesScosBefore = db.storeCategoryOrderDao().findForStore("store_lidl")
            .count { it.categoryId == "cat_produce" }
        assertThat(producesScosBefore).isEqualTo(1)

        repo.softDelete("cat_produce")

        // Cascade: items' categoryId is NULL'd (they survive as uncategorized).
        val items = db.itemDao().observeNeeded("local-only").first()
            .filter { it.id in setOf("milk", "bread") }
        assertThat(items).hasSize(2)
        assertThat(items.map { it.categoryId }.toSet()).containsExactly(null)
        // Cascade: SCO rows for cat_produce are tombstoned (across every store).
        val producesScosAfter = db.storeCategoryOrderDao().findForStore("store_lidl")
            .count { it.categoryId == "cat_produce" }
        assertThat(producesScosAfter).isEqualTo(0)
    }

    @Test fun `addCategory resurrects a previously soft-deleted category with the same name`() = runTest {
        repo.softDelete("cat_produce")
        val resurrectedId = repo.addCategory(name = "Produce", icon = null)
        assertThat(resurrectedId).isEqualTo("cat_produce")

        val live = repo.observeAll(includeArchived = false).first()
            .filter { it.name == "Produce" }
        assertThat(live).hasSize(1)
        assertThat(live.single().id).isEqualTo("cat_produce")
        // Keeps its seeded provenance (still the same row, just resurrected).
        assertThat(live.single().isSeeded).isTrue()
        assertThat(live.single().deletedAt).isNull()
    }

    @Test fun `setArchived and softDelete are no-ops for categories the session does not own`() = runTest {
        val otherRepo = CategoryRepositoryImpl(
            db = db,
            dao = db.categoryDao(),
            itemDao = db.itemDao(),
            scoDao = db.storeCategoryOrderDao(),
            ids = object : IdGenerator { override fun newId(): String = UUID.randomUUID().toString() },
            clock = Clock.fixed(Instant.ofEpochMilli(50_000L), ZoneOffset.UTC),
            session = FakeSessionProvider("some-other-user"),
        )

        otherRepo.setArchived("cat_produce", archived = true)
        otherRepo.softDelete("cat_produce")

        // From the correct owner's perspective, cat_produce is unchanged.
        val seeded = repo.observeAll(includeArchived = false).first()
        val produce = seeded.single { it.id == "cat_produce" }
        assertThat(produce.isArchived).isFalse()
        assertThat(produce.deletedAt).isNull()
    }
}
