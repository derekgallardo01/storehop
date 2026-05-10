package com.storehop.app.ui.items

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
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

    Scaffold(
        topBar = {
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
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddItem) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.action_add_item),
                )
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
                    EmptyState(query = query)
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
                                    onClick = { onEditItem(row.item.id) },
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
                                    onClick = { onEditItem(row.item.id) },
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

@Composable
private fun ItemRow(
    row: ItemWithCategoryAndStores,
    isNeeded: Boolean,
    onClick: () -> Unit,
    onToggleNeeded: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val url = row.item.imageUrl
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
            )
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
        val hasStores = row.stores.isNotEmpty()
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

@Composable
private fun EmptyState(query: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (query.isBlank()) stringResource(R.string.items_empty_no_query)
                   else stringResource(R.string.items_empty_with_query, query),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
