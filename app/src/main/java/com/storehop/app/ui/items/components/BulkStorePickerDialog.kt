package com.storehop.app.ui.items.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.storehop.app.R
import com.storehop.app.data.entity.Store

/**
 * v0.8.1 bulk store-tag picker. Invoked from the Items list's selection-
 * mode TopAppBar; lets the user pick one or more stores to UNION into
 * every selected item's existing store set (add-only semantics — no
 * stores are removed). Apply is enabled only after at least one store
 * is picked; cancel and dismiss are equivalent.
 *
 * The screen reads the selection size from the VM's
 * `ItemsListUiState.selectedItemIds.size` and passes it in for the
 * "Tag N items…" body copy.
 */
@Composable
fun BulkStorePickerDialog(
    stores: List<Store>,
    selectedItemCount: Int,
    onApply: (storeIdsToAdd: Set<String>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }

    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.items_bulk_tag_title)) },
        text = {
            Column {
                Text(
                    pluralStringResource(
                        id = R.plurals.items_bulk_tag_body,
                        count = selectedItemCount,
                        selectedItemCount,
                    ),
                )
                Spacer(Modifier.height(16.dp))
                StoreChipsRow(
                    stores = stores,
                    selectedStoreIds = picked,
                    onToggle = { id ->
                        picked = if (id in picked) picked - id else picked + id
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(picked) },
                enabled = picked.isNotEmpty(),
            ) {
                Text(stringResource(R.string.items_bulk_tag_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
