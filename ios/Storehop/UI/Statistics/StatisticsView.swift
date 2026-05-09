import Charts
import SwiftUI

/// Settings → Statistics. Mirrors Android's `StatisticsScreen`. Sourced
/// from `StatisticsViewModel` which combines purchase aggregations with
/// the user's library counts.
///
/// Sections (post the v0.5.7 simplification — the redundant 30d/7d tiles
/// are no longer rendered):
///   1. Activity: all-time count + 12-week trend chart + most active day
///   2. Items: library counters + top 10 + stale items
///   3. Stores: total + per-store breakdown
///   4. Categories: total + per-category breakdown
struct StatisticsView: View {
    @Environment(AppContainer.self) private var container
    @State private var viewModel: StatisticsViewModel?

    var body: some View {
        Group {
            if let viewModel {
                content(viewModel: viewModel)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(String(localized: "statistics_title"))
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if viewModel == nil {
                let vm = StatisticsViewModel(
                    purchases: container.purchaseHistoryRepository,
                    itemRepository: container.itemRepository,
                    storeRepository: container.storeRepository,
                    categoryRepository: container.categoryRepository,
                    session: container.session,
                    clock: container.clock
                )
                vm.bind()
                viewModel = vm
            }
        }
        .onDisappear { viewModel?.teardown() }
    }

    @ViewBuilder
    private func content(viewModel: StatisticsViewModel) -> some View {
        switch viewModel.state {
        case .loading:
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .empty:
            EmptyState()
        case .ready(let state):
            ReadyContent(state: state)
        }
    }
}

// MARK: - States

private struct EmptyState: View {
    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "chart.bar.xaxis")
                .font(.largeTitle)
                .foregroundStyle(StorehopColors.onSurfaceVariant)
            Text(String(localized: "statistics_empty_title"))
                .font(StorehopTypography.titleMedium)
            Text(String(localized: "statistics_empty_body"))
                .font(StorehopTypography.bodyMedium)
                .foregroundStyle(StorehopColors.onSurfaceVariant)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding()
    }
}

private struct ReadyContent: View {
    let state: StatisticsReadyState

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                ActivitySection(state: state)
                ItemsSection(state: state)
                StoresSection(state: state)
                CategoriesSection(state: state)
            }
            .padding(.vertical, 16)
        }
    }
}

// MARK: - Sections

private struct ActivitySection: View {
    let state: StatisticsReadyState

    var body: some View {
        SectionCard(title: String(localized: "statistics_section_activity")) {
            // Match Android's post-v0.5.7 layout: one big "All-time" tile +
            // trend chart + most-active-day. The 30d/7d tiles were removed
            // because for short-history users they all matched the all-time
            // count, making the card look broken.
            StatTile(
                label: String(localized: "statistics_total_purchases"),
                value: "\(state.totalPurchases)"
            )
            if !state.purchasesPerDay.isEmpty {
                Text(String(localized: "statistics_trend_label"))
                    .font(StorehopTypography.bodySmall)
                    .foregroundStyle(StorehopColors.onSurfaceVariant)
                    .frame(maxWidth: .infinity, alignment: .leading)
                DailyTrendChart(data: state.purchasesPerDay)
                    .frame(height: 96)
            }
            if let dow = state.mostActiveDayOfWeek {
                let dayNames = mostActiveDayNames()
                if dow >= 0 && dow < dayNames.count {
                    Text(String(format: String(localized: "statistics_most_active_day_format %@"), dayNames[dow]))
                        .font(StorehopTypography.bodyMedium)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
    }

    private func mostActiveDayNames() -> [String] {
        // SQLite's strftime('%w', ...) returns 0 = Sunday through 6 = Saturday.
        // Match that ordering with localized names.
        [
            String(localized: "day_sunday"),
            String(localized: "day_monday"),
            String(localized: "day_tuesday"),
            String(localized: "day_wednesday"),
            String(localized: "day_thursday"),
            String(localized: "day_friday"),
            String(localized: "day_saturday"),
        ]
    }
}

private struct ItemsSection: View {
    let state: StatisticsReadyState

    var body: some View {
        SectionCard(title: String(localized: "statistics_section_items")) {
            HStack(spacing: 8) {
                StatTile(label: String(localized: "statistics_library_total"), value: "\(state.totalItems)")
                StatTile(label: String(localized: "statistics_library_staples"), value: "\(state.stapleItems)")
                StatTile(label: String(localized: "statistics_library_priority"), value: "\(state.priorityItems)")
            }
            if !state.topItems.isEmpty {
                Text(String(localized: "statistics_top_items"))
                    .font(StorehopTypography.titleSmall)
                    .frame(maxWidth: .infinity, alignment: .leading)
                BarList(rows: state.topItems)
            }
            if !state.staleItems.isEmpty {
                Text(String(localized: "statistics_stale_items"))
                    .font(StorehopTypography.titleSmall)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(String(localized: "statistics_stale_items_subtitle"))
                    .font(StorehopTypography.bodySmall)
                    .foregroundStyle(StorehopColors.onSurfaceVariant)
                    .frame(maxWidth: .infinity, alignment: .leading)
                VStack(spacing: 4) {
                    ForEach(state.staleItems) { row in
                        HStack {
                            Text(row.name)
                                .font(StorehopTypography.bodyMedium)
                            Spacer()
                            Text(String(format: String(localized: "statistics_stale_days_format %lld"), row.daysSinceLastPurchase))
                                .font(StorehopTypography.bodySmall)
                                .foregroundStyle(StorehopColors.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

private struct StoresSection: View {
    let state: StatisticsReadyState

    var body: some View {
        SectionCard(title: String(localized: "statistics_section_stores")) {
            StatTile(label: String(localized: "statistics_total_stores"), value: "\(state.totalStores)")
            if let most = state.mostShoppedStore {
                Text(String(format: String(localized: "statistics_most_shopped_format %@ %lld"), most.name, most.count))
                    .font(StorehopTypography.bodyMedium)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            if !state.purchasesByStore.isEmpty {
                BarList(rows: state.purchasesByStore)
            }
        }
    }
}

private struct CategoriesSection: View {
    let state: StatisticsReadyState

    var body: some View {
        SectionCard(title: String(localized: "statistics_section_categories")) {
            StatTile(label: String(localized: "statistics_total_categories"), value: "\(state.totalCategories)")
            if !state.purchasesByCategory.isEmpty {
                BarList(rows: state.purchasesByCategory.map { row in
                    NamedCount(
                        name: row.name.isEmpty ? String(localized: "statistics_uncategorised") : row.name,
                        count: row.count
                    )
                })
            }
        }
    }
}

// MARK: - Pieces

private struct SectionCard<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .font(StorehopTypography.titleMedium)
            content
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(StorehopColors.surfaceVariant.opacity(0.4), in: RoundedRectangle(cornerRadius: StorehopShape.cornerMedium))
        .padding(.horizontal, 16)
    }
}

private struct StatTile: View {
    let label: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(value)
                .font(StorehopTypography.titleLarge)
                .fontWeight(.semibold)
            Text(label)
                .font(StorehopTypography.bodySmall)
                .foregroundStyle(StorehopColors.onSurfaceVariant)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(StorehopColors.surface, in: RoundedRectangle(cornerRadius: StorehopShape.cornerSmall))
    }
}

private struct DailyTrendChart: View {
    let data: [DayCount]

    var body: some View {
        Chart(data, id: \.day) { row in
            BarMark(
                x: .value("Day", row.day),
                y: .value("Count", row.count)
            )
            .foregroundStyle(StorehopColors.primary)
        }
        .chartXAxis(.hidden)
        .chartYAxis {
            AxisMarks(position: .leading)
        }
    }
}

/// Horizontal bar list used by Items / Stores / Categories breakdowns.
private struct BarList: View {
    let rows: [NamedCount]

    var body: some View {
        let max = rows.map(\.count).max() ?? 1
        VStack(spacing: 6) {
            ForEach(rows) { row in
                BarRow(name: row.name, count: row.count, max: max)
            }
        }
    }
}

private struct BarRow: View {
    let name: String
    let count: Int
    let max: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(name)
                    .font(StorehopTypography.bodyMedium)
                Spacer()
                Text("\(count)")
                    .font(StorehopTypography.bodySmall)
                    .foregroundStyle(StorehopColors.onSurfaceVariant)
            }
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Rectangle()
                        .fill(StorehopColors.surfaceVariant)
                        .frame(height: 6)
                    Rectangle()
                        .fill(StorehopColors.primary)
                        .frame(width: max > 0 ? geo.size.width * CGFloat(count) / CGFloat(max) : 0, height: 6)
                }
                .clipShape(Capsule())
            }
            .frame(height: 6)
        }
    }
}
