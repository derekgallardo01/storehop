package com.storehop.app.ui.shop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.storehop.app.data.repository.StorePickerRow
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorePickerScreen(
    onPickStore: (storeId: String) -> Unit,
    viewModel: StorePickerViewModel = hiltViewModel(),
) {
    val rows by viewModel.rows.collectAsState()
    val critical by viewModel.criticalAcrossStores.collectAsState()

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

    Scaffold(topBar = { TopAppBar(title = { Text("Where are you shopping?") }) }) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (critical.isNotEmpty()) {
                CriticalNeedsBanner(critical = critical)
            }
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(localRows, key = { it.store.id }) { row ->
                    ReorderableItem(reorderState, key = row.store.id) { dragging ->
                        StorePickerCard(
                            row = row,
                            isDragging = dragging,
                            onClick = { onPickStore(row.store.id) },
                            // Long-press anywhere on the card starts a drag.
                            // Tap (without long-press) still navigates via onClick.
                            dragHandleModifier = Modifier.longPressDraggableHandle(
                                onDragStarted = { isDragging = true },
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
}

@Composable
private fun CriticalNeedsBanner(critical: List<String>) {
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
                Text(
                    text = "Critical items needed",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    text = critical.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun StorePickerCard(
    row: StorePickerRow,
    isDragging: Boolean,
    onClick: () -> Unit,
    dragHandleModifier: Modifier,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        // Lift the row visually while the user is dragging it.
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isDragging) 8.dp else 1.dp,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
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
                        val s = "${row.neededCount} item${if (row.neededCount == 1) "" else "s"} needed"
                        s to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    // Trip-affirmation case: this store had things to grab and
                    // they've all been grabbed (here or at another store this
                    // item was tagged to). Sage primary so the user can scan
                    // the picker and see at a glance which stops are done.
                    row.pickedUpInSessionCount > 0 ->
                        "✓ All set" to MaterialTheme.colorScheme.primary
                    else ->
                        "Nothing needed" to MaterialTheme.colorScheme.onSurfaceVariant
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
                            text = "⚠ ${row.criticalItemNames.size} critical",
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
            // Drag handle: long-press here (or anywhere on the row -- this
            // handle is co-located with the row's drag region) to start
            // reordering. Subtle so it doesn't distract from the chevron.
            Icon(
                Icons.Filled.DragIndicator,
                contentDescription = "Drag to reorder",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = dragHandleModifier
                    .size(20.dp)
                    .padding(end = 4.dp),
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
