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
import com.storehop.app.R
import com.storehop.app.data.prefs.ThemeMode
import com.storehop.app.sync.PullState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenStatistics: () -> Unit,
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
            if (pullState == PullState.FAILED) {
                CloudSyncBanner(onRetry = viewModel::retryPull)
            }
            AccountCard(
                state = state,
                onSignIn = { viewModel.signInWithGoogle(context) },
                onSignOut = viewModel::signOut,
            )
            StatisticsCard(onOpen = onOpenStatistics)
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
            DataCard(snackbarHostState = snackbarHostState)
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
    viewModel: ImportExportViewModel = hiltViewModel(),
) {
    val busy by viewModel.busy.collectAsState()
    val latestImport by viewModel.latestImport.collectAsState()

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
            OutlinedButton(
                onClick = { exportItems.launch("storehop-items.csv") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.action_export_items)) }
            OutlinedButton(
                onClick = { exportCategories.launch("storehop-categories.csv") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.action_export_categories)) }
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
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
