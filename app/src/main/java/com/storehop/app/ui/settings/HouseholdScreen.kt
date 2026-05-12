package com.storehop.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.storehop.app.R
import com.storehop.app.billing.Entitlement
import com.storehop.app.billing.isUnlocked
import com.storehop.app.data.entity.HouseholdMember

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HouseholdScreen(
    onBack: () -> Unit,
    viewModel: HouseholdViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val event by viewModel.uiEvent.collectAsState()
    val entitlement by viewModel.entitlement.collectAsState()
    val premiumPrice by viewModel.premiumPrice.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    var pendingInvite by remember { mutableStateOf<com.storehop.app.data.repository.InviteCode?>(null) }
    var joinTokenInput by remember { mutableStateOf("") }
    var joinError by remember { mutableStateOf<Int?>(null) }
    var showLeaveConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(event) {
        when (val e = event) {
            is HouseholdUiEvent.InviteGenerated -> pendingInvite = e.invite
            is HouseholdUiEvent.JoinedHousehold -> {
                joinTokenInput = ""
                joinError = null
                snackbar.showSnackbar("Joined household")
            }
            HouseholdUiEvent.LeftHousehold -> snackbar.showSnackbar("Left household")
            HouseholdUiEvent.InviteNotFound -> joinError = R.string.household_error_invite_not_found
            HouseholdUiEvent.InviteExpired -> joinError = R.string.household_error_invite_expired
            HouseholdUiEvent.InviteAlreadyUsed -> joinError = R.string.household_error_invite_used
            HouseholdUiEvent.InvalidTokenFormat -> joinError = R.string.household_error_invalid_token
            is HouseholdUiEvent.Failed -> snackbar.showSnackbar(e.reason)
            null -> Unit
        }
        if (event != null) viewModel.acknowledgeEvent()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.household_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MembersCard(state.members, state.isPersonalHousehold)
            InviteCard(
                entitlement = entitlement,
                premiumPrice = premiumPrice,
                onGenerateInvite = {
                    val activity = context.findActivity()
                    if (activity != null) {
                        viewModel.onGenerateInviteTapped(activity)
                    }
                },
            )
            JoinCard(
                tokenInput = joinTokenInput,
                onTokenChange = { joinTokenInput = it; joinError = null },
                errorMessageRes = joinError,
                onJoin = { viewModel.acceptInvite(joinTokenInput) },
            )
            if (!state.isPersonalHousehold) {
                LeaveCard(onLeave = { showLeaveConfirm = true })
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    pendingInvite?.let { invite ->
        InviteCodeDialog(invite = invite, onDismiss = { pendingInvite = null })
    }

    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text(stringResource(R.string.household_leave_confirm_title)) },
            text = { Text(stringResource(R.string.household_leave_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showLeaveConfirm = false
                    viewModel.leaveHousehold()
                }) { Text(stringResource(R.string.household_leave_confirm_button)) }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirm = false }) {
                    Text(stringResource(R.string.household_leave_cancel_button))
                }
            },
        )
    }
}

@Composable
private fun MembersCard(members: List<HouseholdMember>, isPersonalHousehold: Boolean) {
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.household_members_header),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            if (isPersonalHousehold || members.size <= 1) {
                Text(
                    stringResource(R.string.household_members_just_you),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                members.forEach { member ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = member.displayName ?: member.uid,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (member.isOwner) {
                            Text(
                                stringResource(R.string.household_members_owner_badge),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun InviteCard(
    entitlement: Entitlement,
    premiumPrice: String?,
    onGenerateInvite: () -> Unit,
) {
    val unlocked = entitlement.isUnlocked
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.household_generate_invite_header),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.household_invite_explanation),
                style = MaterialTheme.typography.bodySmall,
            )
            // v0.8: gate Generate Invite behind Premium entitlement. Locked
            // button shows the Play-localized price; tapping launches the
            // billing flow. Accepting an invite (the JoinCard below) stays
            // free under the inviter-pays model.
            if (!unlocked) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.premium_locked_invite_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onGenerateInvite) {
                Text(
                    if (unlocked) {
                        stringResource(R.string.household_generate_invite)
                    } else if (premiumPrice != null) {
                        stringResource(R.string.premium_locked_invite_label, premiumPrice)
                    } else {
                        stringResource(R.string.premium_locked_invite_label_loading)
                    },
                )
            }
        }
    }
}

@Composable
private fun JoinCard(
    tokenInput: String,
    onTokenChange: (String) -> Unit,
    errorMessageRes: Int?,
    onJoin: () -> Unit,
) {
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.household_join_with_code),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = tokenInput,
                onValueChange = onTokenChange,
                label = { Text(stringResource(R.string.household_invite_code_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = errorMessageRes != null,
            )
            if (errorMessageRes != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(errorMessageRes),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onJoin, enabled = tokenInput.isNotBlank()) {
                Text(stringResource(R.string.household_join_button))
            }
        }
    }
}

@Composable
private fun LeaveCard(onLeave: () -> Unit) {
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            OutlinedButton(
                onClick = onLeave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.household_leave))
            }
        }
    }
}

@Composable
private fun InviteCodeDialog(
    invite: com.storehop.app.data.repository.InviteCode,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.household_invite_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.household_invite_dialog_message))
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        invite.token,
                        style = MaterialTheme.typography.headlineMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(invite.token))
                onDismiss()
            }) { Text(stringResource(R.string.household_invite_copy)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.household_invite_dismiss))
            }
        },
    )
}
