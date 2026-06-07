package com.storehop.app.di

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.firestoreSettings
import com.google.firebase.firestore.persistentCacheSettings
import com.google.firebase.storage.FirebaseStorage
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Production Firestore singleton. Auth is provided by [AppModule].
 *
 * We enable Firestore's **persistent local cache** even though Room is the app's
 * source of truth — the cache helps the snapshot-listener replay on cold start
 * and bridges short network outages without us having to re-implement the queue.
 * It does NOT make Firestore the source of truth; the SyncEngine (M4+) still
 * reads/writes Room first.
 */
@Module
@InstallIn(SingletonComponent::class)
object FirebaseModule {

    @Provides
    @Singleton
    fun provideFirestore(): FirebaseFirestore =
        FirebaseFirestore.getInstance().apply {
            firestoreSettings = firestoreSettings {
                setLocalCacheSettings(
                    persistentCacheSettings {
                        // Default 100 MB; bump to 250 MB so a heavy user doesn't
                        // start evicting cached docs while the snapshot listener
                        // is still warming up on cold start.
                        setSizeBytes(250L * 1024 * 1024)
                    },
                )
            }
        }

    @Provides
    @Singleton
    fun provideFirebaseStorage(): FirebaseStorage = FirebaseStorage.getInstance()
}
