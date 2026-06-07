package com.storehop.app.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import androidx.core.net.toUri
import com.storehop.app.BuildConfig
import com.storehop.app.R
import com.storehop.app.billing.Entitlement
import com.storehop.app.billing.isUnlocked
import com.storehop.app.data.prefs.ThemeMode
import com.storehop.app.sync.PullState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenStatistics: () -> Unit,
    onOpenHousehold: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val currentLocaleTag by viewModel.currentLocaleTag.collectAsState()
    val pullState by viewModel.pullState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // StatisticsCard sits above the section headers as a featured
            // deep-link, since Stats is reached only from here (no bottom-nav
            // entry). Grouping it under a section header of one would look
            // thin.
            StatisticsCard(onOpen = onOpenStatistics)

            SectionHeader(text = stringResource(R.string.settings_section_account))
            if (pullState == PullState.FAILED) {
                CloudSyncBanner(onRetry = viewModel::retryPull)
            }
            AccountCard(
                state = state,
                onSignIn = { viewModel.signInWithGoogle(context) },
                onSignOut = viewModel::signOut,
            )
            // v0.7.0: multi-user household management lives directly under the
            // Account section since invites work only for signed-in users —
            // an anonymous user can still tap through and see "Just you", but
            // generating / accepting an invite requires Google sign-in.
            HouseholdLinkCard(onOpen = onOpenHousehold)

            SectionHeader(text = stringResource(R.string.settings_section_display))
            ThemeCard(
                selected = themeMode,
                onSelect = viewModel::setThemeMode,
            )
            LanguageCard(
                selectedTag = currentLocaleTag,
                onSelect = { tag ->
                    viewModel.setLocale(tag)
                    // Force the activity to remake so resource strings
                    // re-resolve under the new locale. On API 33+ the system
                    // would normally auto-restart after setApplicationLocales,
                    // but ComponentActivity (vs AppCompatActivity) doesn't
                    // always get that auto-restart -- doing it ourselves is
                    // both safe and reliable across versions. We unwrap the
                    // Compose Context (it's typically a ContextWrapper around
                    // the Activity, so a direct cast returns null).
                    context.findActivity()?.recreate()
                },
            )

            SectionHeader(text = stringResource(R.string.settings_section_data))
            DataCard(
                snackbarHostState = snackbarHostState,
                entitlement = viewModel.entitlement.collectAsState().value,
                premiumPrice = viewModel.premiumPrice.collectAsState().value,
                onLaunchPremiumPurchase = {
                    context.findActivity()?.let(viewModel::launchPremiumPurchase)
                },
            )
            ForceSyncCard(
                forceSyncState = viewModel.forceSyncState.collectAsState().value,
                pendingCount = viewModel.pendingPushCount.collectAsState().value,
                onForceSync = viewModel::forceSyncNow,
                onAcknowledge = viewModel::acknowledgeForceSync,
            )
            // v0.8: upsell card. Shown only when the user isn't entitled.
            // Above the About section so it sits at the bottom of the
            // scroll where the user lands after browsing settings.
            UpgradeToPremiumCard(
                entitlement = viewModel.entitlement.collectAsState().value,
                premiumPrice = viewModel.premiumPrice.collectAsState().value,
                onUpgrade = {
                    context.findActivity()?.let(viewModel::launchPremiumPurchase)
                },
                onRestore = viewModel::restorePurchases,
            )

            SectionHeader(text = stringResource(R.string.settings_section_about))
            AboutCard()
        }
    }
}

@Composable
private fun ThemeCard(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    SettingsCard(title = stringResource(R.string.settings_theme_title)) {
        Column(modifier = Modifier.selectableGroup()) {
            RadioRow(
                label = stringResource(R.string.settings_theme_system),
                selected = selected == ThemeMode.SYSTEM,
                onClick = { onSelect(ThemeMode.SYSTEM) },
            )
            RadioRow(
                label = stringResource(R.string.settings_theme_light),
                selected = selected == ThemeMode.LIGHT,
                onClick = { onSelect(ThemeMode.LIGHT) },
            )
            RadioRow(
                label = stringResource(R.string.settings_theme_dark),
                selected = selected == ThemeMode.DARK,
                onClick = { onSelect(ThemeMode.DARK) },
            )
        }
    }
}

@Composable
private fun LanguageCard(
    selectedTag: String,
    onSelect: (String) -> Unit,
) {
    SettingsCard(title = stringResource(R.string.settings_language_title)) {
        Column(modifier = Modifier.selectableGroup()) {
            RadioRow(
                label = stringResource(R.string.settings_language_system),
                selected = selectedTag.isBlank(),
                onClick = { onSelect("") },
            )
            RadioRow(
                label = stringResource(R.string.settings_language_english),
                selected = selectedTag.equals("en", ignoreCase = true) ||
                    selectedTag.startsWith("en-", ignoreCase = true),
                onClick = { onSelect("en") },
            )
            RadioRow(
                label = stringResource(R.string.settings_language_pt_pt),
                selected = selectedTag.equals("pt-PT", ignoreCase = true),
                onClick = { onSelect("pt-PT") },
            )
            RadioRow(
                label = stringResource(R.string.settings_language_es),
                selected = selectedTag.equals("es", ignoreCase = true) ||
                    selectedTag.startsWith("es-", ignoreCase = true),
                onClick = { onSelect("es") },
            )
            RadioRow(
                label = stringResource(R.string.settings_language_it),
                selected = selectedTag.equals("it", ignoreCase = true) ||
                    selectedTag.startsWith("it-", ignoreCase = true),
                onClick = { onSelect("it") },
            )
        }
    }
}

/**
 * "Data" card — Mike-asked CSV import / export of items + categories. Each
 * button hooks a Storage Access Framework launcher so the user picks the
 * destination / source file from their own storage (Downloads, Drive, etc.)
 * — we never read or write files outside of what the user explicitly selects.
 *
 * Import is **non-destructive** by contract (see ImportExportRepository); the
 * snackbar here surfaces the count of skipped duplicates so the user can tell
 * at a glance that nothing was overwritten. Undo soft-deletes the rows that
 * were just inserted, leaving any pre-existing data untouched.
 */
@Composable
private fun DataCard(
    snackbarHostState: SnackbarHostState,
    entitlement: com.storehop.app.billing.Entitlement,
    premiumPrice: String?,
    onLaunchPremiumPurchase: () -> Unit,
    viewModel: ImportExportViewModel = hiltViewModel(),
) {
    val busy by viewModel.busy.collectAsState()
    val latestImport by viewModel.latestImport.collectAsState()
    val unlocked = entitlement.isUnlocked

    val exportItems = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
        onResult = { uri -> uri?.let { viewModel.exportItemsTo(it) } },
    )
    val exportCategories = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
        onResult = { uri -> uri?.let { viewModel.exportCategoriesTo(it) } },
    )
    val importItems = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        // Broad MIME so a generic CSV from email or Drive shows up. Some
        // sources mark them as text/plain, application/octet-stream, etc.
        onResult = { uri -> uri?.let { viewModel.importItemsFrom(it) } },
    )
    val importCategories = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> uri?.let { viewModel.importCategoriesFrom(it) } },
    )

    // After every import, surface the count + Undo. ActionPerformed -> Undo;
    // any other result (timeout, dismiss) consumes the result without undoing.
    val undoLabel = stringResource(R.string.import_undo_label)
    val r = latestImport
    val message = if (r != null) stringResource(
        R.string.import_result_summary,
        r.itemsImported, r.categoriesImported, r.storesImported, r.duplicatesSkipped,
    ) else null
    LaunchedEffect(latestImport) {
        if (r == null || message == null) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = undoLabel,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoLastImport()
        } else {
            viewModel.consumeLatestImport()
        }
    }

    SettingsCard(title = stringResource(R.string.settings_data_section_title)) {
        // Stacked full-width buttons: longer labels ("Export categories" /
        // "Import categories") wrap to two lines when squeezed into a 50%-
        // width 2x2 grid, leaving the buttons visibly mismatched. One column
        // keeps every action on a single line at the same width and height.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.settings_data_section_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            // v0.8: Export gated behind Premium. Locked buttons show the
            // Play-localized price and launch the billing flow on tap.
            // Import stays unconditionally free — onboarding hook for
            // users moving from another app.
            OutlinedButton(
                onClick = {
                    if (unlocked) exportItems.launch("storehop-items.csv")
                    else onLaunchPremiumPurchase()
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (unlocked) stringResource(R.string.action_export_items)
                    else stringResource(
                        R.string.premium_locked_export_label,
                        premiumPrice ?: "",
                    ),
                )
            }
            OutlinedButton(
                onClick = {
                    if (unlocked) exportCategories.launch("storehop-categories.csv")
                    else onLaunchPremiumPurchase()
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (unlocked) stringResource(R.string.action_export_categories)
                    else stringResource(
                        R.string.premium_locked_export_categories_label,
                        premiumPrice ?: "",
                    ),
                )
            }
            OutlinedButton(
                onClick = { importItems.launch(arrayOf("text/*", "application/octet-stream")) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.action_import_items)) }
            OutlinedButton(
                onClick = { importCategories.launch(arrayOf("text/*", "application/octet-stream")) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.action_import_categories)) }
        }
    }
}

/**
 * v0.8: upsell card. Visible only when [entitlement] is
 * [Entitlement.NotEntitled]. Premium and LegacyUser hide it because
 * the gate is already lifted on those users' devices.
 *
 * Renders the value props inline, the Play-localized price on the
 * primary CTA, and a "Restore purchases" link underneath for users
 * who paid on another device of the same Google account.
 */
@Composable
private fun UpgradeToPremiumCard(
    entitlement: com.storehop.app.billing.Entitlement,
    premiumPrice: String?,
    onUpgrade: () -> Unit,
    onRestore: () -> Unit,
) {
    if (entitlement.isUnlocked) return
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.premium_card_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "• " + stringResource(R.string.premium_card_body_household),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "• " + stringResource(R.string.premium_card_body_export),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onUpgrade,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (premiumPrice != null) {
                        stringResource(R.string.premium_cta_unlock, premiumPrice)
                    } else {
                        stringResource(R.string.premium_cta_unlock_loading)
                    },
                )
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.premium_restore_purchases))
            }
        }
    }
}

/**
 * v0.7.1: "Force sync now" card — load-bearing for the sideload APK → Play
 * Store transition Mike has to do. Tap the button, wait for the queue to
 * drain + "Safe to uninstall" appears, then uninstall + reinstall from
 * Play.
 *
 * State machine lives in [SettingsViewModel.forceSyncState]. UI reflects
 * the four states:
 *   - Idle → primary "Force sync now" button + an explainer line.
 *   - Syncing → CircularProgressIndicator + "Syncing N items..."
 *   - SafeToUninstall → green check + "Safe to uninstall" message.
 *   - Failed → warning + remaining-count + "Retry" button.
 */
@Composable
private fun ForceSyncCard(
    forceSyncState: ForceSyncState,
    pendingCount: Int,
    onForceSync: () -> Unit,
    onAcknowledge: () -> Unit,
) {
    SettingsCard(title = stringResource(R.string.settings_force_sync_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.settings_force_sync_explainer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            when (forceSyncState) {
                is ForceSyncState.Idle -> {
                    OutlinedButton(
                        onClick = onForceSync,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (pendingCount > 0) {
                                stringResource(R.string.settings_force_sync_button_pending, pendingCount)
                            } else {
                                stringResource(R.string.settings_force_sync_button_idle)
                            },
                        )
                    }
                }
                is ForceSyncState.Syncing -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text(
                            text = stringResource(
                                R.string.settings_force_sync_in_progress,
                                forceSyncState.pendingCount,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                is ForceSyncState.SafeToUninstall -> {
                    Text(
                        text = stringResource(R.string.settings_force_sync_safe),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = onAcknowledge,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.settings_force_sync_button_done))
                    }
                }
                is ForceSyncState.Failed -> {
                    Text(
                        text = stringResource(
                            R.string.settings_force_sync_failed,
                            forceSyncState.pendingCount,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = onForceSync,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.settings_force_sync_button_retry))
                    }
                }
            }
        }
    }
}

/**
 * Banner shown above the Account section when the most recent cloud-sync
 * pull failed (network, permission, etc.). Tapping Retry kicks off another
 * pull via [SettingsViewModel.retryPull]; on success the banner disappears.
 *
 * Uses the error-container palette so it's visually distinct from the
 * success-state cards underneath without screaming red.
 */
@Composable
private fun CloudSyncBanner(onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.cloud_sync_incomplete),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.cloud_sync_retry_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = onRetry) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

@Composable
private fun StatisticsCard(onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.BarChart,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.statistics_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.statistics_settings_link_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        }
    }
}

@Composable
private fun RadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun AccountCard(
    state: AccountState,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.account_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                AccountAvatar(photoUrl = state.photoUrl, isAnonymous = state.isAnonymous)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    if (state.isAnonymous) {
                        Text(
                            stringResource(R.string.account_anonymous_label),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            stringResource(R.string.account_anonymous_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = state.displayName?.takeIf { it.isNotBlank() }
                                ?: state.email
                                ?: stringResource(R.string.account_signed_in_default),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        state.email?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (state.isAnonymous) {
                Button(
                    onClick = onSignIn,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.action_sign_in_with_google))
                }
                Text(
                    stringResource(R.string.account_sign_in_helper),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                OutlinedButton(
                    onClick = onSignOut,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.action_sign_out))
                }
                Text(
                    stringResource(R.string.account_sign_out_helper),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AccountAvatar(photoUrl: String?, isAnonymous: Boolean) {
    if (!photoUrl.isNullOrBlank()) {
        AsyncImage(
            model = photoUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
        )
    } else {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    if (isAnonymous) MaterialTheme.colorScheme.surface
                    else MaterialTheme.colorScheme.primaryContainer,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.AccountCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

/**
 * Walks the Context wrapper chain to find the underlying [Activity]. Compose's
 * `LocalContext.current` is typically a ContextWrapper around the Activity (the
 * Theme's themed wrapper), so a direct `as? Activity` cast returns null. Walking
 * `baseContext` until we hit an Activity is the standard pattern.
 */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Section label that introduces a group of related cards. Compact + low
 * visual weight (titleSmall, onSurfaceVariant, leading padding) so it
 * orients the eye without competing with the card titles below it. Used
 * to break the previously-flat Settings screen into Account / Display /
 * Data / About zones.
 */
/**
 * v0.7.0: Settings → Household tap-target. Compact card with a forward
 * arrow that opens the dedicated Household screen (member list, invite
 * generate/join, leave). Mirrors the visual weight of [StatisticsCard].
 */
@Composable
private fun HouseholdLinkCard(onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpen,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.household_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.household_settings_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
    )
}

/**
 * About card: app version + build number + a privacy-policy link.
 * Tapping the link launches the default browser via an ACTION_VIEW
 * intent — no in-app webview (avoids a dependency, keeps the trust
 * boundary at the browser).
 */
@Composable
private fun AboutCard() {
    val context = LocalContext.current
    SettingsCard(title = stringResource(R.string.settings_section_about)) {
        Column {
            Text(
                text = stringResource(
                    R.string.settings_about_version_format,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            AboutLinkRow(
                label = stringResource(R.string.settings_about_privacy),
                onClick = {
                    context.openUrl("https://derekgallardo01.github.io/storehop/privacy-policy")
                },
            )
        }
    }
}

@Composable
private fun AboutLinkRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Launch [url] in the user's default browser. */
private fun Context.openUrl(url: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri())
    runCatching { startActivity(intent) } // No-op if no browser; better than crashing.
}
