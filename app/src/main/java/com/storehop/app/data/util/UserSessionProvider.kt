package com.storehop.app.data.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * The currently signed-in user's id, exposed reactively so repositories and the
 * sync engine can swap data sources when the session changes (sign-in,
 * sign-out, anonymous-to-Google upgrade).
 *
 * `userId` is null when the user is signed out (no Firebase session at all).
 * Most production repositories observe this Flow and return `emptyFlow()` /
 * empty results when null, since a signed-out user has no scoped data to read.
 *
 * The `"local-only"` sentinel only existed pre-Firebase. After M2, every
 * row is either tagged with a real Firebase uid or has been migrated by
 * `SignInBootstrapper` to the first uid the device sees.
 */
interface UserSessionProvider {
    val userId: StateFlow<String?>
    fun currentUserId(): String? = userId.value
}

/**
 * Test-only stand-in for [UserSessionProvider]. Exposes a fixed uid (or null)
 * so unit tests can construct repositories without standing up FirebaseAuth.
 *
 * Production wiring lives in [com.storehop.app.auth.FirebaseAuthSessionProvider].
 */
class LocalOnlyUserSessionProvider @Inject constructor() : UserSessionProvider {
    private val _userId = MutableStateFlow<String?>(LOCAL_ONLY)
    override val userId: StateFlow<String?> = _userId.asStateFlow()

    companion object {
        const val LOCAL_ONLY: String = "local-only"
    }
}
