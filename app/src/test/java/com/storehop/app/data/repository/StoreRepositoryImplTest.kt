package com.storehop.app.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.storehop.app.data.db.StorehopDatabase
import com.storehop.app.data.util.IdGenerator
import com.storehop.app.data.util.UserSessionProvider
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
            session = object : UserSessionProvider {
                // Same sentinel as the seed pack so the unique-(userId,name) index applies.
                override fun currentUserId(): String = "local-only"
            },
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

    @Test fun `concurrent addStore with the same name only succeeds once`() = runTest {
        // Without withTransaction wrapping addStore, two coroutines could both pass
        // findByName == null and then upsert, with the second silently no-opping
        // (Room @Upsert IGNOREs the unique-index conflict). Wrapping in
        // withTransaction serializes the read+write so the second call sees the
        // first's row and the require() check throws. Stress this:
        val results: List<Result<String>> = coroutineScope {
            val deferreds: List<Deferred<Result<String>>> = (0 until 8).map {
                async(Dispatchers.Default) {
                    runCatching { repo.addStore("RaceStore", colorArgb = null) }
                }
            }
            deferreds.awaitAll()
        }
        val successes = results.count { it.isSuccess }
        val failures = results.count { it.isFailure }

        assertThat(successes).isEqualTo(1)
        assertThat(failures).isEqualTo(7)
        // And only one row in the DB.
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
            session = object : UserSessionProvider {
                override fun currentUserId(): String = "some-other-user"
            },
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
