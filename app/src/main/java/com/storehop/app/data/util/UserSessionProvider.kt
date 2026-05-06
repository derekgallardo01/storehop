package com.storehop.app.data.util

import javax.inject.Inject

interface UserSessionProvider {
    fun currentUserId(): String
}

/**
 * Pre-sign-in placeholder. Always returns the [LOCAL_ONLY] sentinel.
 * After Google Sign-In lands, swap the @Binds in AppModule for an
 * implementation that reads from the active Firebase auth session.
 */
class LocalOnlyUserSessionProvider @Inject constructor() : UserSessionProvider {
    override fun currentUserId(): String = LOCAL_ONLY

    companion object {
        const val LOCAL_ONLY: String = "local-only"
    }
}
