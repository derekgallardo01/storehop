package com.storehop.app.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.storehop.app.data.db.StorehopDatabase
import com.storehop.app.data.util.IdGenerator
import com.storehop.app.testing.FakeSessionProvider
import com.storehop.app.testing.TEST_USER_ID
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
class StoreRepositoryImplTest {

    private lateinit var db: StorehopDatabase
    private lateinit var repo: StoreRepositoryImpl

    @Before fun setup() {
        // Seeded so the test can attempt to add a store with the same name as a seeded one.
        db = createTestDb(seeded = true)
        repo = StoreRepositoryImpl(
            db = db,
            dao = db.storeDao(),
            xrefDao = db.itemStoreXrefDao(),
            scoDao = db.storeCategoryOrderDao(),
            ids = object : IdGenerator { override fun newId(): String = UUID.randomUUID().toString() },
            clock = Clock.fixed(Instant.ofEpochMilli(50_000L), ZoneOffset.UTC),
            // Same sentinel as the seed pack so the unique-(userId,name) index applies.
            session = FakeSessionProvider("local-only"),
        )
    }

    @After fun tearDown() { db.close() }

    @Test fun `addStore appends a user store alongside the seeded ones`() = runTest {
        repo.addStore("Mercadona", colorArgb = null)
        val all = repo.observeAll(includeArchived = false).first()
        // 14 seeded + 1 user = 15.
        assertThat(all).hasSize(15)
        val mercadona = all.single { it.name == "Mercadona" }
        assertThat(mercadona.isSeeded).isFalse()
    }

    @Test fun `addStore rejects a duplicate name (case-insensitive) with IllegalArgumentException`() {
        // Room's @Upsert silently no-ops on a non-PK unique-index conflict, so
        // StoreRepositoryImpl detects the collision explicitly. This test pins
        // that contract: same name as a seeded store, mixed case, both rejected.
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repo.addStore("Lidl", colorArgb = null) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repo.addStore("lidl", colorArgb = null) }
        }
        // No phantom row was created.
        kotlinx.coroutines.runBlocking {
            val all = repo.observeAll(includeArchived = false).first()
            assertThat(all.count { it.name.equals("Lidl", ignoreCase = true) }).isEqualTo(1)
        }
    }

    @Test fun `NONA-2 addStore handles a 10000-char name without truncation or crash`() = runTest {
        val giant = "X".repeat(10_000)
        val id = repo.addStore(name = giant, colorArgb = null)
        val live = repo.observeAll(includeArchived = false).first()
            .single { it.id == id }
        assertThat(live.name).isEqualTo(giant)
        assertThat(live.name.length).isEqualTo(10_000)
    }

    @Test fun `NONA-3 addStore name with quotes apostrophes and SQL meta characters round-trips`() = runTest {
        // SQLite parameterized queries protect against injection. This locks in
        // that the repo isn't doing string concatenation anywhere we'd regress.
        val tricky = "Joe's \"Mart\"; DROP TABLE stores; -- 100% safe"
        val id = repo.addStore(name = tricky, colorArgb = null)
        // Verify the row exists, contains the literal string, AND that the stores
        // table is intact (DROP didn't run).
        val live = repo.observeAll(includeArchived = false).first()
        assertThat(live.single { it.id == id }.name).isEqualTo(tricky)
        // Seed pack still intact => DROP TABLE didn't execute.
        assertThat(live.count { it.isSeeded }).isEqualTo(14)
    }

    @Test fun `addStore rejects an empty or whitespace-only name`() {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repo.addStore("", colorArgb = null) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repo.addStore("   ", colorArgb = null) }
        }
    }

    @Test fun `archived seeded store is excluded from observeAll(false) and listed by observeAll(true)`() = runTest {
        repo.setArchived("store_lidl", archived = true)
        assertThat(repo.observeAll(includeArchived = false).first().map { it.id })
            .doesNotContain("store_lidl")
        assertThat(repo.observeAll(includeArchived = true).first().map { it.id })
            .contains("store_lidl")
    }

    @Test fun `addStore resurrects a previously soft-deleted store with the same name (preserves id for sync)`() = runTest {
        repo.softDelete("store_lidl")
        // Resurrection path: addStore returns the ORIGINAL id, not a fresh UUID.
        // Re-using the id is correct for sync semantics (other devices see the
        // tombstone clear, not a delete-and-create which would conflict with
        // their own tombstones).
        val resurrectedId = repo.addStore("Lidl", colorArgb = 0xFF112233.toInt())
        assertThat(resurrectedId).isEqualTo("store_lidl")

        val live = repo.observeAll(includeArchived = false).first()
            .filter { it.name == "Lidl" }
        assertThat(live).hasSize(1)
        // The resurrected row keeps its seeded provenance (it's still the seeded
        // Lidl, just temporarily deleted then restored), and picks up the new color.
        assertThat(live.single().id).isEqualTo("store_lidl")
        assertThat(live.single().isSeeded).isTrue()
        assertThat(live.single().colorArgb).isEqualTo(0xFF112233.toInt())
        assertThat(live.single().deletedAt).isNull()
    }

    @Test fun `addStore still rejects a duplicate when the existing row is live (not tombstoned)`() {
        // Sanity-check that the resurrection path doesn't accidentally also resurrect
        // live rows -- the live-duplicate behavior must still throw.
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repo.addStore("Lidl", colorArgb = null) }
        }
    }

    @Test fun `concurrent addStore with the same name only succeeds once`() = runTest {
        // Without withTransaction wrapping addStore, two coroutines could both pass
        // findByName == null and then upsert, with the second silently no-opping
        // (Room @Upsert IGNOREs the unique-index conflict). Wrapping in
        // withTransaction serializes the read+write so the second call sees the
        // first's row and the require() check throws. Stress this:
        val results: List<Result<String>> = coroutineScope {
            val deferreds: List<Deferred<Result<String>>> = (0 until 100).map {
                async(Dispatchers.Default) {
                    runCatching { repo.addStore("RaceStore", colorArgb = null) }
                }
            }
            deferreds.awaitAll()
        }
        val successes = results.count { it.isSuccess }
        val failures = results.count { it.isFailure }

        // Octa-check bumped from 8 to 100 to make sure the lock truly serializes
        // under realistic contention rather than just a small lucky window.
        assertThat(successes).isEqualTo(1)
        assertThat(failures).isEqualTo(99)
        val ours = repo.observeAll(includeArchived = false).first()
            .filter { it.name == "RaceStore" }
        assertThat(ours).hasSize(1)
    }

    @Test fun `softDelete cascades to xrefs and store_category_orders for that store`() = runTest {
        // Seed: Lidl already has 13 SCO rows. Tag two new items to Lidl too.
        listOf("milk", "bread").forEach { id ->
            db.itemDao().upsert(
                com.storehop.app.data.entity.Item(
                    id = id, name = id, categoryId = null, notes = null, quantity = null,
                    isNeeded = true, lastPurchasedAt = null,
                    userId = "local-only", createdAt = 1L, updatedAt = 1L, deletedAt = null,
                ),
            )
            db.itemStoreXrefDao().upsert(
                com.storehop.app.data.entity.ItemStoreXref(
                    itemId = id, storeId = "store_lidl", userId = "local-only",
                    createdAt = 1L, updatedAt = 1L, deletedAt = null,
                ),
            )
        }
        // Pre-conditions: live xrefs and live SCO rows for Lidl exist.
        assertThat(db.itemStoreXrefDao().findForItem("milk")).isNotEmpty()
        assertThat(db.storeCategoryOrderDao().findForStore("store_lidl")).isNotEmpty()

        repo.softDelete("store_lidl")

        // Lidl is gone from observeAll(includeArchived=false).
        assertThat(repo.observeAll(includeArchived = false).first().map { it.id })
            .doesNotContain("store_lidl")
        // Cascade tombstoned the xrefs (findForItem filters deletedAt IS NULL).
        assertThat(db.itemStoreXrefDao().findForItem("milk")).isEmpty()
        assertThat(db.itemStoreXrefDao().findForItem("bread")).isEmpty()
        // Cascade tombstoned the SCO rows (findForStore filters deletedAt IS NULL).
        assertThat(db.storeCategoryOrderDao().findForStore("store_lidl")).isEmpty()
        // Items themselves are NOT deleted -- they're just untagged from Lidl.
        // (This is intentional: a user might re-add the store later, and the item
        //  data should outlive the store-tagging.)
        assertThat(db.itemDao().observeNeeded("local-only").first().map { it.id })
            .containsAtLeast("milk", "bread")
    }

    @Test fun `setArchived softDelete and rename are no-ops for stores the session does not own`() = runTest {
        // Build a separate repo whose session is some-other-user, then try to
        // mutate "store_lidl" (which is owned by local-only via the seed).
        val otherRepo = StoreRepositoryImpl(
            db = db,
            dao = db.storeDao(),
            xrefDao = db.itemStoreXrefDao(),
            scoDao = db.storeCategoryOrderDao(),
            ids = object : IdGenerator { override fun newId(): String = UUID.randomUUID().toString() },
            clock = Clock.fixed(Instant.ofEpochMilli(50_000L), ZoneOffset.UTC),
            session = FakeSessionProvider("some-other-user"),
        )

        otherRepo.setArchived("store_lidl", archived = true)
        otherRepo.softDelete("store_lidl")
        otherRepo.rename("store_lidl", "Hijacked")

        // From the original (correct) owner's perspective, store_lidl is unchanged.
        val lidl = repo.observeById("store_lidl").first()
        assertThat(lidl).isNotNull()
        assertThat(lidl!!.name).isEqualTo("Lidl")
        assertThat(lidl.isArchived).isFalse()
        assertThat(lidl.deletedAt).isNull()
    }
}
