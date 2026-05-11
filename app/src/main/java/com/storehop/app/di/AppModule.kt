package com.storehop.app.di

import com.google.firebase.auth.FirebaseAuth
import com.storehop.app.auth.FirebaseAuthSessionProvider
import com.storehop.app.data.util.HouseholdSessionProvider
import com.storehop.app.data.util.IdGenerator
import com.storehop.app.data.util.UserBackedHouseholdSessionProvider
import com.storehop.app.data.util.UserSessionProvider
import com.storehop.app.data.util.UuidIdGenerator
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()

    /**
     * App-lifetime scope for fire-and-forget background work (sign-in
     * bootstrapping, sync engine, etc.). Uses [SupervisorJob] so a failure
     * in one job doesn't cancel siblings.
     */
    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideFirebaseAuth(): FirebaseAuth = FirebaseAuth.getInstance()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindsModule {

    @Binds
    @Singleton
    abstract fun bindIdGenerator(impl: UuidIdGenerator): IdGenerator

    /**
     * Production binding: every `userId` we see is a Firebase uid.
     * The pre-Firebase `LocalOnlyUserSessionProvider` survives only as a
     * test helper -- nothing in the production graph references it.
     */
    @Binds
    @Singleton
    abstract fun bindUserSessionProvider(
        impl: FirebaseAuthSessionProvider,
    ): UserSessionProvider

    /**
     * v0.7.0 Phase 1.x binding: the active household for every user is
     * their own uid (a "household of one") until the first-launch bootstrap
     * (Phase 2) replaces this with a real DAO-backed lookup. Wrapping
     * [UserSessionProvider] here keeps every repository's runtime
     * behaviour identical to v0.6.x while the codebase migrates from
     * `userId`-scoped to `householdId`-scoped queries.
     */
    @Binds
    @Singleton
    abstract fun bindHouseholdSessionProvider(
        impl: UserBackedHouseholdSessionProvider,
    ): HouseholdSessionProvider
}
