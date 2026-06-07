package com.storehop.app.data.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * v0.7.0 multi-user: the *active household* the user is currently scoped to.
 *
 * Every repository query is filtered by `householdId` going forward. For
 * single-user households (everyone post-migration who hasn't joined another
 * household via invite) the value equals the user's uid, so the swap from
 * `userId`-scoped to `householdId`-scoped queries is behaviour-preserving.
 *
 * The production implementation is
 * [com.storehop.app.auth.FirebaseAuthSessionProvider] — the same Singleton
 * that publishes [UserSessionProvider.userId]. Both ids are updated together
 * after sync gating so observers never see an inconsistent (uid, householdId)
 * pair. When the user accepts an invite (Phase 3) the provider's
 * `_householdId` flow flips to the new household's id and every observing
 * repository re-queries against the shared household path.
 *
 * `householdId` is null when the user is signed out / not yet bootstrapped
 * (matches `UserSessionProvider.userId` semantics).
 */
interface HouseholdSessionProvider {
    val householdId: StateFlow<String?>
    fun currentHouseholdId(): String? = householdId.value
}

/**
 * v0.7.0 Phase 3: write-side hook used by `HouseholdRepository` after an
 * invite-accept or leave-household action. Calling `switchToHousehold`
 * re-runs the same sync gate the auth listener uses (peek + pull or
 * claim) against the new household and then publishes it via
 * [HouseholdSessionProvider.householdId]. Implemented by
 * [com.storehop.app.auth.FirebaseAuthSessionProvider] in production.
 */
interface HouseholdSwitcher {
    suspend fun switchToHousehold(newHouseholdId: String)
}

/**
 * Test-only stand-in. Mirrors [LocalOnlyUserSessionProvider] for the
 * household abstraction so unit tests can construct repositories that
 * filter by household without standing up auth.
 */
class FakeHouseholdSessionProvider(
    initial: String? = LocalOnlyUserSessionProvider.LOCAL_ONLY,
) : HouseholdSessionProvider {
    private val _householdId = MutableStateFlow(initial)
    override val householdId: StateFlow<String?> = _householdId.asStateFlow()

    fun setHouseholdId(value: String?) {
        _householdId.value = value
    }
}
