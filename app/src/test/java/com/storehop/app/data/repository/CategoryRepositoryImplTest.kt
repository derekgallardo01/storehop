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

    @Test fun `rename updates the name, bumps updatedAt, and flips pendingSync`() = runTest {
        val produceBefore = db.categoryDao().findById("local-only", "cat_produce")!!
        // Pre-condition: seeder leaves pendingSync = true on initial insert
        // (pre-cloud), so we bounce it to false to verify rename re-flips it.
        db.categoryDao().upsert(produceBefore.copy(pendingSync = false))

        repo.rename("cat_produce", "Fresh produce")

        val after = db.categoryDao().findById("local-only", "cat_produce")!!
        assertThat(after.name).isEqualTo("Fresh produce")
        assertThat(after.updatedAt).isEqualTo(50_000L)
        assertThat(after.pendingSync).isTrue()
    }

    @Test fun `rename trims whitespace from the new name`() = runTest {
        repo.rename("cat_produce", "  Fresh produce  ")
        assertThat(db.categoryDao().findById("local-only", "cat_produce")!!.name)
            .isEqualTo("Fresh produce")
    }

    @Test fun `rename is a silent no-op when the id does not exist for this user`() = runTest {
        repo.rename("does_not_exist", "anything")
        // No throw; the absent id is treated as a missing precondition, not an
        // error. (Mirrors how softDelete handles ownership-failed targets.)
        // Sanity: nothing else changed.
        val all = repo.observeAll(includeArchived = false).first()
        assertThat(all.any { it.id == "does_not_exist" }).isFalse()
    }

    @Test fun `rename rejects a duplicate name (case-insensitive) of an alive category with IllegalArgumentException`() {
        // The unique index on (userId, name) covers tombstones too, so an
        // unguarded upsert on rename hits SQLiteConstraintException and
        // surfaces the generic "Could not rename" error. Mike hit this after
        // importing hundreds of categories from his old app -- v0.5.5 fix.
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repo.rename("cat_produce", "Bakery") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repo.rename("cat_produce", "bakery") }
        }
        // Sanity: cat_produce keeps its original name.
        kotlinx.coroutines.runBlocking {
            assertThat(db.categoryDao().findById("local-only", "cat_produce")!!.name)
                .isEqualTo("Produce")
        }
    }

    @Test fun `rename succeeds when the target name is held only by a tombstoned category`() = runTest {
        // Tombstone "Bakery" (still in the table as a soft-delete), then
        // rename Produce -> Bakery. Pre-v6 the UNIQUE index counted
        // tombstones and this rename failed with SQLiteConstraintException.
        // Post-v6 the index is non-unique, application-layer collision check
        // is alive-only, so the rename succeeds and the tombstoned Bakery
        // stays tombstoned (still hidden from observeAll). This is the bug
        // Mike hit on v0.5.4 -- can't rename "Pet" -> "Pets" because a
        // deleted "Pets" remnant was in the way.
        repo.softDelete("cat_bakery")
        repo.rename("cat_produce", "Bakery")

        val produce = db.categoryDao().findById("local-only", "cat_produce")!!
        assertThat(produce.name).isEqualTo("Bakery")
        assertThat(produce.deletedAt).isNull()
        // The tombstoned Bakery row is still present (and still tombstoned)
        // -- we didn't touch it. observeAll(includeArchived=false) excludes
        // tombstones, so the user sees only one alive "Bakery" (the renamed
        // Produce). There are now two rows in the table with name="Bakery"
        // (one alive, one tombstoned) -- legal post-v6.
        val baked = db.categoryDao().findAnyById("local-only", "cat_bakery")!!
        assertThat(baked.deletedAt).isNotNull()
        val visible = repo.observeAll(includeArchived = false).first()
            .filter { it.name.equals("Bakery", ignoreCase = true) }
        assertThat(visible).hasSize(1)
        assertThat(visible.single().id).isEqualTo("cat_produce")
    }

    @Test fun `rename allows a case-only change of the row's own name`() = runTest {
        // "Produce" -> "produce" is the same row keeping its id; the unique
        // index doesn't reject because we're upserting the same primary key.
        // The collision lookup finds the same row, so the same-id check
        // permits the rename through.
        repo.rename("cat_produce", "produce")
        assertThat(db.categoryDao().findById("local-only", "cat_produce")!!.name)
            .isEqualTo("produce")
    }

    @Test fun `rename rejects an empty or whitespace-only name`() {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repo.rename("cat_produce", "") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repo.rename("cat_produce", "   ") }
        }
    }

    @Test fun `rename is a no-op for a category owned by a different user`() = runTest {
        val otherRepo = CategoryRepositoryImpl(
            db = db, dao = db.categoryDao(), itemDao = db.itemDao(),
            scoDao = db.storeCategoryOrderDao(),
            ids = object : IdGenerator { override fun newId(): String = UUID.randomUUID().toString() },
            clock = Clock.fixed(Instant.ofEpochMilli(50_000L), ZoneOffset.UTC),
            session = FakeSessionProvider("some-other-user"),
        )

        otherRepo.rename("cat_produce", "HIJACKED")

        // Owner's view: name is unchanged.
        val produce = db.categoryDao().findById("local-only", "cat_produce")!!
        assertThat(produce.name).isEqualTo("Produce")
    }

    @Test fun `undoSoftDelete restores the category, its SCO rows, and the items it had`() = runTest {
        // Build state: two items in cat_produce; cat_produce has SCO rows at
        // multiple stores via the seeder.
        listOf("milk", "bread").forEach { id ->
            db.itemDao().upsert(
                com.storehop.app.data.entity.Item(
                    id = id, name = id, categoryId = "cat_produce", notes = null, quantity = null,
                    isNeeded = true, lastPurchasedAt = null,
                    userId = "local-only", createdAt = 1L, updatedAt = 1L, deletedAt = null,
                ),
            )
        }
        val lidlScoBefore = db.storeCategoryOrderDao().findForStore("store_lidl")
            .count { it.categoryId == "cat_produce" }
        assertThat(lidlScoBefore).isEqualTo(1)

        repo.softDelete("cat_produce")
        // Sanity: items are uncategorized, SCO rows are tombstoned.
        assertThat(
            db.itemDao().observeNeeded("local-only").first()
                .filter { it.id in setOf("milk", "bread") }
                .all { it.categoryId == null },
        ).isTrue()
        assertThat(
            db.storeCategoryOrderDao().findForStore("store_lidl")
                .count { it.categoryId == "cat_produce" },
        ).isEqualTo(0)

        repo.undoSoftDelete("cat_produce")

        // Category itself is alive again.
        val cat = db.categoryDao().findById("local-only", "cat_produce")!!
        assertThat(cat.deletedAt).isNull()
        // Items are re-linked to cat_produce.
        val items = db.itemDao().observeNeeded("local-only").first()
            .filter { it.id in setOf("milk", "bread") }
        assertThat(items.map { it.categoryId }.toSet()).containsExactly("cat_produce")
        // SCO rows are alive again at every store they were at before.
        val lidlScoAfter = db.storeCategoryOrderDao().findForStore("store_lidl")
            .count { it.categoryId == "cat_produce" }
        assertThat(lidlScoAfter).isEqualTo(1)
    }

    @Test fun `undoSoftDelete is a silent no-op when the category was never tombstoned`() = runTest {
        // cat_produce is alive after setup. Calling undo on it should be a
        // no-op rather than corrupting state.
        val before = db.categoryDao().findById("local-only", "cat_produce")!!
        repo.undoSoftDelete("cat_produce")
        val after = db.categoryDao().findById("local-only", "cat_produce")!!
        assertThat(after).isEqualTo(before)
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
