package com.storehop.app.billing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.android.billingclient.api.Purchase
import com.google.common.truth.Truth.assertThat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.FirebaseUserMetadata
import com.storehop.app.data.prefs.UserPreferencesRepository
import com.storehop.app.testing.FakeSessionProvider
import com.storehop.app.testing.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Pins the load-bearing entitlement logic added in v0.8 + the v0.8 follow-
 * up VIP allowlist:
 *  - VIP email match grants `LegacyUser` regardless of account age.
 *  - Pre-v0.8 account timestamp grants `LegacyUser`.
 *  - Post-v0.8 non-VIP account stays `NotEntitled`.
 *  - The legacy flag re-evaluates on every uid change (fixes the
 *    sticky-flag bug where a non-VIP signing in on a VIP device would
 *    inherit Premium).
 *  - A valid purchase from BillingManager promotes to `Premium` even
 *    when the legacy flag is also true (purchase wins for clarity).
 *
 * Test infra: [EntitlementRepository.start] launches two long-lived
 * collectors. We pass [TestScope.backgroundScope] as the
 * applicationScope so they auto-cancel at test-body end and `runTest`
 * doesn't hang waiting for them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EntitlementRepositoryTest {

    @get:Rule val tempFolder = TemporaryFolder()
    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val fixedClock: Clock = Clock.fixed(Instant.ofEpochMilli(2_000_000_000_000L), ZoneOffset.UTC)
    private lateinit var dataStoreFile: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var userPrefs: UserPreferencesRepository
    private val billingPurchases = MutableStateFlow<List<Purchase>>(emptyList())
    private lateinit var billingManager: BillingManager

    @Before fun setup() {
        dataStoreFile = tempFolder.newFile("prefs.preferences_pb")
        dataStoreFile.delete()
        billingManager = mockk<BillingManager>(relaxed = true).also {
            every { it.purchases } returns billingPurchases
        }
    }

    @After fun tearDown() {
        dataStoreFile.delete()
    }

    /** Inits the DataStore + repo on the test scope so IO advances with
     *  the test scheduler. Call from inside each test body after `runTest`
     *  enters. */
    private fun TestScope.bindPrefs() {
        dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { dataStoreFile },
        )
        userPrefs = UserPreferencesRepository(dataStore, fixedClock)
    }

    // MARK: - VIP allowlist (v0.8 follow-up)

    @Test fun `VIP email grants LegacyUser even when account creationTimestamp is post-v0_8`() = runTest(UnconfinedTestDispatcher()) {
        val auth = mockAuth(
            email = "mikehaynes@gmail.com",
            creationTimestamp = EntitlementRepository.V0_8_RELEASE_DATE_MS + 1_000_000L,
        )
        val repo = newRepo(auth)
        repo.start()
        advanceUntilIdle()

        assertThat(userPrefs.legacyUserGranted.first()).isTrue()
        assertThat(repo.entitlement.value).isEqualTo(Entitlement.LegacyUser)
    }

    @Test fun `Derek's dev email is in the VIP allowlist`() = runTest(UnconfinedTestDispatcher()) {
        val auth = mockAuth(
            email = "derekgallardo01@gmail.com",
            creationTimestamp = EntitlementRepository.V0_8_RELEASE_DATE_MS + 1L,
        )
        val repo = newRepo(auth)
        repo.start()
        advanceUntilIdle()
        assertThat(repo.entitlement.value).isEqualTo(Entitlement.LegacyUser)
    }

    @Test fun `Amanda's email is in the VIP allowlist`() = runTest(UnconfinedTestDispatcher()) {
        val auth = mockAuth(
            email = "amandafrost79@gmail.com",
            creationTimestamp = EntitlementRepository.V0_8_RELEASE_DATE_MS + 1L,
        )
        val repo = newRepo(auth)
        repo.start()
        advanceUntilIdle()
        assertThat(repo.entitlement.value).isEqualTo(Entitlement.LegacyUser)
    }

    @Test fun `email comparison is case-insensitive`() = runTest(UnconfinedTestDispatcher()) {
        val auth = mockAuth(
            email = "MikeHaynes@Gmail.Com",
            creationTimestamp = EntitlementRepository.V0_8_RELEASE_DATE_MS + 1L,
        )
        val repo = newRepo(auth)
        repo.start()
        advanceUntilIdle()
        assertThat(repo.entitlement.value).isEqualTo(Entitlement.LegacyUser)
    }

    // MARK: - Date-based grandfather

    @Test fun `pre-v0_8 account grants LegacyUser even without VIP email`() = runTest(UnconfinedTestDispatcher()) {
        val auth = mockAuth(
            email = "random@example.com",
            creationTimestamp = EntitlementRepository.V0_8_RELEASE_DATE_MS - 1_000_000L,
        )
        val repo = newRepo(auth)
        repo.start()
        advanceUntilIdle()
        assertThat(repo.entitlement.value).isEqualTo(Entitlement.LegacyUser)
    }

    @Test fun `post-v0_8 non-VIP account stays NotEntitled`() = runTest(UnconfinedTestDispatcher()) {
        val auth = mockAuth(
            email = "random@example.com",
            creationTimestamp = EntitlementRepository.V0_8_RELEASE_DATE_MS + 1_000_000L,
        )
        val repo = newRepo(auth)
        repo.start()
        advanceUntilIdle()
        assertThat(repo.entitlement.value).isEqualTo(Entitlement.NotEntitled)
    }

    @Test fun `anonymous user with no email and no metadata stays NotEntitled`() = runTest(UnconfinedTestDispatcher()) {
        val auth = mockAuth(email = null, creationTimestamp = null)
        val repo = newRepo(auth)
        repo.start()
        advanceUntilIdle()
        assertThat(repo.entitlement.value).isEqualTo(Entitlement.NotEntitled)
    }

    // MARK: - Sticky-flag bug fix (v0.8 follow-up)

    @Test fun `flag re-evaluates on uid change - VIP then non-VIP correctly flips back to NotEntitled`() = runTest(UnconfinedTestDispatcher()) {
        bindPrefs()
        // Pre-seed: legacy flag already true (left over from a previous
        // VIP sign-in on this device).
        userPrefs.setLegacyUserGranted(true)
        userPrefs.setLegacyCheckDoneForUid("vip-uid")

        val auth = mockAuth(
            email = "random@example.com",
            creationTimestamp = EntitlementRepository.V0_8_RELEASE_DATE_MS + 1L,
        )
        val repo = EntitlementRepository(
            billingManager = billingManager,
            sessionProvider = FakeSessionProvider("new-uid"),
            auth = auth,
            userPrefs = userPrefs,
            applicationScope = backgroundScope,
        )
        repo.start()
        advanceUntilIdle()

        // The check ran for "new-uid" and recomputed → flag flipped back.
        assertThat(userPrefs.legacyUserGranted.first()).isFalse()
        assertThat(repo.entitlement.value).isEqualTo(Entitlement.NotEntitled)
    }

    // MARK: - Purchase precedence

    @Test fun `valid Play purchase grants Premium even when legacy flag is also true`() = runTest(UnconfinedTestDispatcher()) {
        val auth = mockAuth(
            email = "mikehaynes@gmail.com",
            creationTimestamp = EntitlementRepository.V0_8_RELEASE_DATE_MS - 1L,
        )
        billingPurchases.value = listOf(mockPurchase(state = Purchase.PurchaseState.PURCHASED))
        val repo = newRepo(auth)
        repo.start()
        advanceUntilIdle()

        // Purchase precedence: Premium wins over LegacyUser.
        assertThat(repo.entitlement.value).isEqualTo(Entitlement.Premium)
    }

    @Test fun `pending Play purchase does NOT grant Premium`() = runTest(UnconfinedTestDispatcher()) {
        val auth = mockAuth(
            email = "random@example.com",
            creationTimestamp = EntitlementRepository.V0_8_RELEASE_DATE_MS + 1L,
        )
        billingPurchases.value = listOf(mockPurchase(state = Purchase.PurchaseState.PENDING))
        val repo = newRepo(auth)
        repo.start()
        advanceUntilIdle()
        assertThat(repo.entitlement.value).isEqualTo(Entitlement.NotEntitled)
    }

    // MARK: - Helpers

    /** Constructs the repo and the DataStore-backed UserPreferencesRepository
     *  together. Must run inside a TestScope so DataStore IO lives on the
     *  test scheduler. */
    private fun TestScope.newRepo(auth: FirebaseAuth): EntitlementRepository {
        bindPrefs()
        return EntitlementRepository(
            billingManager = billingManager,
            sessionProvider = FakeSessionProvider("test-uid"),
            auth = auth,
            userPrefs = userPrefs,
            applicationScope = backgroundScope,
        )
    }

    private fun mockAuth(email: String?, creationTimestamp: Long?): FirebaseAuth {
        val metadata: FirebaseUserMetadata? = creationTimestamp?.let {
            mockk { every { this@mockk.creationTimestamp } returns it }
        }
        val user: FirebaseUser? = if (email != null || metadata != null) {
            mockk {
                every { this@mockk.email } returns email
                every { this@mockk.metadata } returns metadata
            }
        } else null
        return mockk { every { currentUser } returns user }
    }

    private fun mockPurchase(state: Int): Purchase = mockk {
        every { purchaseState } returns state
        every { products } returns listOf(BillingManager.PRODUCT_ID_PREMIUM)
    }
}
