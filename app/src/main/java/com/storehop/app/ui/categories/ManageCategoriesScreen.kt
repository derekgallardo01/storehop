package com.storehop.app.ui.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.storehop.app.R
import com.storehop.app.data.entity.Category
import com.storehop.app.ui.util.UndoBar
import com.storehop.app.ui.util.UndoBarState
import com.storehop.app.ui.util.WordCaps
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ManageCategoriesScreen(
    onBack: () -> Unit,
    viewModel: ManageCategoriesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val categories = state.categories

    var showAddDialog by remember { mutableStateOf(false) }
    var pendingRename by remember { mutableStateOf<Category?>(null) }
    var pendingDelete by remember { mutableStateOf<Category?>(null) }
    var pendingBulkDelete by remember { mutableStateOf<Set<Category>?>(null) }

    val haptics = LocalHapticFeedback.current
    var undoState: UndoBarState? by remember { mutableStateOf(null) }
    val undoTemplate = stringResource(R.string.undo_category_deleted)
    val scope = rememberCoroutineScope()

    // Drag-reorder support. The optimistic-local pattern: while the user
    // drags, we update `localRows` immediately so the move is visible; the
    // VM commit lands on drop and the live flow eventually overwrites
    // `localRows` once it settles.
    var localRows by remember(categories) { mutableStateOf(categories) }
    var isDragging by remember { mutableStateOf(false) }
    LaunchedEffect(categories, isDragging) {
        if (!isDragging) localRows = categories
    }
    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        localRows = localRows.toMutableList().apply {
            val fromIndex = from.index.coerceIn(0, lastIndex)
            val toIndex = to.index.coerceIn(0, lastIndex)
            add(toIndex, removeAt(fromIndex))
        }
    }

    Scaffold(
        topBar = {
            if (state.selectionMode) {
                SelectionTopBar(
                    selectedCount = state.selectedIds.size,
                    totalCount = categories.size,
                    onCancel = viewModel::cancelSelection,
                    onSelectAll = viewModel::selectAll,
                    onDelete = {
                        pendingBulkDelete = categories
                            .filter { it.id in state.selectedIds }
                            .toSet()
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.title_manage_categories)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!state.selectionMode) {
                ExtendedFloatingActionButton(
                    onClick = { showAddDialog = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.add_category_dialog_title)) },
                )
            }
        },
        bottomBar = {
            UndoBar(state = undoState, onDismiss = { undoState = null })
        },
    ) { padding ->
        if (categories.isEmpty()) {
            Box(
                modifier = Modifier.padding(padding).fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.categories_empty_state),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
            ) {
                items(localRows, key = { it.id }) { category ->
                    ReorderableItem(reorderState, key = category.id) { dragging ->
                        CategoryCard(
                            category = category,
                            isDragging = dragging,
                            isSelectionMode = state.selectionMode,
                            isSelected = category.id in state.selectedIds,
                            onTap = {
                                if (state.selectionMode) {
                                    viewModel.toggleSelection(category.id)
                                } else {
                                    pendingRename = category
                                }
                            },
                            onLongPress = {
                                if (!state.selectionMode) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.enterSelection(category.id)
                                }
                            },
                            onRename = { pendingRename = category },
                            onDelete = { pendingDelete = category },
                            dragHandleModifier = Modifier.longPressDraggableHandle(
                                onDragStarted = {
                                    isDragging = true
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDragStopped = {
                                    isDragging = false
                                    viewModel.commitReorder(localRows.map { it.id })
                                },
                            ),
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddCategoryDialog(
            onDismiss = { showAddDialog = false },
            onAdd = viewModel::addManyCategories,
        )
    }
    pendingRename?.let { category ->
        RenameCategoryDialog(
            currentName = category.name,
            onDismiss = { pendingRename = null },
            onRename = { newName -> viewModel.renameCategory(category.id, newName) },
        )
    }
    pendingDelete?.let { category ->
        DeleteCategoryConfirmDialog(
            categoryName = category.name,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                val id = category.id
                val name = category.name
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.deleteCategory(id)
                pendingDelete = null
                undoState = UndoBarState(
                    message = undoTemplate.format(name),
                    onUndo = { viewModel.undoDeleteCategory(id) },
                )
            },
        )
    }
    pendingBulkDelete?.let { selected ->
        val count = selected.size
        val undoMessage = pluralStringResource(
            R.plurals.undo_categories_deleted_count, count, count,
        )
        DeleteManyCategoriesConfirmDialog(
            count = count,
            onDismiss = { pendingBulkDelete = null },
            onConfirm = {
                pendingBulkDelete = null
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                scope.launch {
                    val deletedAt = viewModel.deleteSelected() ?: return@launch
                    undoState = UndoBarState(
                        message = undoMessage,
                        onUndo = { viewModel.undoDeleteMany(deletedAt) },
                    )
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    selectedCount: Int,
    totalCount: Int,
    onCancel: () -> Unit,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                pluralStringResource(
                    R.plurals.manage_categories_selected_count,
                    selectedCount,
                    selectedCount,
                ),
            )
        },
        navigationIcon = {
            IconButton(onClick = onCancel) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.action_cancel),
                )
            }
        },
        actions = {
            IconButton(onClick = onSelectAll, enabled = selectedCount < totalCount) {
                Icon(
                    Icons.Filled.SelectAll,
                    contentDescription = stringResource(R.string.action_select_all),
                )
            }
            IconButton(onClick = onDelete, enabled = selectedCount > 0) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CategoryCard(
    category: Category,
    isDragging: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    dragHandleModifier: Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val containerColor = when {
        isDragging -> MaterialTheme.colorScheme.surfaceVariant
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surface
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Tap / long-press gesture target covers only the checkbox +
            // text region. The trailing drag handle and overflow menu are
            // outside it so their own gestures (longPressDraggableHandle,
            // IconButton) don't race the Card-level long-press into
            // selection mode.
            Row(
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(onClick = onTap, onLongClick = onLongPress)
                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = null, // Whole row handles taps.
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                Text(
                    text = category.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
            }
            if (!isSelectionMode) {
                Icon(
                    Icons.Filled.DragHandle,
                    contentDescription = stringResource(R.string.action_reorder),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = dragHandleModifier.padding(end = 4.dp).size(28.dp),
                )
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.category_more_options),
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
            }
        }
    }
}

/**
 * Multi-line add dialog. Supersedes the single-line shared
 * [com.storehop.app.ui.util.AddCategoryDialog] for the Manage Categories
 * screen (the item-form picker still uses the single-line shared one
 * because there's no use-case for adding many from there). Paste a list,
 * one category per line; whitespace-only lines drop out, case-insensitive
 * duplicates within the input are deduped, and the
 * [ManageCategoriesViewModel.addManyCategories] result populates a final
 * summary.
 */
@Composable
private fun AddCategoryDialog(
    onDismiss: () -> Unit,
    onAdd: suspend (String) -> BulkAddResult,
) {
    var text by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<BulkAddResult?>(null) }
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.add_category_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.add_category_multi_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        summary = null
                    },
                    label = { Text(stringResource(R.string.add_category_field_label)) },
                    keyboardOptions = WordCaps,
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .padding(top = 8.dp),
                )
                summary?.let { result ->
                    Text(
                        text = stringResource(
                            R.string.add_category_summary,
                            result.added, result.duplicates,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (text.isNotBlank() && !saving) {
                        saving = true
                        scope.launch {
                            val result = onAdd(text)
                            saving = false
                            if (result.added > 0 || result.duplicates > 0) {
                                onDismiss()
                            } else {
                                summary = result
                            }
                        }
                    }
                },
                enabled = text.isNotBlank() && !saving,
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

@Composable
private fun RenameCategoryDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onRename: suspend (String) -> String?,
) {
    var name by remember { mutableStateOf(currentName) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    val canSubmit = name.isNotBlank() && name.trim() != currentName && !saving

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.rename_category_dialog_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    error = null
                },
                label = { Text(stringResource(R.string.add_category_field_label)) },
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
private fun DeleteCategoryConfirmDialog(
    categoryName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_category_dialog_title, categoryName)) },
        text = { Text(stringResource(R.string.delete_category_dialog_message)) },
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

@Composable
private fun DeleteManyCategoriesConfirmDialog(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(pluralStringResource(R.plurals.delete_categories_dialog_title, count, count))
        },
        text = { Text(stringResource(R.string.delete_category_dialog_message)) },
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
