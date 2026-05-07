package com.storehop.app.ui.items

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.storehop.app.R
import com.storehop.app.ui.util.localizedLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemFormScreen(
    onBack: () -> Unit,
    viewModel: ItemFormViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val stores by viewModel.stores.collectAsState()
    val isEdit = viewModel.isEdit
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.saved, state.deleted) {
        if (state.saved || state.deleted) onBack()
    }
    LaunchedEffect(state.saveError) {
        // Surface non-validation errors (network/upload failures) as a
        // snackbar so they're visible even when the user has scrolled --
        // and so the form's vertical rhythm doesn't shift around.
        state.saveError?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(stringResource(if (isEdit) R.string.title_edit_item else R.string.title_add_item))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (isEdit) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                            )
                        }
                    }
                    // Save lives in the top bar so it's always visible -- the
                    // form is long enough that a bottom-anchored Save was
                    // hidden below the fold on first render.
                    TextButton(
                        onClick = viewModel::submit,
                        enabled = !state.isSubmitting,
                    ) {
                        if (state.isSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 8.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(stringResource(R.string.action_saving))
                        } else {
                            Text(stringResource(R.string.action_save))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (state.isLoading) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = { Text(stringResource(R.string.form_field_name)) },
                isError = state.nameError,
                supportingText = if (state.nameError) {
                    { Text(stringResource(R.string.form_error_name_required)) }
                } else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.brand,
                onValueChange = viewModel::setBrand,
                label = { Text(stringResource(R.string.form_field_brand_optional)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            CategoryDropdown(
                selectedId = state.categoryId,
                categories = categories,
                onSelect = viewModel::setCategoryId,
            )

            Column {
                Text(
                    stringResource(R.string.form_field_stores),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    stores.forEach { store ->
                        FilterChip(
                            selected = store.id in state.storeIds,
                            onClick = { viewModel.toggleStore(store.id) },
                            label = { Text(store.name) },
                        )
                    }
                }
            }

            HorizontalDivider()

            ToggleRow(
                title = stringResource(R.string.form_toggle_staple),
                subtitle = stringResource(R.string.form_toggle_staple_subtitle),
                checked = state.isStaple,
                onCheckedChange = viewModel::setStaple,
            )
            ToggleRow(
                title = stringResource(R.string.form_toggle_priority),
                subtitle = stringResource(R.string.form_toggle_priority_subtitle),
                checked = state.isPriority,
                onCheckedChange = viewModel::setPriority,
            )

            ImagePickerTile(
                imageUrl = state.imageUrl,
                localUri = state.localImageUri,
                isUploading = state.isUploadingImage,
                onImagePicked = viewModel::pickLocalImage,
                onClearImage = viewModel::clearImage,
            )
            // Save action lives in the top app bar (always visible).
            // saveError is surfaced via Snackbar from the LaunchedEffect above.
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_item_title)) },
            text = { Text(stringResource(R.string.delete_item_message, state.name)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.delete()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryDropdown(
    selectedId: String?,
    categories: List<com.storehop.app.data.entity.Category>,
    onSelect: (String?) -> Unit,
) {
    var sheetOpen by remember { mutableStateOf(false) }
    val selected = categories.firstOrNull { it.id == selectedId }
    val noneLabel = stringResource(R.string.form_field_category_none)
    val displayLabel = selected?.localizedLabel() ?: noneLabel
    val sheetState = rememberModalBottomSheetState()

    // Disabled-but-clickable text field shows the current selection and
    // launches the bottom sheet on tap. The transparent Box overlay catches
    // the click since OutlinedTextField swallows clicks when not enabled.
    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = displayLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.form_field_category)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = false,
        )
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { sheetOpen = true },
        )
    }

    if (sheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = sheetState,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.form_field_category_pick),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    item {
                        ListItem(
                            headlineContent = { Text(noneLabel) },
                            modifier = Modifier.clickable {
                                onSelect(null); sheetOpen = false
                            },
                        )
                    }
                    items(categories.size) { i ->
                        val c = categories[i]
                        ListItem(
                            headlineContent = { Text(c.localizedLabel()) },
                            modifier = Modifier.clickable {
                                onSelect(c.id); sheetOpen = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
