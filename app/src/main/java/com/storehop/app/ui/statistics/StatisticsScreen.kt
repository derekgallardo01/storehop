package com.storehop.app.ui.statistics

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.storehop.app.R
import com.storehop.app.data.dao.DayCount

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    onBack: () -> Unit,
    viewModel: StatisticsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.statistics_title)) },
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
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            when (val s = state) {
                StatisticsUiState.Loading -> LoadingContent()
                StatisticsUiState.Empty -> EmptyContent()
                is StatisticsUiState.Ready -> ReadyContent(state = s)
            }
        }
    }
}

@Composable
private fun LoadingContent() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.statistics_empty_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.statistics_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReadyContent(state: StatisticsUiState.Ready) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ActivitySection(state)
        ItemInsightsSection(state)
        StoreInsightsSection(state)
        CategoryBreakdownSection(state)
    }
}

@Composable
private fun SectionCard(
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
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        }
    }
}

@Composable
private fun ActivitySection(state: StatisticsUiState.Ready) {
    SectionCard(title = stringResource(R.string.statistics_section_activity)) {
        // The 30-day / 7-day tiles were removed: for users with under a
        // month of history they all matched the all-time count, which made
        // the card look broken. The 12-week trend chart below carries the
        // recency signal much better than three rolling counters.
        StatTile(
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.statistics_total_purchases),
            value = state.totalPurchases.toString(),
        )
        if (state.purchasesPerDay.isNotEmpty()) {
            Text(
                text = stringResource(R.string.statistics_trend_label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DailyTrendChart(
                data = state.purchasesPerDay,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
            )
        }
        state.mostActiveDayOfWeek?.let { dow ->
            val dayNames = stringArrayResource(R.array.statistics_days_of_week)
            val dayName = dayNames.getOrNull(dow) ?: ""
            Text(
                text = stringResource(R.string.statistics_most_active_day_format, dayName),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ItemInsightsSection(state: StatisticsUiState.Ready) {
    SectionCard(title = stringResource(R.string.statistics_section_items)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatTile(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.statistics_library_total),
                value = state.totalItems.toString(),
            )
            StatTile(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.statistics_library_staples),
                value = state.stapleItems.toString(),
            )
            StatTile(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.statistics_library_priority),
                value = state.priorityItems.toString(),
            )
        }
        if (state.topItems.isNotEmpty()) {
            Text(
                text = stringResource(R.string.statistics_top_items),
                style = MaterialTheme.typography.titleSmall,
            )
            BarList(state.topItems)
        }
        if (state.staleItems.isNotEmpty()) {
            Text(
                text = stringResource(R.string.statistics_stale_items),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.statistics_stale_items_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.staleItems.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = row.name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(
                                R.string.statistics_stale_days_format,
                                row.daysSinceLastPurchase,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StoreInsightsSection(state: StatisticsUiState.Ready) {
    SectionCard(title = stringResource(R.string.statistics_section_stores)) {
        StatTile(
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.statistics_total_stores),
            value = state.totalStores.toString(),
        )
        state.mostShoppedStore?.let { top ->
            Text(
                text = stringResource(
                    R.string.statistics_most_shopped_format,
                    top.name,
                    top.count,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (state.purchasesByStore.isNotEmpty()) {
            BarList(state.purchasesByStore)
        }
    }
}

@Composable
private fun CategoryBreakdownSection(state: StatisticsUiState.Ready) {
    SectionCard(title = stringResource(R.string.statistics_section_categories)) {
        StatTile(
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.statistics_total_categories),
            value = state.totalCategories.toString(),
        )
        if (state.purchasesByCategory.isNotEmpty()) {
            val uncategorised = stringResource(R.string.statistics_uncategorised)
            val rows = state.purchasesByCategory.map { row ->
                if (row.name.isBlank()) row.copy(name = uncategorised) else row
            }
            BarList(rows)
        }
    }
}

@Composable
private fun StatTile(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Column {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BarList(rows: List<NamedCount>) {
    val max = rows.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            BarRow(name = row.name, count = row.count, max = max)
        }
    }
}

@Composable
private fun BarRow(name: String, count: Int, max: Int) {
    val fraction = (count.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surface

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Track + filled portion. Using two Boxes (rather than a LinearProgress
        // composable) keeps the bar height + radius matching the Material 3
        // surfaceVariant card without fighting the ProgressIndicator's defaults.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(trackColor),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(8.dp)
                    .background(barColor),
            )
        }
    }
}

/**
 * Sparkline-style line chart of [data]. Days with no recorded purchases
 * are skipped by the source query, so we draw whatever days the DAO
 * returned in their natural order — gaps render as a flat baseline if a
 * single isolated point is the only data, otherwise they connect.
 */
@Composable
private fun DailyTrendChart(
    data: List<DayCount>,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.primary
    val baselineColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = modifier) {
        if (data.isEmpty()) return@Canvas
        val maxCount = data.maxOf { it.count }.coerceAtLeast(1).toFloat()
        val w = size.width
        val h = size.height
        val pad = 4f
        val drawW = w - pad * 2
        val drawH = h - pad * 2
        // Baseline.
        drawLine(
            color = baselineColor,
            start = Offset(pad, h - pad),
            end = Offset(w - pad, h - pad),
            strokeWidth = 1f,
        )
        if (data.size == 1) {
            // Single dot: render as a small filled circle so the user
            // sees something instead of a flat line.
            val cx = pad + drawW / 2f
            val cy = h - pad - (data[0].count / maxCount) * drawH
            drawCircle(color = color, radius = 4f, center = Offset(cx, cy))
            return@Canvas
        }
        val stepX = drawW / (data.size - 1).toFloat()
        val path = Path()
        data.forEachIndexed { i, point ->
            val x = pad + i * stepX
            val y = h - pad - (point.count / maxCount) * drawH
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 3f),
        )
    }
}

