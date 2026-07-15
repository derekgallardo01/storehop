package com.storehop.app.ui.items

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import com.storehop.app.R
import com.storehop.app.data.db.relations.ItemWithCategoryAndStores
import com.storehop.app.data.prefs.SortMode
import com.storehop.app.ui.common.ZoomableImageDialog
import com.storehop.app.ui.items.components.BulkStorePickerDialog
import com.storehop.app.ui.util.EmptyState
import com.storehop.app.ui.util.UndoBar
import com.storehop.app.ui.util.UndoBarState
import com.storehop.app.ui.util.UndoEvent
import com.storehop.app.ui.util.localizedLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemsListScreen(
    onAddItem: () -> Unit,
    onEditItem: (itemId: String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCategories: () -> Unit,
    viewModel: ItemsListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val query by viewModel.query.collectAsState()
    val ctx = LocalContext.current
    val undoTemplate = stringResource(R.string.undo_item_deleted)
    var undoState: UndoBarState? by remember { mutableStateOf(null) }

    // Pull cross-screen undo prompts from the bus and surface them on the
    // shared UndoBar (3s auto-dismiss, X close button, swipe-to-dismiss).
    // The ItemForm screen fires these events after softDelete and pops back
    // to this list -- the LaunchedEffect collects them as they arrive.
    LaunchedEffect(Unit) {
        viewModel.undoEvents.collect { event ->
            when (event) {
                is UndoEvent.ItemDeleted -> {
                    val itemId = event.itemId
                    undoState = UndoBarState(
                        message = undoTemplate.format(event.itemName),
                        onUndo = { viewModel.undoItemDelete(itemId) },
                    )
                }
            }
        }
    }

    var overflowOpen by remember { mutableStateOf(false) }
    var showBulkPicker by remember { mutableStateOf(false) }
    val stores by viewModel.stores.collectAsState()

    // v0.8.1: in selection mode, system back exits selection (matches
    // the contextual TopAppBar's X). Without this, back would pop the
    // screen entirely, which is jarring.
    androidx.activity.compose.BackHandler(enabled = state.isInSelectionMode) {
        viewModel.clearSelection()
    }

    Scaffold(
        topBar = {
            if (state.isInSelectionMode) {
                // Contextual TopAppBar: "[N] selected" with X (exit) +
                // "Tag to stores…" actions. Replaces the normal bar
                // entirely while selection mode is active.
                TopAppBar(
                    title = {
                        Text(
                            stringResource(
                                R.string.items_selection_count,
                                state.selectedItemIds.size,
                            ),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.items_selection_exit_cd),
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { showBulkPicker = true }) {
                            Icon(
                                Icons.Filled.Store,
                                contentDescription = stringResource(R.string.items_action_tag_to_stores),
                            )
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.title_items)) },
                    actions = {
                        // Sort toggle: flat alphabetic vs category-grouped.
                        // Persisted via UserPreferencesRepository.itemsListSortMode.
                        IconButton(onClick = {
                            val next = if (state.sortMode == SortMode.ALPHABETIC)
                                SortMode.CATEGORY else SortMode.ALPHABETIC
                            viewModel.setSortMode(next)
                        }) {
                            Icon(
                                imageVector = if (state.sortMode == SortMode.ALPHABETIC)
                                    Icons.Filled.Category
                                else Icons.AutoMirrored.Filled.Sort,
                                contentDescription = stringResource(
                                    if (state.sortMode == SortMode.ALPHABETIC)
                                        R.string.sort_category_cd
                                    else R.string.sort_alphabetic_cd,
                                ),
                            )
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = stringResource(R.string.action_settings),
                            )
                        }
                        Box {
                            IconButton(onClick = { overflowOpen = true }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = stringResource(R.string.action_more_options),
                                )
                            }
                            DropdownMenu(
                                expanded = overflowOpen,
                                onDismissRequest = { overflowOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_manage_categories)) },
                                    onClick = {
                                        overflowOpen = false
                                        onOpenCategories()
                                    },
                                )
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            // Hide FAB in selection mode -- a "+" action while
            // multi-selecting items would be ambiguous.
            if (!state.isInSelectionMode) {
                FloatingActionButton(onClick = onAddItem) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = stringResource(R.string.action_add_item),
                    )
                }
            }
        },
        bottomBar = {
            UndoBar(state = undoState, onDismiss = { undoState = null })
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.search_items_placeholder)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setQuery("") }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.action_clear_search),
                            )
                        }
                    }
                },
                singleLine = true,
            )

            // Pure UX affordance: data is already reactive via Flow, but
            // pull-to-refresh gives users a "I asked for fresh data and it
            // happened" beat. We just show the spinner for a short moment.
            var isRefreshing by remember { mutableStateOf(false) }
            val ptrScope = rememberCoroutineScope()
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    ptrScope.launch {
                        isRefreshing = true
                        delay(500)
                        isRefreshing = false
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                val isEmpty = when (state.sortMode) {
                    SortMode.ALPHABETIC -> state.rows.isEmpty()
                    SortMode.CATEGORY -> state.sections.isEmpty()
                }
                if (isEmpty) {
                    val q = query.trim()
                    if (q.isEmpty()) {
                        EmptyState(
                            icon = Icons.Outlined.Inventory2,
                            title = stringResource(R.string.items_empty_no_query_title),
                            body = stringResource(R.string.items_empty_no_query_body),
                        )
                    } else {
                        EmptyState(
                            icon = Icons.Outlined.SearchOff,
                            title = stringResource(R.string.items_empty_search_title),
                            body = stringResource(R.string.items_empty_search_body, q),
                        )
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 96.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        when (state.sortMode) {
                            SortMode.ALPHABETIC -> {
                                items(state.rows, key = { it.item.id }) { row ->
                                    ItemRow(
                                        row = row,
                                        isNeeded = state.neededItemIds.contains(row.item.id),
                                        isSelected = row.item.id in state.selectedItemIds,
                                        isInSelectionMode = state.isInSelectionMode,
                                        onClick = {
                                            if (state.isInSelectionMode) {
                                                viewModel.toggleSelection(row.item.id)
                                            } else {
                                                onEditItem(row.item.id)
                                            }
                                        },
                                        onLongClick = { viewModel.toggleSelection(row.item.id) },
                                        onToggleNeeded = {
                                            viewModel.toggleNeededAtAllStores(
                                                row.item.id,
                                                state.neededItemIds.contains(row.item.id),
                                            )
                                        },
                                    )
                                }
                            }
                            SortMode.CATEGORY -> {
                                state.sections.forEach { section ->
                                    item(key = "header_${section.categoryName}") {
                                        ItemsCategoryHeader(section = section, ctx = ctx)
                                    }
                                    items(section.rows, key = { it.item.id }) { row ->
                                        ItemRow(
                                            row = row,
                                            isNeeded = state.neededItemIds.contains(row.item.id),
                                            isSelected = row.item.id in state.selectedItemIds,
                                            isInSelectionMode = state.isInSelectionMode,
                                            onClick = {
                                                if (state.isInSelectionMode) {
                                                    viewModel.toggleSelection(row.item.id)
                                                } else {
                                                    onEditItem(row.item.id)
                                                }
                                            },
                                            onLongClick = { viewModel.toggleSelection(row.item.id) },
                                            onToggleNeeded = {
                                                viewModel.toggleNeededAtAllStores(
                                                    row.item.id,
                                                    state.neededItemIds.contains(row.item.id),
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // v0.8.1 bulk store-tag picker. Rendered as a top-level overlay so it
    // stays composed independently of the Scaffold body's state.
    if (showBulkPicker) {
        BulkStorePickerDialog(
            stores = stores,
            selectedItemCount = state.selectedItemIds.size,
            onApply = { picked ->
                viewModel.applyBulkStores(picked)  // VM clears selection on success
                showBulkPicker = false
            },
            onDismiss = { showBulkPicker = false },
        )
    }
}

/**
 * Section header in CATEGORY sort mode. Resolves the localized label via
 * the seeded `nameKey` lookup (same pattern as Shop-at-Store), and swaps
 * in the localized "(uncategorised)" label when the ViewModel emitted the
 * [UNCATEGORISED_SENTINEL].
 */
@Composable
@android.annotation.SuppressLint("DiscouragedApi")
private fun ItemsCategoryHeader(section: ItemsCategorySection, ctx: Context) {
    val label = resolveSectionLabel(section, ctx)
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@android.annotation.SuppressLint("DiscouragedApi")
private fun resolveSectionLabel(section: ItemsCategorySection, ctx: Context): String {
    if (section.categoryName == UNCATEGORISED_SENTINEL) {
        return ctx.getString(R.string.items_uncategorised_label)
    }
    val key = section.categoryNameKey ?: return section.categoryName
    val resId = ctx.resources.getIdentifier(key, "string", ctx.packageName)
    return if (resId != 0) ctx.getString(resId) else section.categoryName
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemRow(
    row: ItemWithCategoryAndStores,
    isNeeded: Boolean,
    isSelected: Boolean,
    isInSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleNeeded: () -> Unit,
) {
    val rowBg = if (isSelected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        androidx.compose.ui.graphics.Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // v0.8.1: leading checkbox-style indicator shown only in selection
        // mode. Replaces the avatar's leading slot so the row doesn't grow
        // wider, and provides a clear "this row is/isn't selected" cue
        // (the background tint alone is too subtle on some themes).
        if (isInSelectionMode) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
        } else {
            // Avatar slot only when NOT in selection mode (the checkbox
            // above replaces it, keeping the row width identical).
            val url = row.item.imageUrl
            if (!url.isNullOrBlank()) {
                var viewing by remember(url) { mutableStateOf(false) }
                AsyncImage(
                    model = url,
                    contentDescription = stringResource(R.string.photo_item),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        // Tap the photo to enlarge; the row body still opens
                        // the editor (the tap here is consumed first).
                        .clickable { viewing = true },
                )
                if (viewing) {
                    ZoomableImageDialog(model = url, onDismiss = { viewing = false })
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = row.item.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.item.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            val subtitle = listOfNotNull(
                row.item.brand?.takeIf { it.isNotBlank() },
                row.category?.localizedLabel(),
            ).joinToString(" • ")
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (row.item.isStaple) {
            Icon(
                Icons.Filled.Star,
                contentDescription = stringResource(R.string.badge_always_on_list),
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        if (row.item.isPriority) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = stringResource(R.string.badge_critical),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        // v0.6.1: +/- toggle. Disabled when the item has no tagged stores
        // (nothing to add it to). The button is wider than the row's tap
        // target so its own tap doesn't bubble into the row's onClick.
        // v0.8.1: hidden in selection mode -- the row's tap belongs to
        // toggleSelection then, and a +/- here would be ambiguous.
        val hasStores = row.stores.isNotEmpty()
        if (!isInSelectionMode) {
            IconButton(
                onClick = onToggleNeeded,
                enabled = hasStores,
            ) {
            Icon(
                imageVector = if (isNeeded) Icons.Filled.Remove else Icons.Filled.Add,
                contentDescription = stringResource(
                    if (isNeeded) R.string.action_remove_from_list
                    else R.string.action_add_to_list,
                ),
                tint = if (hasStores) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            }
        }
    }
}

