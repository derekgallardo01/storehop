package com.storehop.app.di

import android.content.Context
import com.google.firebase.analytics.FirebaseAnalytics
import com.storehop.app.analytics.AnalyticsService
import com.storehop.app.analytics.AnalyticsServiceImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Analytics wiring: the [FirebaseAnalytics] SDK singleton + the
 * [AnalyticsService] interface -> impl binding. PostHog is set up inside
 * [AnalyticsServiceImpl.start] (from a config key), so it needs no provider here.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AnalyticsModule {

    @Binds
    @Singleton
    abstract fun bindAnalyticsService(impl: AnalyticsServiceImpl): AnalyticsService

    companion object {
        @Provides
        @Singleton
        fun provideFirebaseAnalytics(@ApplicationContext context: Context): FirebaseAnalytics =
            FirebaseAnalytics.getInstance(context)
    }
}
