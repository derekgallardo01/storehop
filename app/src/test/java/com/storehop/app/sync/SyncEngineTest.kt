package com.storehop.app.sync

import com.google.android.gms.tasks.Tasks
import com.google.common.truth.Truth.assertThat
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.storehop.app.data.dao.CategoryDao
import com.storehop.app.data.dao.ItemDao
import com.storehop.app.data.dao.ItemStoreXrefDao
import com.storehop.app.data.dao.PurchaseRecordDao
import com.storehop.app.data.dao.StoreCategoryOrderDao
import com.storehop.app.data.dao.StoreDao
import com.storehop.app.data.entity.Category
import com.storehop.app.data.entity.Item
import com.storehop.app.data.entity.ItemStoreXref
import com.storehop.app.data.entity.PurchaseRecord
import com.storehop.app.data.entity.Store
import com.storehop.app.data.entity.StoreCategoryOrder
import com.storehop.app.data.util.UserSessionProvider
import com.storehop.app.testing.FakeSessionProvider
import com.storehop.app.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Pins SyncEngine's load-bearing behaviors:
 *  - per-uid push: when uid changes, the previous user's push jobs cancel
 *  - successful push -> markPushed flips pendingSync = 0 on the row
 *  - push failure leaves pendingSync = 1 so the row retries on next emission
 *  - signed-out (uid == null) state runs no push jobs
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncEngineTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val firestore: FirebaseFirestore = mockk()
    private val itemDao: ItemDao = mockk(relaxed = true)
    private val categoryDao: CategoryDao = mockk(relaxed = true)
    private val storeDao: StoreDao = mockk(relaxed = true)
    private val xrefDao: ItemStoreXrefDao = mockk(relaxed = true)
    private val scoDao: StoreCategoryOrderDao = mockk(relaxed = true)
    private val purchaseDao: PurchaseRecordDao = mockk(relaxed = true)

    /** Wires firestore.collection("users").document(uid).collection(name) -> docRef. */
    private fun stubFirestorePath(): DocumentReference {
        val docRef: DocumentReference = mockk {
            every { path } returns "users/uid/items/x"
            coEvery { set(any()) } returns Tasks.forResult(null)
        }
        val collRef: CollectionReference = mockk {
            every { document(any<String>()) } returns docRef
        }
        val userDoc: DocumentReference = mockk {
            every { collection(any<String>()) } returns collRef
        }
        val usersColl: CollectionReference = mockk {
            every { document(any<String>()) } returns userDoc
        }
        every { firestore.collection("users") } returns usersColl
        return docRef
    }

    private fun stubAllPushFlowsEmpty() {
        every { itemDao.observePendingPush(any()) } returns flowOf(emptyList())
        every { categoryDao.observePendingPush(any()) } returns flowOf(emptyList())
        every { storeDao.observePendingPush(any()) } returns flowOf(emptyList())
        every { xrefDao.observePendingPush(any()) } returns flowOf(emptyList())
        every { scoDao.observePendingPush(any()) } returns flowOf(emptyList())
        every { purchaseDao.observePendingPush(any()) } returns flowOf(emptyList())
    }

    @Test fun `with a uid the engine starts push jobs that markPushed on success`() = runTest {
        val docRef = stubFirestorePath()
        // Only items has a pending row; the rest emit empty.
        val item = Item(
            id = "milk", name = "Milk", categoryId = null, notes = null,
            quantity = null, isNeeded = true, lastPurchasedAt = null,
            userId = "uid", createdAt = 1L, updatedAt = 1L, deletedAt = null,
        )
        every { itemDao.observePendingPush("uid") } returns flowOf(listOf(item))
        every { categoryDao.observePendingPush(any()) } returns flowOf(emptyList())
        every { storeDao.observePendingPush(any()) } returns flowOf(emptyList())
        every { xrefDao.observePendingPush(any()) } returns flowOf(emptyList())
        every { scoDao.observePendingPush(any()) } returns flowOf(emptyList())
        every { purchaseDao.observePendingPush(any()) } returns flowOf(emptyList())

        val scope = CoroutineScope(SupervisorJob() + mainDispatcher.dispatcher)
        val engine = newEngine(FakeSessionProvider(initial = "uid"), scope)
        engine.start()
        advanceUntilIdle()

        coVerify(exactly = 1) { docRef.set(any()) }
        coVerify(exactly = 1) { itemDao.markPushed("uid", "milk") }
        scope.cancel()
    }

    @Test fun `push failure leaves pendingSync intact for retry`() = runTest {
        val docRef: DocumentReference = mockk {
            every { path } returns "users/uid/items/x"
            coEvery { set(any()) } returns Tasks.forException(RuntimeException("PERMISSION_DENIED"))
        }
        val collRef: CollectionReference = mockk { every { document(any<String>()) } returns docRef }
        val userDoc: DocumentReference = mockk { every { collection(any<String>()) } returns collRef }
        val usersColl: CollectionReference = mockk { every { document(any<String>()) } returns userDoc }
        every { firestore.collection("users") } returns usersColl

        val item = Item(
            id = "milk", name = "Milk", categoryId = null, notes = null,
            quantity = null, isNeeded = true, lastPurchasedAt = null,
            userId = "uid", createdAt = 1L, updatedAt = 1L, deletedAt = null,
        )
        every { itemDao.observePendingPush("uid") } returns flowOf(listOf(item))
        every { categoryDao.observePendingPush(any()) } returns flowOf(emptyList())
        every { storeDao.observePendingPush(any()) } returns flowOf(emptyList())
        every { xrefDao.observePendingPush(any()) } returns flowOf(emptyList())
        every { scoDao.observePendingPush(any()) } returns flowOf(emptyList())
        every { purchaseDao.observePendingPush(any()) } returns flowOf(emptyList())

        val scope = CoroutineScope(SupervisorJob() + mainDispatcher.dispatcher)
        val engine = newEngine(FakeSessionProvider(initial = "uid"), scope)
        engine.start()
        advanceUntilIdle()

        // Critical contract: failed push must NOT call markPushed -- otherwise
        // the row would silently stay at pendingSync=0 and never retry.
        coVerify(exactly = 0) { itemDao.markPushed(any(), any()) }
        scope.cancel()
    }

    @Test fun `null uid skips push jobs entirely`() = runTest {
        every { firestore.collection(any()) } returns mockk(relaxed = true)
        stubAllPushFlowsEmpty()

        val scope = CoroutineScope(SupervisorJob() + mainDispatcher.dispatcher)
        val engine = newEngine(FakeSessionProvider(initial = null), scope)
        engine.start()
        advanceUntilIdle()

        // No DAO observation should fire when there's no user signed in --
        // there's nothing to push.
        coVerify(exactly = 0) { itemDao.observePendingPush(any()) }
        coVerify(exactly = 0) { storeDao.observePendingPush(any()) }
        scope.cancel()
    }

    @Test fun `uid switch cancels the previous user's pending observers`() = runTest {
        stubFirestorePath()
        // Two distinct flows for the two uids; the first must be cancelled
        // once the session uid flips so we don't keep pushing for a stale user.
        val firstFlow = MutableStateFlow<List<Item>>(emptyList())
        val secondFlow = MutableStateFlow<List<Item>>(emptyList())
        every { itemDao.observePendingPush("first-uid") } returns firstFlow
        every { itemDao.observePendingPush("second-uid") } returns secondFlow
        every { categoryDao.observePendingPush(any()) } returns flowOf(emptyList())
        every { storeDao.observePendingPush(any()) } returns flowOf(emptyList())
        every { xrefDao.observePendingPush(any()) } returns flowOf(emptyList())
        every { scoDao.observePendingPush(any()) } returns flowOf(emptyList())
        every { purchaseDao.observePendingPush(any()) } returns flowOf(emptyList())

        val session = FakeSessionProvider(initial = "first-uid")
        val scope = CoroutineScope(SupervisorJob() + mainDispatcher.dispatcher)
        val engine = newEngine(session, scope)
        engine.start()
        advanceUntilIdle()

        session.setUserId("second-uid")
        advanceUntilIdle()

        // After the flip we should see the second uid's observer wired up.
        // The first uid's flow gets cancelled (collectLatest semantics on the
        // outer session flow), so emitting onto firstFlow must NOT trigger a
        // push.
        firstFlow.value = listOf(
            Item(
                id = "ghost", name = "Ghost", categoryId = null, notes = null,
                quantity = null, isNeeded = true, lastPurchasedAt = null,
                userId = "first-uid", createdAt = 1L, updatedAt = 1L, deletedAt = null,
            ),
        )
        advanceUntilIdle()

        coVerify(exactly = 0) { itemDao.markPushed("first-uid", "ghost") }
        scope.cancel()
    }

    private fun newEngine(session: UserSessionProvider, scope: CoroutineScope) = SyncEngine(
        firestore = firestore,
        session = session,
        applicationScope = scope,
        itemDao = itemDao,
        categoryDao = categoryDao,
        storeDao = storeDao,
        xrefDao = xrefDao,
        scoDao = scoDao,
        purchaseDao = purchaseDao,
    )
}
