package com.storehop.app.data.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v0.7.0 multi-user: the *active household* the user is currently scoped to.
 *
 * Every repository query is filtered by `householdId` going forward. For
 * single-user households (everyone pre-v0.7.0 + everyone post-migration
 * who hasn't joined another household) the value equals the user's uid,
 * so the swap from `userId`-scoped to `householdId`-scoped queries is
 * behaviour-preserving.
 *
 * When the user accepts an invite later (Phase 3) this flow flips to the
 * new household's id and every observing repository re-queries against
 * the shared household path. The flip should be gated through the same
 * pull-then-publish pattern [com.storehop.app.auth.FirebaseAuthSessionProvider]
 * uses for uid changes, so observers never see an inconsistent
 * (uid, householdId) pair.
 *
 * `householdId` is null when the user is signed out / not yet bootstrapped
 * (matches `UserSessionProvider.userId` semantics).
 */
interface HouseholdSessionProvider {
    val householdId: StateFlow<String?>
    fun currentHouseholdId(): String? = householdId.value
}

/**
 * Production implementation for v0.7.0 Phase 1.x: until the first-launch
 * bootstrap that creates real household-membership rows lands (Phase 2),
 * the active household for every user is simply their own uid — a
 * "household of one". Wrapping [UserSessionProvider] this way means the
 * runtime behaviour is identical to v0.6.x (same row set) while the rest
 * of the codebase migrates from `userId`-scoped to `householdId`-scoped
 * queries.
 *
 * Phase 2 will replace this impl with one that reads the user's active
 * membership from [com.storehop.app.data.dao.HouseholdMemberDao] and falls
 * back to "households of one" only when no membership row exists yet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class UserBackedHouseholdSessionProvider @Inject constructor(
    session: UserSessionProvider,
) : HouseholdSessionProvider {
    // The household id for a single-user household equals the user's uid.
    // No transformation needed; we just expose `session.userId` under the
    // `householdId` label so repositories can depend on the household
    // abstraction without caring about the v0.7.0 transitional details.
    override val householdId: StateFlow<String?> = session.userId
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
