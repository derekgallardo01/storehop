package com.storehop.app.ui.shop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.storehop.app.R
import com.storehop.app.data.db.relations.ShoppingRow
import com.storehop.app.ui.util.UndoBar
import com.storehop.app.ui.util.UndoBarState
import com.storehop.app.ui.util.WordCaps
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShopAtStoreScreen(
    onBack: () -> Unit,
    viewModel: ShopAtStoreViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val quickAddInput by viewModel.quickAddInput.collectAsState()
    val quickAddSuggestions by viewModel.quickAddSuggestions.collectAsState()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }

    var undoState: UndoBarState? by remember { mutableStateOf(null) }

    val defaultStoreLabel = stringResource(R.string.share_list_default_store)
    val undoTemplate = stringResource(R.string.undo_item_purchased)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.store?.name ?: stringResource(R.string.title_shop)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    // Toggle: show / hide every checked-off row (purchased
                    // non-staples AND not-needed staples). Filled check icon
                    // = currently showing; outlined = hidden.
                    IconToggleButton(
                        checked = state.showPurchased,
                        onCheckedChange = viewModel::setShowPurchased,
                    ) {
                        Icon(
                            imageVector = if (state.showPurchased)
                                Icons.Filled.CheckCircle
                            else Icons.Outlined.CheckCircle,
                            contentDescription = stringResource(
                                if (state.showPurchased) R.string.action_hide_purchased
                                else R.string.action_show_purchased,
                            ),
                        )
                    }
                    val canShare = state.rowsByCategory.isNotEmpty()
                    IconButton(onClick = { menuOpen = true }, enabled = canShare) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.action_more),
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_share_list)) },
                            leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                launchShareList(
                                    context = context,
                                    storeName = state.store?.name ?: defaultStoreLabel,
                                    sections = state.rowsByCategory,
                                )
                            },
                        )
                    }
                },
            )
        },
        bottomBar = {
            Column {
                UndoBar(
                    state = undoState,
                    onDismiss = { undoState = null },
                )
                if (quickAddSuggestions.isNotEmpty()) {
                    QuickAddSuggestions(
                        suggestions = quickAddSuggestions,
                        onPick = viewModel::pickExistingItem,
                    )
                }
                QuickAddBar(
                    value = quickAddInput,
                    onValueChange = viewModel::setQuickAddInput,
                    onSubmit = viewModel::submitQuickAddText,
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (state.criticalNames.isNotEmpty()) {
                CriticalBanner(criticalNames = state.criticalNames)
            }

            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.search_store_placeholder)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
            )

            // Pure UX affordance: data is already reactive, but pull-to-refresh
            // gives users a "I asked for fresh data and it happened" beat.
            var isRefreshing by remember { mutableStateOf(false) }
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    scope.launch {
                        isRefreshing = true
                        delay(500)
                        isRefreshing = false
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.rowsByCategory.isEmpty()) {
                    EmptyState(query = state.query)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 32.dp),
                    ) {
                        state.rowsByCategory.forEach { section ->
                            item(key = "header_${section.categoryName}") {
                                CategoryHeader(section)
                            }
                            items(section.rows, key = { it.itemId }) { row ->
                                ShopAtStoreRow(row, onTap = {
                                    val wasNeeded = row.isNeeded
                                    viewModel.togglePurchased(row)
                                    // Light tactile click on every check-off so the user
                                    // gets confirmation without looking. Done both
                                    // directions (check + un-check); keeps the gesture's
                                    // physical feel consistent.
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    if (wasNeeded) {
                                        // Show the undo bar. UndoBarState's default `stamp`
                                        // ensures consecutive taps reset the auto-dismiss
                                        // timer (LaunchedEffect inside UndoBar restarts).
                                        val itemId = row.itemId
                                        undoState = UndoBarState(
                                            message = undoTemplate.format(row.itemName),
                                            onUndo = { viewModel.undoPurchase(itemId) },
                                        )
                                    }
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Bottom-anchored quick-add row: controlled component. Caller owns the input
 * value and the submit action; this Composable just renders the field and
 * the Add button. Submit fires when the user hits IME Done or taps Add, and
 * is a no-op for blank/whitespace input (the caller is also defensive).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickAddBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text(stringResource(R.string.quick_add_placeholder)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = WordCaps.copy(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onSubmit,
                enabled = value.isNotBlank(),
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.action_add),
                )
            }
        }
    }
}

/**
 * Autocomplete list shown above the [QuickAddBar] while the user is adding
 * an item. Each row is a master-Items entry; tap to tag it to this store.
 * Items already needed at this store are filtered out by the ViewModel
 * before they get here. Capped height so the list never pushes the input
 * field off-screen on small devices; `LazyColumn` scrolls if there are
 * more rows than fit.
 */
@Composable
private fun QuickAddSuggestions(
    suggestions: List<QuickAddSuggestion>,
    onPick: (itemId: String) -> Unit,
) {
    Surface(
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp),
        ) {
            items(suggestions, key = { it.itemId }) { suggestion ->
                QuickAddSuggestionRow(
                    suggestion = suggestion,
                    onClick = { onPick(suggestion.itemId) },
                )
            }
        }
    }
}

@Composable
private fun QuickAddSuggestionRow(
    suggestion: QuickAddSuggestion,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = suggestion.name,
                style = MaterialTheme.typography.bodyLarge,
            )
            // Secondary line: brand · category. Falls back gracefully when
            // either is absent. If both are absent, no line is drawn.
            val secondaryParts = listOfNotNull(
                suggestion.brand?.takeIf { it.isNotBlank() },
                suggestion.categoryName?.takeIf { it.isNotBlank() },
            )
            if (secondaryParts.isNotEmpty()) {
                Text(
                    text = secondaryParts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (suggestion.isStaple) {
            Icon(
                Icons.Filled.Star,
                contentDescription = stringResource(R.string.badge_always_on_list),
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun CriticalBanner(criticalNames: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                val n = criticalNames.size
                Text(
                    text = pluralStringResource(R.plurals.critical_at_this_store, n, n),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = criticalNames.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun CategoryHeader(section: CategorySection) {
    val label = section.localizedDisplayName()
    val displayOrder = section.displayOrder
    Text(
        text = if (displayOrder != null && displayOrder < 9999)
            stringResource(R.string.aisle_format, label, displayOrder + 1)
        else label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * Resolve the section's display label from its seeded `nameKey` if present,
 * falling back to the raw category name. Same lookup pattern as the
 * [com.storehop.app.ui.util.localizedLabel] extension on Category.
 */
@Composable
@android.annotation.SuppressLint("DiscouragedApi")
private fun CategorySection.localizedDisplayName(): String {
    val context = LocalContext.current
    val key = categoryNameKey ?: return categoryName
    val resId = context.resources.getIdentifier(key, "string", context.packageName)
    return if (resId != 0) context.getString(resId) else categoryName
}

@Composable
private fun ShopAtStoreRow(
    row: ShoppingRow,
    onTap: () -> Unit,
) {
    // Any purchased row -- staple or not -- now stays in the list within the
    // session window so the user can undo a mis-tap. Non-staples drop out on
    // the next visit (sessionStartMs in the DAO query); staples stick around
    // forever struck-through.
    val isPurchased = !row.isNeeded
    val isPriorityVisual = row.isPriority && !isPurchased
    val rowAlpha = if (isPurchased) 0.6f else 1f
    val nameDecoration = if (isPurchased) TextDecoration.LineThrough else TextDecoration.None

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .alpha(rowAlpha),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Priority side-stripe -- 4dp sage band on the leading edge. Only
        // shown for needed-and-priority items; the treatment fades on
        // purchase since the row also fades.
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .height(56.dp)
                .background(
                    if (isPriorityVisual) MaterialTheme.colorScheme.primary
                    else androidx.compose.ui.graphics.Color.Transparent,
                ),
        )
        Checkbox(checked = isPurchased, onCheckedChange = { onTap() })
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp, top = 8.dp, bottom = 8.dp)) {
            Text(
                text = row.itemName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isPriorityVisual) FontWeight.SemiBold else FontWeight.Normal,
                textDecoration = nameDecoration,
            )
            row.brand?.takeIf { it.isNotBlank() }?.let { brand ->
                Text(
                    text = brand,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textDecoration = nameDecoration,
                )
            }
        }
        if (row.isStaple && isPurchased) {
            // "Always on the list" pin so the user knows why this row will
            // come back next visit even though they just checked it off.
            Icon(
                Icons.Filled.Star,
                contentDescription = stringResource(R.string.badge_always_on_list),
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(18.dp).padding(end = 16.dp),
            )
        } else if (isPriorityVisual) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = stringResource(R.string.badge_critical),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp).padding(end = 16.dp),
            )
        }
    }
}

@Composable
private fun EmptyState(query: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (query.isBlank()) stringResource(R.string.shop_empty_no_query)
                   else stringResource(R.string.shop_empty_with_query, query),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
