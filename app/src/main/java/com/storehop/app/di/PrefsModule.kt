package com.storehop.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Two separate DataStore files so corruption of one can't poison the other:
 *  - `user_preferences` for theme + locale (the "what the user picked" layer)
 *  - `pull_state` for per-uid sync state (the "what the cloud-sync engine
 *    knows" layer)
 *
 * Each is backed by an extension property so Android guarantees a single
 * instance per process per file name.
 */
private val Context.userPreferencesDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "user_preferences")

private val Context.pullStateDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "pull_state")

/** Hilt qualifier for the pull-state DataStore so the two providers can coexist. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PullStatePrefs

@Module
@InstallIn(SingletonComponent::class)
object PrefsModule {

    @Provides
    @Singleton
    fun provideUserPreferencesDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.userPreferencesDataStore

    @Provides
    @Singleton
    @PullStatePrefs
    fun providePullStateDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.pullStateDataStore
}
