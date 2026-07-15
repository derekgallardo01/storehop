package com.storehop.app.ui.shop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Store
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.storehop.app.R
import com.storehop.app.data.repository.StorePickerRow
import com.storehop.app.ui.util.EmptyState
import com.storehop.app.ui.util.UndoBar
import com.storehop.app.ui.util.UndoBarState
import com.storehop.app.ui.util.WordCaps
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorePickerScreen(
    onPickStore: (storeId: String) -> Unit,
    onOpenSettings: () -> Unit,
    onEditAisles: (storeId: String) -> Unit,
    viewModel: StorePickerViewModel = hiltViewModel(),
) {
    val rows by viewModel.rows.collectAsState()
    val criticalSummary by viewModel.criticalSummary.collectAsState()
    val buyTodaySummary by viewModel.buyTodaySummary.collectAsState()

    // Local mutable copy so the dragging row visually moves before the DB
    // round-trip lands (and so the upstream Flow re-emit triggered by our
    // commit doesn't yank the row back mid-drag). Synced from upstream
    // whenever the user isn't actively dragging.
    var localRows by remember { mutableStateOf(rows) }
    var isDragging by remember { mutableStateOf(false) }
    LaunchedEffect(rows, isDragging) {
        if (!isDragging) localRows = rows
    }

    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // Lazy-column indices map 1:1 to localRows -- no header items in
        // this LazyColumn (the critical banner lives outside it). Guard
        // against out-of-range just in case the lib's edge sentinels poke
        // in during a fling.
        localRows = localRows.toMutableList().apply {
            val fromIndex = from.index.coerceIn(0, lastIndex)
            val toIndex = to.index.coerceIn(0, lastIndex)
            add(toIndex, removeAt(fromIndex))
        }
    }

    var showAddDialog by remember { mutableStateOf(false) }
    // Pending Rename / Delete actions. We keep the entire StorePickerRow so
    // dialogs can show the current name without re-fetching.
    var pendingRename by remember { mutableStateOf<StorePickerRow?>(null) }
    var pendingDelete by remember { mutableStateOf<StorePickerRow?>(null) }

    val haptics = LocalHapticFeedback.current
    var undoState: UndoBarState? by remember { mutableStateOf(null) }
    val undoTemplate = stringResource(R.string.undo_store_deleted)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_shop)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.action_settings),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.action_add_store)) },
            )
        },
        bottomBar = {
            UndoBar(state = undoState, onDismiss = { undoState = null })
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            buyTodaySummary?.let { BuyTodayBanner(state = it) }
            criticalSummary?.let { CriticalNeedsBanner(state = it) }
            if (localRows.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.Store,
                    title = stringResource(R.string.storepicker_empty_title),
                    body = stringResource(R.string.storepicker_empty_body),
                )
                return@Column
            }
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.fillMaxSize(),
                // Bottom padding leaves room for the FAB so the last card
                // isn't covered by it.
                contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
            ) {
                items(localRows, key = { it.store.id }) { row ->
                    ReorderableItem(reorderState, key = row.store.id) { dragging ->
                        StorePickerCard(
                            row = row,
                            isDragging = dragging,
                            onClick = { onPickStore(row.store.id) },
                            onRename = { pendingRename = row },
                            onDelete = { pendingDelete = row },
                            onEditAisles = { onEditAisles(row.store.id) },
                            onToggleOneOff = {
                                viewModel.setStoreOneOff(row.store.id, !row.store.isOneOff)
                            },
                            // Long-press the drag-handle icon starts a drag.
                            // Tap (without long-press) still navigates via onClick.
                            dragHandleModifier = Modifier.longPressDraggableHandle(
                                onDragStarted = {
                                    isDragging = true
                                    // Tactile cue confirming the long-press
                                    // engaged drag mode -- otherwise users
                                    // may not realize they can now drag.
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDragStopped = {
                                    isDragging = false
                                    viewModel.commitOrder(localRows.map { it.store.id })
                                },
                            ),
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddStoreDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, isOneOff -> viewModel.addStore(name, isOneOff) },
        )
    }
    pendingRename?.let { row ->
        RenameStoreDialog(
            currentName = row.store.name,
            onDismiss = { pendingRename = null },
            onRename = { newName -> viewModel.renameStore(row.store.id, newName) },
        )
    }
    pendingDelete?.let { row ->
        DeleteStoreConfirmDialog(
            storeName = row.store.name,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                val storeId = row.store.id
                val storeName = row.store.name
                // Stronger haptic for a destructive action -- matches the
                // weight of the consequence and gives the user a tactile
                // beat that "something significant just happened."
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.deleteStore(storeId)
                pendingDelete = null
                // Offer undo via the shared bar. Setting a fresh UndoBarState
                // resets the auto-dismiss timer so a rapid second delete just
                // replaces the prompt instead of queuing two.
                undoState = UndoBarState(
                    message = undoTemplate.format(storeName),
                    onUndo = { viewModel.undoDeleteStore(storeId) },
                )
            },
        )
    }
}

@Composable
private fun AddStoreDialog(
    onDismiss: () -> Unit,
    onAdd: suspend (String, Boolean) -> String?,
) {
    var name by remember { mutableStateOf("") }
    var isOneOff by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val submit = {
        if (name.isNotBlank() && !saving) {
            saving = true
            scope.launch {
                val result = onAdd(name, isOneOff)
                saving = false
                if (result == null) onDismiss()
                else error = result
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.add_store_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    label = { Text(stringResource(R.string.add_store_field_label)) },
                    placeholder = { Text(stringResource(R.string.add_store_field_placeholder)) },
                    singleLine = true,
                    keyboardOptions = WordCaps,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.focusRequester(focusRequester),
                )
                Spacer(Modifier.height(16.dp))
                // v0.9: one-off store toggle. Items tagged only to a one-
                // off store are hidden from the master Items list; they
                // live inside this store's Shop view only. For non-
                // recurring purchases (couch, drying rack, etc.).
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.store_form_one_off_toggle),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            stringResource(R.string.store_form_one_off_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = isOneOff,
                        onCheckedChange = { isOneOff = it },
                        enabled = !saving,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { submit() },
                enabled = name.isNotBlank() && !saving,
            ) {
                Text(stringResource(if (saving) R.string.action_adding else R.string.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/**
 * v0.9 "Buy Today!" banner — pinned above the Critical banner at the top of the
 * Stores screen. Distinct urgent styling (error container) so it reads as
 * "act today," not "don't forget eventually." Collapsed by default; tap to
 * expand a per-store breakdown. Mirrors [CriticalNeedsBanner]'s structure.
 */
@Composable
private fun BuyTodayBanner(state: BuyTodayBannerState) {
    var expanded by remember { mutableStateOf(false) }
    val onContainer = MaterialTheme.colorScheme.onErrorContainer
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Today,
                    contentDescription = null,
                    tint = onContainer,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.buy_today_banner_count,
                            state.totalCount,
                            state.totalCount,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = onContainer,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (state.singleStore) {
                            stringResource(R.string.buy_today_banner_all_at, state.topStoreName)
                        } else {
                            stringResource(
                                R.string.buy_today_banner_most_at,
                                state.topStoreName,
                                state.topStoreCount,
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = onContainer,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = stringResource(
                        if (expanded) R.string.buy_today_banner_collapse_cd
                        else R.string.buy_today_banner_expand_cd,
                    ),
                    tint = onContainer,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp, start = 36.dp)) {
                    state.byStore.forEach { (storeName, items) ->
                        Text(
                            text = stringResource(
                                R.string.buy_today_banner_store_section,
                                storeName,
                                items.size,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = onContainer,
                        )
                        Text(
                            text = items.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = onContainer,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CriticalNeedsBanner(state: CriticalBannerState) {
    var expanded by remember { mutableStateOf(false) }
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = onContainer,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.critical_banner_count,
                            state.totalCount,
                            state.totalCount,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = onContainer,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (state.singleStore) {
                            stringResource(
                                R.string.critical_banner_all_at,
                                state.topStoreName,
                            )
                        } else {
                            stringResource(
                                R.string.critical_banner_most_at,
                                state.topStoreName,
                                state.topStoreCount,
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = onContainer,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = stringResource(
                        if (expanded) R.string.critical_banner_collapse_cd
                        else R.string.critical_banner_expand_cd,
                    ),
                    tint = onContainer,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp, start = 36.dp)) {
                    state.byStore.forEach { (storeName, items) ->
                        Text(
                            text = stringResource(
                                R.string.critical_banner_store_section,
                                storeName,
                                items.size,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = onContainer,
                        )
                        Text(
                            text = items.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = onContainer,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StorePickerCard(
    row: StorePickerRow,
    isDragging: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onEditAisles: () -> Unit,
    onToggleOneOff: () -> Unit,
    dragHandleModifier: Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            // Long-press anywhere on the tile starts a drag-to-reorder.
            // Beta tester feedback: the small drag-handle icon wasn't
            // intuitive ("she didn't know that's what that does"). Tap
            // continues to navigate via the .clickable below.
            .then(dragHandleModifier)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        // Lift the row visually while the user is dragging it.
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isDragging) 8.dp else 1.dp,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Store color dot.
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        row.store.colorArgb?.let { Color(it.toLong() or 0xFF000000) }
                            ?: MaterialTheme.colorScheme.secondaryContainer,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = row.store.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.store.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                val (subtitle, subtitleColor) = when {
                    row.neededCount > 0 -> {
                        val s = pluralStringResource(
                            R.plurals.store_items_needed,
                            row.neededCount,
                            row.neededCount,
                        )
                        s to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    // Trip-affirmation case: this store had things to grab and
                    // they've all been grabbed (here or at another store this
                    // item was tagged to). Sage primary so the user can scan
                    // the picker and see at a glance which stops are done.
                    row.pickedUpInSessionCount > 0 ->
                        stringResource(R.string.store_all_set) to MaterialTheme.colorScheme.primary
                    else ->
                        stringResource(R.string.store_nothing_needed) to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor,
                )
            }
            if (row.criticalItemNames.isNotEmpty()) {
                AssistChip(
                    onClick = onClick,
                    label = {
                        Text(
                            text = pluralStringResource(
                                R.plurals.store_critical_chip,
                                row.criticalItemNames.size,
                                row.criticalItemNames.size,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        labelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
                Spacer(Modifier.width(4.dp))
            }
            // (The dedicated drag-handle icon was removed — long-press on
            // the whole tile starts a drag now, per beta feedback that the
            // 6-dot indicator wasn't discoverable.)
            // Overflow menu: Rename / Delete. Wrapped in a Box so the
            // DropdownMenu anchors here rather than at the screen edge.
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.store_more_options),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_rename)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_edit_aisles)) },
                        leadingIcon = {
                            Icon(Icons.Filled.DragIndicator, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            onEditAisles()
                        },
                    )
                    // v0.9: toggle the one-off flag. Trailing checkmark
                    // shows the current state without an extra row chrome.
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(R.string.store_form_one_off_toggle))
                        },
                        leadingIcon = {
                            Icon(
                                if (row.store.isOneOff) Icons.Filled.CheckBox
                                else Icons.Filled.CheckBoxOutlineBlank,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onToggleOneOff()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.action_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
    }
}

@Composable
private fun RenameStoreDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onRename: suspend (String) -> String?,
) {
    var name by remember { mutableStateOf(currentName) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val canSubmit = name.isNotBlank() && name.trim() != currentName && !saving

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.rename_store_dialog_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    error = null
                },
                label = { Text(stringResource(R.string.add_store_field_label)) },
                singleLine = true,
                keyboardOptions = WordCaps,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                modifier = Modifier.focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (canSubmit) {
                        saving = true
                        scope.launch {
                            val result = onRename(name)
                            saving = false
                            if (result == null) onDismiss()
                            else error = result
                        }
                    }
                },
                enabled = canSubmit,
            ) {
                Text(stringResource(if (saving) R.string.action_saving else R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun DeleteStoreConfirmDialog(
    storeName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_store_dialog_title, storeName)) },
        text = { Text(stringResource(R.string.delete_store_dialog_message)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text(stringResource(R.string.action_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
