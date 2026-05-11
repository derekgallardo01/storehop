package com.storehop.app.di

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.storehop.app.data.util.FakeHouseholdSessionProvider
import com.storehop.app.data.util.HouseholdSessionProvider
import com.storehop.app.data.util.HouseholdSwitcher
import com.storehop.app.data.util.IdGenerator
import com.storehop.app.data.util.LocalOnlyUserSessionProvider
import com.storehop.app.data.util.UserSessionProvider
import com.storehop.app.data.util.UuidIdGenerator
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Clock
import javax.inject.Singleton

/**
 * Replaces [FirebaseModule] with relaxed mocks. The E2E tests don't talk
 * to a real Firebase backend -- the SyncEngine is structurally satisfied
 * but does no real work because its FirebaseFirestore + FirebaseStorage
 * collaborators are mocks.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [FirebaseModule::class],
)
object TestFirebaseModule {

    @Provides
    @Singleton
    fun provideFirestore(): FirebaseFirestore = mockk(relaxed = true)

    @Provides
    @Singleton
    fun provideFirebaseStorage(): FirebaseStorage = mockk(relaxed = true)
}

/**
 * Replaces [AppModule]. Same Clock + ApplicationScope semantics, but the
 * FirebaseAuth provider returns a relaxed mock so we never call
 * `FirebaseApp.configure()` (HiltTestApplication doesn't run
 * StorehopApplication.onCreate, so Firebase wouldn't be initialized).
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [AppModule::class],
)
object TestAppModule {

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()

    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideFirebaseAuth(): FirebaseAuth = mockk(relaxed = true)
}

/**
 * Replaces [AppBindsModule]. Binds the test-only
 * [LocalOnlyUserSessionProvider] (always emits `"local-only"`) so the
 * data layer has a stable uid without going through FirebaseAuth's
 * sign-in dance. UuidIdGenerator binding is unchanged.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [AppBindsModule::class],
)
abstract class TestAppBindsModule {

    @Binds
    @Singleton
    abstract fun bindIdGenerator(impl: UuidIdGenerator): IdGenerator

    @Binds
    @Singleton
    abstract fun bindUserSessionProvider(
        impl: LocalOnlyUserSessionProvider,
    ): UserSessionProvider
}

/**
 * v0.7.0: bind the new HouseholdSessionProvider + HouseholdSwitcher
 * surfaces with a `FakeHouseholdSessionProvider` instance pre-seeded to
 * `local-only` (the same sentinel `LocalOnlyUserSessionProvider` emits).
 * Tests run as a single-member household where uid == householdId, so
 * every household-scoped query lands the same row set the userId-scoped
 * queries did pre-v0.7.0. E2E tests that need to simulate a household
 * switch can grab this instance via Hilt and call `setHouseholdId`.
 *
 * Uses `@InstallIn` (not `@TestInstallIn`) because there's no
 * production module to replace — the production HouseholdSessionProvider
 * + HouseholdSwitcher bindings live on `AppBindsModule` which the
 * sibling `TestAppBindsModule` above replaces. This module adds the
 * test-only equivalents into the same SingletonComponent without
 * conflicting.
 */
@Module
@InstallIn(SingletonComponent::class)
object TestHouseholdSessionModule {

    @Provides
    @Singleton
    fun provideFakeHouseholdSession(): FakeHouseholdSessionProvider =
        FakeHouseholdSessionProvider(initial = LocalOnlyUserSessionProvider.LOCAL_ONLY)

    @Provides
    fun provideHouseholdSession(fake: FakeHouseholdSessionProvider): HouseholdSessionProvider =
        fake

    /**
     * The instrumented tests don't drive an invite-accept / leave flow
     * (no Firestore round-trip in a mock'd setup), so we bind a no-op
     * HouseholdSwitcher that satisfies the DI graph without actually
     * doing anything. HouseholdRepository tests cover the switch logic
     * with a mock at the JVM-unit-test level.
     */
    @Provides
    @Singleton
    fun provideHouseholdSwitcher(): HouseholdSwitcher = NoOpHouseholdSwitcher()
}

private class NoOpHouseholdSwitcher : HouseholdSwitcher {
    override suspend fun switchToHousehold(newHouseholdId: String) { /* no-op */ }
}
