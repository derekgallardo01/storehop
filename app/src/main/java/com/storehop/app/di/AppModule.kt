package com.storehop.app.di

import com.storehop.app.data.util.IdGenerator
import com.storehop.app.data.util.UserSessionProvider
import com.storehop.app.data.util.UuidIdGenerator
import com.storehop.app.data.util.LocalOnlyUserSessionProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindsModule {

    @Binds
    @Singleton
    abstract fun bindIdGenerator(impl: UuidIdGenerator): IdGenerator

    @Binds
    @Singleton
    abstract fun bindUserSessionProvider(impl: LocalOnlyUserSessionProvider): UserSessionProvider
}
