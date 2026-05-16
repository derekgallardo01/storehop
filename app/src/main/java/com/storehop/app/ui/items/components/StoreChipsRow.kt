package com.storehop.app.ui.items.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.storehop.app.data.entity.Store

/**
 * Horizontally-scrolling row of selectable store chips. Used by both the
 * single-item edit form (`ItemFormScreen`) and the v0.8.1 bulk-tag
 * picker dialog (`BulkStorePickerDialog`). Both surfaces share the
 * same chip styling + scroll affordance, so the visual behavior stays
 * consistent.
 *
 * The caller owns the selection set and `onToggle` callback; the
 * Composable is purely presentational.
 */
@Composable
fun StoreChipsRow(
    stores: List<Store>,
    selectedStoreIds: Set<String>,
    onToggle: (storeId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        stores.forEach { store ->
            FilterChip(
                selected = store.id in selectedStoreIds,
                onClick = { onToggle(store.id) },
                label = { Text(store.name) },
            )
        }
    }
}
