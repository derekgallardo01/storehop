package com.storehop.app.ui.settings

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storehop.app.billing.BillingManager
import com.storehop.app.billing.Entitlement
import com.storehop.app.billing.EntitlementRepository
import com.storehop.app.billing.isUnlocked
import com.storehop.app.data.entity.HouseholdMember
import com.storehop.app.data.repository.HouseholdRepository
import com.storehop.app.data.repository.InviteCode
import com.storehop.app.data.repository.InviteResult
import com.storehop.app.data.util.HouseholdSessionProvider
import com.storehop.app.data.util.UserSessionProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * v0.7.0 Phase 3b: drives the Settings → Household screen.
 *
 * State combines three sources: current uid, current household id, and the
 * live members list. UI events (generate invite, join with code, leave)
 * delegate to [HouseholdRepository] and surface results through transient
 * fields on [HouseholdUiState] for the UI to render banners + dialogs.
 *
 * Loose error handling: the invite-accept failure cases are typed
 * (NotFound, Expired, AlreadyUsed, Failed) so the UI can render a precise
 * inline error instead of a generic "something went wrong" snackbar.
 */
@HiltViewModel
class HouseholdViewModel @Inject constructor(
    private val repository: HouseholdRepository,
    private val entitlementRepo: EntitlementRepository,
    private val billingManager: BillingManager,
    userSession: UserSessionProvider,
    householdSession: HouseholdSessionProvider,
) : ViewModel() {

    private val _uiEvent = MutableStateFlow<HouseholdUiEvent?>(null)
    val uiEvent: StateFlow<HouseholdUiEvent?> = _uiEvent.asStateFlow()

    /** v0.8: live entitlement state. Generate-Invite button reads this
     *  to decide whether to call [generateInvite] or [launchPurchase]. */
    val entitlement: StateFlow<Entitlement> = entitlementRepo.entitlement

    /** Play-localized formatted price for the upsell button — e.g.
     *  "$7.99" / "€7,99". Null until BillingClient finishes connecting.
     *  Used to render "Unlock for $7.99" instead of hardcoding the
     *  currency on the client. */
    val premiumPrice: StateFlow<String?> = billingManager.productDetails
        .map { it?.oneTimePurchaseOfferDetails?.formattedPrice }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = null,
        )

    val uiState: StateFlow<HouseholdUiState> = combine(
        userSession.userId,
        householdSession.householdId,
        repository.observeMembers(),
    ) { uid, hid, members ->
        HouseholdUiState(
            currentUid = uid,
            householdId = hid,
            members = members,
            isPersonalHousehold = uid != null && hid == uid,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = HouseholdUiState(),
    )

    /**
     * v0.8: launch the Play purchase sheet for the Premium IAP. Wired
     * to the gated "Generate Invite" button when the user is not yet
     * entitled. Activity is required by Play to anchor the sheet's
     * window.
     */
    fun launchPurchase(activity: Activity) {
        billingManager.launchPurchase(activity)
    }

    /**
     * v0.8: combined gate — calls [generateInvite] if entitled, falls
     * back to launching the purchase sheet otherwise. Simplifies the
     * Composable call site (single onClick, VM handles routing).
     */
    fun onGenerateInviteTapped(activity: Activity) {
        if (entitlement.value.isUnlocked) {
            generateInvite()
        } else {
            launchPurchase(activity)
        }
    }

    fun generateInvite() {
        viewModelScope.launch {
            runCatching { repository.generateInvite() }
                .onSuccess { invite -> _uiEvent.value = HouseholdUiEvent.InviteGenerated(invite) }
                .onFailure { e -> _uiEvent.value = HouseholdUiEvent.Failed(e.message ?: "unknown error") }
        }
    }

    fun acceptInvite(rawToken: String) {
        // Normalize: uppercase + strip whitespace + drop visually-ambiguous
        // chars that aren't in the alphabet (in case the user mistyped).
        val token = rawToken.uppercase().filter { it.isLetterOrDigit() }
        if (token.length != INVITE_TOKEN_LENGTH) {
            _uiEvent.value = HouseholdUiEvent.InvalidTokenFormat
            return
        }
        viewModelScope.launch {
            when (val result = repository.acceptInvite(token)) {
                is InviteResult.Success -> _uiEvent.value = HouseholdUiEvent.JoinedHousehold(result.householdId)
                InviteResult.NotFound -> _uiEvent.value = HouseholdUiEvent.InviteNotFound
                InviteResult.Expired -> _uiEvent.value = HouseholdUiEvent.InviteExpired
                InviteResult.AlreadyUsed -> _uiEvent.value = HouseholdUiEvent.InviteAlreadyUsed
                is InviteResult.Failed -> _uiEvent.value =
                    HouseholdUiEvent.Failed(result.cause.message ?: "unknown error")
            }
        }
    }

    fun leaveHousehold() {
        viewModelScope.launch {
            runCatching { repository.leaveHousehold() }
                .onSuccess { _uiEvent.value = HouseholdUiEvent.LeftHousehold }
                .onFailure { e -> _uiEvent.value = HouseholdUiEvent.Failed(e.message ?: "unknown error") }
        }
    }

    fun acknowledgeEvent() {
        _uiEvent.value = null
    }

    companion object {
        private const val INVITE_TOKEN_LENGTH = 8
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** Snapshot of the household screen's combined data. */
data class HouseholdUiState(
    val currentUid: String? = null,
    val householdId: String? = null,
    val members: List<HouseholdMember> = emptyList(),
    val isPersonalHousehold: Boolean = true,
)

/** One-shot outcomes the UI shows as a snackbar or dialog. */
sealed class HouseholdUiEvent {
    data class InviteGenerated(val invite: InviteCode) : HouseholdUiEvent()
    data class JoinedHousehold(val newHouseholdId: String) : HouseholdUiEvent()
    object LeftHousehold : HouseholdUiEvent()
    object InviteNotFound : HouseholdUiEvent()
    object InviteExpired : HouseholdUiEvent()
    object InviteAlreadyUsed : HouseholdUiEvent()
    object InvalidTokenFormat : HouseholdUiEvent()
    data class Failed(val reason: String) : HouseholdUiEvent()
}
