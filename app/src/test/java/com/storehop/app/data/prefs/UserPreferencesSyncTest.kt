package com.storehop.app.data.prefs

import com.google.android.gms.tasks.Tasks
import com.google.common.truth.Truth.assertThat
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Pins [UserPreferencesSync.reconcile]'s three load-bearing branches:
 *  1. Cloud absent → push local.
 *  2. Cloud older than local → push local.
 *  3. Cloud newer than local → apply cloud → DataStore.
 *
 * Plus a no-op equality branch and the "blank uid" guard. Firestore is
 * mocked so the tests are pure JVM (Robolectric not needed).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserPreferencesSyncTest {

    private val prefs: UserPreferencesRepository = mockk(relaxed = true)
    private val firestore: FirebaseFirestore = mockk()
    private val docRef: DocumentReference = mockk()
    private val collRef: CollectionReference = mockk { every { document(any<String>()) } returns docRef }
    init { every { firestore.collection("userPrefs") } returns collRef }

    private fun newSync(testScope: TestScope) = UserPreferencesSync(
        firestore = firestore,
        prefs = prefs,
        applicationScope = testScope,
    )

    private val sampleLocal = UserPreferencesSnapshot(
        themeMode = ThemeMode.DARK,
        localeTag = "pt-PT",
        showPurchased = false,
        shopAtStoreSortMode = SortMode.ALPHABETIC,
        itemsListSortMode = SortMode.CATEGORY,
        updatedAt = 100_000L,
    )

    @Test fun `reconcile blank uid is a no-op (no firestore call, no apply)`() = runTest(UnconfinedTestDispatcher()) {
        val sync = newSync(this)
        sync.reconcile("")
        advanceUntilIdle()
        coVerify(exactly = 0) { docRef.get() }
        coVerify(exactly = 0) { prefs.applyRemoteSnapshot(any()) }
    }

    @Test fun `reconcile pushes local when cloud is absent`() = runTest(UnconfinedTestDispatcher()) {
        val snap: DocumentSnapshot = mockk { every { exists() } returns false }
        coEvery { docRef.get() } returns Tasks.forResult(snap)
        coEvery { prefs.currentSnapshot() } returns sampleLocal
        val captured = slot<UserPreferencesDto>()
        coEvery { docRef.set(capture(captured)) } returns Tasks.forResult(null)

        val sync = newSync(this)
        sync.reconcile("uid-mike")
        advanceUntilIdle()

        coVerify(exactly = 1) { docRef.set(any()) }
        coVerify(exactly = 0) { prefs.applyRemoteSnapshot(any()) }
        // The DTO carried the local snapshot's content (LWW updatedAt
        // preserved so a later device's sync compares against the same value).
        assertThat(captured.captured.themeMode).isEqualTo("DARK")
        assertThat(captured.captured.localeTag).isEqualTo("pt-PT")
        assertThat(captured.captured.updatedAt).isEqualTo(100_000L)
    }

    @Test fun `reconcile pulls cloud and applies when cloud is newer`() = runTest(UnconfinedTestDispatcher()) {
        val cloudDto = UserPreferencesDto(
            themeMode = "LIGHT",
            localeTag = "es",
            showPurchased = true,
            shopAtStoreSortMode = "CATEGORY",
            itemsListSortMode = "ALPHABETIC",
            updatedAt = 200_000L, // newer than local 100_000L
        )
        val snap: DocumentSnapshot = mockk {
            every { exists() } returns true
            every { toObject(UserPreferencesDto::class.java) } returns cloudDto
        }
        coEvery { docRef.get() } returns Tasks.forResult(snap)
        coEvery { prefs.currentSnapshot() } returns sampleLocal
        val capturedSnapshot = slot<UserPreferencesSnapshot>()
        coEvery { prefs.applyRemoteSnapshot(capture(capturedSnapshot)) } returns Unit

        val sync = newSync(this)
        sync.reconcile("uid-mike")
        advanceUntilIdle()

        coVerify(exactly = 1) { prefs.applyRemoteSnapshot(any()) }
        coVerify(exactly = 0) { docRef.set(any()) }
        // Applied snapshot preserves the cloud's updatedAt (load-bearing for LWW).
        assertThat(capturedSnapshot.captured.themeMode).isEqualTo(ThemeMode.LIGHT)
        assertThat(capturedSnapshot.captured.localeTag).isEqualTo("es")
        assertThat(capturedSnapshot.captured.updatedAt).isEqualTo(200_000L)
    }

    @Test fun `reconcile pushes local when cloud is older`() = runTest(UnconfinedTestDispatcher()) {
        val cloudDto = UserPreferencesDto(updatedAt = 50_000L) // older than local 100_000L
        val snap: DocumentSnapshot = mockk {
            every { exists() } returns true
            every { toObject(UserPreferencesDto::class.java) } returns cloudDto
        }
        coEvery { docRef.get() } returns Tasks.forResult(snap)
        coEvery { prefs.currentSnapshot() } returns sampleLocal
        coEvery { docRef.set(any()) } returns Tasks.forResult(null)

        val sync = newSync(this)
        sync.reconcile("uid-mike")
        advanceUntilIdle()

        coVerify(exactly = 1) { docRef.set(any()) }
        coVerify(exactly = 0) { prefs.applyRemoteSnapshot(any()) }
    }

    @Test fun `reconcile is no-op when cloud equals local updatedAt`() = runTest(UnconfinedTestDispatcher()) {
        val cloudDto = UserPreferencesDto(updatedAt = 100_000L) // same as local
        val snap: DocumentSnapshot = mockk {
            every { exists() } returns true
            every { toObject(UserPreferencesDto::class.java) } returns cloudDto
        }
        coEvery { docRef.get() } returns Tasks.forResult(snap)
        coEvery { prefs.currentSnapshot() } returns sampleLocal

        val sync = newSync(this)
        sync.reconcile("uid-mike")
        advanceUntilIdle()

        coVerify(exactly = 0) { docRef.set(any()) }
        coVerify(exactly = 0) { prefs.applyRemoteSnapshot(any()) }
    }

    @Test fun `reconcile swallows firestore errors (network down etc) without crashing`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { docRef.get() } returns Tasks.forException(RuntimeException("network"))
        coEvery { prefs.currentSnapshot() } returns sampleLocal

        val sync = newSync(this)
        // Should not throw. The push loop still starts so the next prefs
        // change will retry.
        sync.reconcile("uid-mike")
        advanceUntilIdle()
    }

    @Test fun `flushPending pushes the current snapshot synchronously`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { prefs.currentSnapshot() } returns sampleLocal
        coEvery { docRef.set(any()) } returns Tasks.forResult(null)

        val sync = newSync(this)
        sync.flushPending("uid-mike")
        advanceUntilIdle()

        coVerify(exactly = 1) { docRef.set(any()) }
    }

    @Test fun `flushPending blank uid is a no-op`() = runTest(UnconfinedTestDispatcher()) {
        val sync = newSync(this)
        sync.flushPending("")
        advanceUntilIdle()
        coVerify(exactly = 0) { docRef.set(any()) }
    }
}
