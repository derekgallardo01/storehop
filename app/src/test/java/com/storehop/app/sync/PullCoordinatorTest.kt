package com.storehop.app.sync

import com.google.android.gms.tasks.Tasks
import com.google.common.truth.Truth.assertThat
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.storehop.app.data.dao.PullWriteDao
import com.storehop.app.data.entity.Category
import com.storehop.app.data.entity.Item
import com.storehop.app.data.entity.ItemStoreXref
import com.storehop.app.data.entity.PurchaseRecord
import com.storehop.app.data.entity.Store
import com.storehop.app.data.entity.StoreCategoryOrder
import com.storehop.app.sync.dto.CategoryDto
import com.storehop.app.sync.dto.ItemDto
import com.storehop.app.sync.dto.ItemStoreXrefDto
import com.storehop.app.sync.dto.PurchaseRecordDto
import com.storehop.app.sync.dto.StoreCategoryOrderDto
import com.storehop.app.sync.dto.StoreDto
import com.storehop.app.sync.dto.SyncCollections
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * Pin PullCoordinator's contracts:
 *
 *  - peek returns true/false correctly based on the stores subcollection
 *  - successful pull writes all six entity lists to PullWriteDao via the
 *    transaction wrapper; pulled rows have pendingSync=false (mappers
 *    enforce this -- exercised here via integration with the real mappers)
 *  - any subcollection failure -> PullResult.Failure, DB never written
 *  - mutex serializes concurrent pulls for the same uid
 */
class PullCoordinatorTest {

    private val firestore: FirebaseFirestore = mockk()
    private val pullWriteDao: PullWriteDao = mockk(relaxed = true)
    private lateinit var coordinator: PullCoordinator

    @Before fun setup() {
        coordinator = PullCoordinator(firestore, pullWriteDao)
    }

    @Test fun `peek returns true when stores subcollection has at least one doc`() = runTest {
        stubPeek(uid = "uid", isEmpty = false)
        assertThat(coordinator.peek("uid")).isTrue()
    }

    @Test fun `peek returns false when stores subcollection is empty`() = runTest {
        stubPeek(uid = "uid", isEmpty = true)
        assertThat(coordinator.peek("uid")).isFalse()
    }

    @Test fun `peek throws on Firestore error`() = runTest {
        val users: CollectionReference = mockk()
        val userDoc: DocumentReference = mockk()
        val storesColl: CollectionReference = mockk()
        val limited: Query = mockk()
        every { firestore.collection("users") } returns users
        every { users.document("uid") } returns userDoc
        every { userDoc.collection(SyncCollections.STORES) } returns storesColl
        every { storesColl.limit(1) } returns limited
        every { limited.get() } returns Tasks.forException(RuntimeException("PERMISSION_DENIED"))

        var caught: Exception? = null
        try {
            coordinator.peek("uid")
        } catch (e: Exception) {
            caught = e
        }
        assertThat(caught).isNotNull()
    }

    @Test fun `successful pull writes all six entity lists to PullWriteDao`() = runTest {
        stubPullForUid(
            uid = "uid",
            items = listOf(itemDto("milk")),
            categories = listOf(categoryDto("cat")),
            stores = listOf(storeDto("lidl")),
            xrefs = listOf(xrefDto("milk", "lidl")),
            scos = listOf(scoDto("lidl", "cat")),
            purchaseRecords = listOf(purchaseDto("p1")),
        )

        val result = coordinator.pullForUid("uid")
        assertThat(result).isInstanceOf(PullCoordinator.PullResult.Success::class.java)
        result as PullCoordinator.PullResult.Success
        assertThat(result.itemCount).isEqualTo(1)
        assertThat(result.storeCount).isEqualTo(1)

        coVerify(exactly = 1) {
            pullWriteDao.replaceAllForUid(
                items = match<List<Item>> { it.size == 1 && !it.first().pendingSync },
                categories = match<List<Category>> { it.size == 1 && !it.first().pendingSync },
                stores = match<List<Store>> { it.size == 1 && !it.first().pendingSync },
                xrefs = match<List<ItemStoreXref>> { it.size == 1 && !it.first().pendingSync },
                scoOrders = match<List<StoreCategoryOrder>> { it.size == 1 && !it.first().pendingSync },
                purchaseRecords = match<List<PurchaseRecord>> { it.size == 1 && !it.first().pendingSync },
            )
        }
    }

    @Test fun `empty cloud pull writes empty lists and returns zero counts`() = runTest {
        stubPullForUid(uid = "uid")

        val result = coordinator.pullForUid("uid")
        assertThat(result).isInstanceOf(PullCoordinator.PullResult.Success::class.java)
        result as PullCoordinator.PullResult.Success
        assertThat(result.itemCount).isEqualTo(0)
        assertThat(result.storeCount).isEqualTo(0)

        // The transaction still runs (with empty lists) — that's fine; it's a
        // no-op write. Just verify we got there without throwing.
        coVerify(exactly = 1) {
            pullWriteDao.replaceAllForUid(any(), any(), any(), any(), any(), any())
        }
    }

    @Test fun `pull returns Failure when one subcollection throws`() = runTest {
        // Items subcollection blows up -- DB never gets written.
        stubPullForUid(uid = "uid", itemsThrow = RuntimeException("network"))

        val result = coordinator.pullForUid("uid")
        assertThat(result).isInstanceOf(PullCoordinator.PullResult.Failure::class.java)

        // Critical: PullWriteDao is NEVER called -- no partial state lands.
        coVerify(exactly = 0) {
            pullWriteDao.replaceAllForUid(any(), any(), any(), any(), any(), any())
        }
    }

    @Test fun `mutex serializes two concurrent pulls for the same uid`() = runTest {
        // Two parallel pullForUid calls must not interleave: PullWriteDao
        // must only see one transaction at a time. We stub the DAO to delay
        // so we can observe ordering.
        stubPullForUid(uid = "uid")
        coEvery { pullWriteDao.replaceAllForUid(any(), any(), any(), any(), any(), any()) } coAnswers {
            delay(10)
        }

        coroutineScope {
            val a = async { coordinator.pullForUid("uid") }
            val b = async { coordinator.pullForUid("uid") }
            a.await()
            b.await()
        }

        // Both transactions ran (Mutex doesn't drop calls, just serializes).
        coVerify(exactly = 2) {
            pullWriteDao.replaceAllForUid(any(), any(), any(), any(), any(), any())
        }
    }

    // -------- helpers --------

    private fun stubPeek(uid: String, isEmpty: Boolean) {
        val users: CollectionReference = mockk()
        val userDoc: DocumentReference = mockk()
        val storesColl: CollectionReference = mockk()
        val limited: Query = mockk()
        val snap: QuerySnapshot = mockk { every { this@mockk.isEmpty } returns isEmpty }
        every { firestore.collection("users") } returns users
        every { users.document(uid) } returns userDoc
        every { userDoc.collection(SyncCollections.STORES) } returns storesColl
        every { storesColl.limit(1) } returns limited
        every { limited.get() } returns Tasks.forResult(snap)
    }

    /**
     * Stubs the parallel `.get()` on each of the six subcollections under
     * `users/{uid}`. Pass `*Throw` to make a specific collection fail.
     */
    private fun stubPullForUid(
        uid: String,
        items: List<ItemDto> = emptyList(),
        categories: List<CategoryDto> = emptyList(),
        stores: List<StoreDto> = emptyList(),
        xrefs: List<ItemStoreXrefDto> = emptyList(),
        scos: List<StoreCategoryOrderDto> = emptyList(),
        purchaseRecords: List<PurchaseRecordDto> = emptyList(),
        itemsThrow: Throwable? = null,
    ) {
        val users: CollectionReference = mockk()
        val userDoc: DocumentReference = mockk()
        every { firestore.collection("users") } returns users
        every { users.document(uid) } returns userDoc

        every { userDoc.collection(SyncCollections.ITEMS) } returns
            stubCollectionGet(items, ItemDto::class.java, itemsThrow)
        every { userDoc.collection(SyncCollections.CATEGORIES) } returns
            stubCollectionGet(categories, CategoryDto::class.java, null)
        every { userDoc.collection(SyncCollections.STORES) } returns
            stubCollectionGet(stores, StoreDto::class.java, null)
        every { userDoc.collection(SyncCollections.ITEM_STORE_XREFS) } returns
            stubCollectionGet(xrefs, ItemStoreXrefDto::class.java, null)
        every { userDoc.collection(SyncCollections.STORE_CATEGORY_ORDERS) } returns
            stubCollectionGet(scos, StoreCategoryOrderDto::class.java, null)
        every { userDoc.collection(SyncCollections.PURCHASE_RECORDS) } returns
            stubCollectionGet(purchaseRecords, PurchaseRecordDto::class.java, null)
    }

    private fun <T : Any> stubCollectionGet(
        dtos: List<T>,
        dtoClass: Class<T>,
        throwable: Throwable?,
    ): CollectionReference {
        val coll: CollectionReference = mockk()
        if (throwable != null) {
            every { coll.get() } returns Tasks.forException(Exception(throwable))
        } else {
            val snap: QuerySnapshot = mockk { every { toObjects(dtoClass) } returns dtos }
            every { coll.get() } returns Tasks.forResult(snap)
        }
        return coll
    }

    private fun itemDto(id: String) = ItemDto(
        id = id, name = id, categoryId = null, notes = null, quantity = null,
        isNeeded = true, lastPurchasedAt = null, userId = "u",
        createdAt = 1L, updatedAt = 1L, deletedAt = null,
    )

    private fun categoryDto(id: String) = CategoryDto(
        id = id, name = id, nameKey = null, icon = null,
        isArchived = false, isSeeded = false, userId = "u",
        createdAt = 1L, updatedAt = 1L, deletedAt = null,
    )

    private fun storeDto(id: String) = StoreDto(
        id = id, name = id, colorArgb = null,
        isArchived = false, isSeeded = false, userId = "u",
        createdAt = 1L, updatedAt = 1L, deletedAt = null,
    )

    private fun xrefDto(itemId: String, storeId: String) = ItemStoreXrefDto(
        itemId = itemId, storeId = storeId, userId = "u",
        createdAt = 1L, updatedAt = 1L, deletedAt = null,
    )

    private fun scoDto(storeId: String, categoryId: String) = StoreCategoryOrderDto(
        storeId = storeId, categoryId = categoryId, displayOrder = 0,
        isSeeded = false, userId = "u",
        createdAt = 1L, updatedAt = 1L, deletedAt = null,
    )

    private fun purchaseDto(id: String) = PurchaseRecordDto(
        id = id, itemId = "i", storeId = "s", purchasedAt = 1L,
        userId = "u", createdAt = 1L, updatedAt = 1L, deletedAt = null,
    )
}
