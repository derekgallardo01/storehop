package com.storehop.app.testing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.storehop.app.data.db.DatabaseSeeder
import com.storehop.app.data.db.StorehopDatabase

/**
 * Builds an in-memory [StorehopDatabase] for unit tests. Pass `seeded = true`
 * to wire the [DatabaseSeeder] callback (default off so individual tests start
 * with empty tables and only seed when they need to).
 */
internal fun createTestDb(seeded: Boolean = false): StorehopDatabase {
    val context: Context = ApplicationProvider.getApplicationContext()
    val builder = Room.inMemoryDatabaseBuilder(context, StorehopDatabase::class.java)
        .allowMainThreadQueries()
    if (seeded) {
        builder.addCallback(DatabaseSeeder(context))
    }
    return builder.build()
}

internal const val TEST_USER_ID = "test-user-A"
internal const val OTHER_USER_ID = "test-user-B"
