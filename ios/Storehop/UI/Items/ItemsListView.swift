import SwiftUI

struct ItemsListView: View {
    let onAddItem: () -> Void
    let onEditItem: (String) -> Void
    let onManageCategories: () -> Void
    let onOpenSettings: () -> Void

    @Environment(AppContainer.self) private var container
    @State private var viewModel: ItemsListViewModel?

    var body: some View {
        Group {
            if let viewModel {
                content(viewModel: viewModel)
            } else {
                ProgressView()
            }
        }
        .onAppear {
            if viewModel == nil {
                let vm = ItemsListViewModel(
                    itemRepository: container.itemRepository,
                    undoEventBus: container.undoEventBus,
                    preferencesRepository: container.userPreferences,
                    session: container.session
                )
                vm.bind()
                viewModel = vm
            }
        }
        .onDisappear { viewModel?.teardown() }
    }

    private func content(viewModel: ItemsListViewModel) -> some View {
        @Bindable var vm = viewModel
        return ZStack(alignment: .bottom) {
            List {
                let isEmpty: Bool = viewModel.sortMode == .alphabetic
                    ? viewModel.items.isEmpty
                    : viewModel.sections.isEmpty
                if isEmpty {
                    Section {
                        Text(viewModel.query.isEmpty
                             ? String(localized: "items_empty")
                             : String(format: String(localized: "items_search_no_match %@"), viewModel.query))
                            .font(StorehopTypography.bodyMedium)
                            .foregroundStyle(StorehopColors.onSurfaceVariant)
                    }
                } else {
                    switch viewModel.sortMode {
                    case .alphabetic:
                        Section {
                            ForEach(viewModel.items, id: \.item.id) { row in
                                ItemRowView(row: row)
                                    .contentShape(Rectangle())
                                    .onTapGesture { onEditItem(row.item.id) }
                            }
                        }
                    case .category:
                        ForEach(viewModel.sections) { section in
                            Section(header: Text(itemsSectionHeader(section)).font(StorehopTypography.titleSmall)) {
                                ForEach(section.rows, id: \.item.id) { row in
                                    ItemRowView(row: row)
                                        .contentShape(Rectangle())
                                        .onTapGesture { onEditItem(row.item.id) }
                                }
                            }
                        }
                    }
                }
                // Bottom inset so FAB doesn't cover the last row.
                Section { Color.clear.frame(height: 80).listRowBackground(Color.clear) }
            }
            .listStyle(.insetGrouped)
            .searchable(text: $vm.query, prompt: String(localized: "items_search_placeholder"))
            .refreshable {
                // Data is already reactive via GRDB ValueObservation; the
                // refreshable affordance is a UX beat — gives the user a
                // tactile "I asked for fresh data" confirmation. Matches
                // the Android pattern.
                try? await Task.sleep(nanoseconds: 500_000_000)
            }
            .navigationTitle(String(localized: "title_items"))
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        let next: SortMode = viewModel.sortMode == .alphabetic ? .category : .alphabetic
                        viewModel.setSortMode(next)
                    } label: {
                        Image(systemName: viewModel.sortMode == .alphabetic
                              ? "list.bullet.indent"
                              : "arrow.up.arrow.down")
                    }
                    .accessibilityLabel(String(localized: viewModel.sortMode == .alphabetic
                        ? "sort_category_cd"
                        : "sort_alphabetic_cd"))
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button(String(localized: "action_manage_categories"), systemImage: "tag", action: onManageCategories)
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                    .accessibilityLabel(String(localized: "action_more_options"))
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(action: onOpenSettings) {
                        Image(systemName: "gearshape")
                    }
                    .accessibilityLabel(String(localized: "action_settings"))
                }
            }

            FloatingAddItemButton(onTap: onAddItem)
                .padding(20)

            if let event = viewModel.pendingUndo {
                UndoBar(
                    message: undoMessage(for: event),
                    onUndo: { viewModel.undoItemDelete(event) },
                    onDismiss: { viewModel.dismissUndo() }
                )
                .padding(.horizontal, 16)
                .padding(.bottom, 88)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: viewModel.pendingUndo)
    }

    /// Resolves the localized label for a section header. Maps the
    /// `__uncategorised__` sentinel to the localized "(uncategorised)"
    /// string; otherwise honors `categoryNameKey` (seeded categories) and
    /// falls back to the raw name (user-added categories).
    private func itemsSectionHeader(_ section: ItemsCategorySection) -> String {
        if section.categoryName == itemsUncategorisedSentinel {
            return String(localized: "items_uncategorised_label")
        }
        if let key = section.categoryNameKey {
            return String(localized: String.LocalizationValue(key))
        }
        return section.categoryName
    }

    private func undoMessage(for event: UndoEvent) -> String {
        switch event {
        case .itemDeleted(_, let name):
            return String(format: String(localized: "undo_item_deleted %@"), name)
        }
    }
}

// MARK: - Subviews

private struct ItemRowView: View {
    let row: ItemWithCategoryAndStores

    var body: some View {
        HStack(spacing: 12) {
            ItemThumbnail(name: row.item.name, imageUrl: row.item.imageUrl)
            VStack(alignment: .leading, spacing: 2) {
                Text(row.item.name)
                    .font(StorehopTypography.bodyLarge)
                if let brand = row.item.brand, !brand.isEmpty {
                    Text(brand)
                        .font(StorehopTypography.bodySmall)
                        .foregroundStyle(StorehopColors.onSurfaceVariant)
                }
                if let categoryName = row.category?.name, !categoryName.isEmpty {
                    Text(categoryLabel(row.category))
                        .font(StorehopTypography.labelSmall)
                        .foregroundStyle(StorehopColors.onSurfaceVariant)
                }
            }
            Spacer()
            if row.item.isStaple {
                Image(systemName: "pin.fill")
                    .foregroundStyle(StorehopColors.secondary)
                    .imageScale(.small)
            }
            if row.item.isPriority {
                Image(systemName: "star.fill")
                    .foregroundStyle(.yellow)
                    .imageScale(.small)
            }
            Image(systemName: "chevron.right")
                .font(.footnote)
                .foregroundStyle(StorehopColors.onSurfaceVariant)
        }
    }

    private func categoryLabel(_ category: Category?) -> String {
        guard let category else { return "" }
        if let key = category.nameKey {
            return String(localized: String.LocalizationValue(key))
        }
        return category.name
    }
}

private struct ItemThumbnail: View {
    let name: String
    let imageUrl: String?

    var body: some View {
        Group {
            if let imageUrl, let url = URL(string: imageUrl) {
                AsyncImage(url: url) { image in
                    image.resizable().scaledToFill()
                } placeholder: { placeholderCircle }
                .frame(width: 40, height: 40)
                .clipShape(Circle())
            } else {
                placeholderCircle
            }
        }
    }

    private var placeholderCircle: some View {
        Circle()
            .fill(StorehopColors.surfaceVariant)
            .frame(width: 40, height: 40)
            .overlay {
                Text(name.first.map { String($0).uppercased() } ?? "?")
                    .font(StorehopTypography.labelMedium)
                    .foregroundStyle(StorehopColors.onSurfaceVariant)
            }
    }
}

private struct FloatingAddItemButton: View {
    let onTap: () -> Void

    var body: some View {
        HStack {
            Spacer()
            Button(action: onTap) {
                Image(systemName: "plus")
                    .font(.title2)
                    .padding(20)
                    .background(StorehopColors.primary, in: Circle())
                    .foregroundStyle(StorehopColors.onPrimary)
                    .shadow(radius: 4, y: 2)
            }
            .accessibilityLabel(String(localized: "action_add_item"))
        }
    }
}
