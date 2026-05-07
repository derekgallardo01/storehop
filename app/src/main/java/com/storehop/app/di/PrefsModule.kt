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
import javax.inject.Singleton

/**
 * Provides the single Preferences DataStore the app uses for non-synced
 * user prefs (theme mode etc). Backed by an extension property on Context
 * which guarantees one instance per process per file name.
 */
private val Context.userPreferencesDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "user_preferences")

@Module
@InstallIn(SingletonComponent::class)
object PrefsModule {

    @Provides
    @Singleton
    fun provideUserPreferencesDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.userPreferencesDataStore
}
