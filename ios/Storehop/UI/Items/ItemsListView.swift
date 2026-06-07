import SwiftUI

struct ItemsListView: View {
    let onAddItem: () -> Void
    let onEditItem: (String) -> Void
    let onManageCategories: () -> Void
    let onOpenSettings: () -> Void

    @Environment(AppContainer.self) private var container
    @State private var viewModel: ItemsListViewModel?
    @State private var showBulkPicker: Bool = false

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
                viewModel = ItemsListViewModel(
                    itemRepository: container.itemRepository,
                    storeRepository: container.storeRepository,
                    undoEventBus: container.undoEventBus,
                    preferencesRepository: container.userPreferences,
                    session: container.session
                )
            }
            // Always (re)bind on appear. NavigationStack pushes (e.g. opening
            // the item form) trigger `.onDisappear` → `teardown()`, which
            // cancels the GRDB ValueObservation subscription. Without
            // rebinding on the return pop, new items added in the form
            // wouldn't surface in the list until the next session-uid event,
            // which never fires under a stable LocalOnly session. `bind()`
            // is idempotent — it cancels any prior subscription first.
            viewModel?.bind()
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
                        let trimmed = viewModel.query.trimmingCharacters(in: .whitespacesAndNewlines)
                        if trimmed.isEmpty {
                            EmptyState(
                                systemImage: "tray",
                                title: L("items_empty_no_query_title"),
                                message: L("items_empty_no_query_body")
                            )
                            .listRowBackground(Color.clear)
                            .listRowInsets(EdgeInsets())
                        } else {
                            EmptyState(
                                systemImage: "magnifyingglass",
                                title: L("items_empty_search_title"),
                                message: String(
                                    format: L("items_empty_search_body %@"),
                                    trimmed
                                )
                            )
                            .listRowBackground(Color.clear)
                            .listRowInsets(EdgeInsets())
                        }
                    }
                } else {
                    switch viewModel.sortMode {
                    case .alphabetic:
                        Section {
                            ForEach(viewModel.items, id: \.item.id) { row in
                                itemRow(viewModel: viewModel, row: row)
                            }
                        }
                    case .category:
                        ForEach(viewModel.sections) { section in
                            Section(header: Text(itemsSectionHeader(section)).font(StorehopTypography.titleSmall)) {
                                ForEach(section.rows, id: \.item.id) { row in
                                    itemRow(viewModel: viewModel, row: row)
                                }
                            }
                        }
                    }
                }
                // Bottom inset so FAB doesn't cover the last row.
                Section { Color.clear.frame(height: 80).listRowBackground(Color.clear) }
            }
            .listStyle(.insetGrouped)
            .searchable(text: $vm.query, prompt: L("items_search_placeholder"))
            .refreshable {
                // Data is already reactive via GRDB ValueObservation; the
                // refreshable affordance is a UX beat — gives the user a
                // tactile "I asked for fresh data" confirmation. Matches
                // the Android pattern.
                try? await Task.sleep(nanoseconds: 500_000_000)
            }
            .navigationTitle(viewModel.isInSelectionMode
                ? String(localized: "items_selection_count \(viewModel.selectedItemIds.count)", bundle: currentLanguageBundle())
                : L("title_items"))
            .navigationBarTitleDisplayMode(viewModel.isInSelectionMode ? .inline : .automatic)
            .toolbar {
                if viewModel.isInSelectionMode {
                    // v0.8.1: contextual toolbar — leading X exits selection,
                    // trailing "Tag to stores…" opens the bulk-tag sheet.
                    ToolbarItem(placement: .topBarLeading) {
                        Button(action: viewModel.clearSelection) {
                            Image(systemName: "xmark")
                        }
                        .accessibilityLabel(L("items_selection_exit_cd"))
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        Button {
                            showBulkPicker = true
                        } label: {
                            Image(systemName: "storefront")
                        }
                        .accessibilityLabel(L("items_action_tag_to_stores"))
                    }
                } else {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button {
                            let next: SortMode = viewModel.sortMode == .alphabetic ? .category : .alphabetic
                            viewModel.setSortMode(next)
                        } label: {
                            Image(systemName: viewModel.sortMode == .alphabetic
                                  ? "list.bullet.indent"
                                  : "arrow.up.arrow.down")
                        }
                        .accessibilityLabel(L(viewModel.sortMode == .alphabetic
                            ? "sort_category_cd"
                            : "sort_alphabetic_cd"))
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        Menu {
                            Button(L("action_manage_categories"), systemImage: "tag", action: onManageCategories)
                        } label: {
                            Image(systemName: "ellipsis.circle")
                        }
                        .accessibilityLabel(L("action_more_options"))
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        Button(action: onOpenSettings) {
                            Image(systemName: "gearshape")
                        }
                        .accessibilityLabel(L("action_settings"))
                    }
                }
            }

            // Hide FAB in selection mode -- a "+" action while
            // multi-selecting items would be ambiguous.
            if !viewModel.isInSelectionMode {
                FloatingAddItemButton(onTap: onAddItem)
                    .padding(20)
            }

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
        .sheet(isPresented: $showBulkPicker) {
            BulkStorePickerSheet(
                stores: viewModel.stores,
                selectedItemCount: viewModel.selectedItemIds.count,
                onApply: { picked in viewModel.applyBulkStores(picked) },
                onDismiss: { showBulkPicker = false }
            )
        }
    }

    /// v0.8.1: one row, with selection-mode awareness. Pulled out as a
    /// helper so both the alphabetic and category-sorted branches above
    /// build identical rows without duplicating the tap/long-press routing.
    @ViewBuilder
    private func itemRow(viewModel: ItemsListViewModel, row: ItemWithCategoryAndStores) -> some View {
        let isSelected = viewModel.selectedItemIds.contains(row.item.id)
        let inSelection = viewModel.isInSelectionMode
        ItemRowView(
            row: row,
            isNeeded: viewModel.neededItemIds.contains(row.item.id),
            isSelected: isSelected,
            isInSelectionMode: inSelection,
            onTap: {
                if inSelection {
                    viewModel.toggleSelection(row.item.id)
                } else {
                    onEditItem(row.item.id)
                }
            },
            onLongPress: { viewModel.toggleSelection(row.item.id) },
            onToggleNeeded: {
                viewModel.toggleNeededAtAllStores(
                    itemId: row.item.id,
                    currentlyNeeded: viewModel.neededItemIds.contains(row.item.id)
                )
            }
        )
        .listRowBackground(isSelected ? StorehopColors.primaryContainer.opacity(0.4) : nil)
    }

    /// Resolves the localized label for a section header. Maps the
    /// `__uncategorised__` sentinel to the localized "(uncategorised)"
    /// string; otherwise honors `categoryNameKey` (seeded categories) and
    /// falls back to the raw name (user-added categories).
    private func itemsSectionHeader(_ section: ItemsCategorySection) -> String {
        if section.categoryName == itemsUncategorisedSentinel {
            return L("items_uncategorised_label")
        }
        if let key = section.categoryNameKey {
            return L(key)
        }
        return section.categoryName
    }

    private func undoMessage(for event: UndoEvent) -> String {
        switch event {
        case .itemDeleted(_, let name):
            return String(format: L("undo_item_deleted %@"), name)
        }
    }
}

// MARK: - Subviews

private struct ItemRowView: View {
    let row: ItemWithCategoryAndStores
    let isNeeded: Bool
    /// v0.8.1: row appears in the bulk-tag selection set. Toggles the
    /// checkmark indicator + background tint (applied at the host level).
    let isSelected: Bool
    /// v0.8.1: the screen is in bulk-tag selection mode (any row is
    /// selected). Hides the +/- toggle and rewires the row tap to
    /// `toggleSelection` instead of opening the editor.
    let isInSelectionMode: Bool
    let onTap: () -> Void
    let onLongPress: () -> Void
    let onToggleNeeded: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            // Tap area for editing or selection. The trailing +/- button
            // has its own tap target so it doesn't bubble into the row tap.
            HStack(spacing: 12) {
                if isInSelectionMode {
                    // v0.8.1: leading checkbox-style indicator replaces the
                    // avatar so the row width stays identical and the
                    // "this row is/isn't selected" cue is unambiguous.
                    ZStack {
                        Circle()
                            .fill(isSelected ? StorehopColors.primary : StorehopColors.surfaceVariant)
                            .frame(width: 40, height: 40)
                        if isSelected {
                            Image(systemName: "checkmark")
                                .foregroundStyle(StorehopColors.onPrimary)
                        }
                    }
                } else {
                    ItemThumbnail(name: row.item.name, imageUrl: row.item.imageUrl)
                }
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
            }
            .contentShape(Rectangle())
            .onTapGesture { onTap() }
            // v0.8.1: long-press enters selection mode (first selected
            // item). Subsequent long-presses behave the same as a tap
            // in selection mode (the host wires both to toggleSelection
            // via onTap when inSelection mode is already active).
            .onLongPressGesture(minimumDuration: 0.4) { onLongPress() }

            // v0.6.1: +/- toggle. Hidden in v0.8.1 selection mode — the
            // row tap belongs to toggleSelection then, so a +/- here
            // would be ambiguous. Disabled when no tagged stores —
            // nothing to add the item to.
            if !isInSelectionMode {
                Button(action: onToggleNeeded) {
                    Image(systemName: isNeeded ? "minus.circle.fill" : "plus.circle.fill")
                        .font(.title3)
                        .foregroundStyle(row.stores.isEmpty
                            ? StorehopColors.onSurfaceVariant
                            : StorehopColors.primary)
                }
                .buttonStyle(.plain)
                .disabled(row.stores.isEmpty)
                .accessibilityLabel(L(isNeeded
                    ? "action_remove_from_list"
                    : "action_add_to_list"))
            }
        }
    }

    private func categoryLabel(_ category: Category?) -> String {
        guard let category else { return "" }
        if let key = category.nameKey {
            return L(key)
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
            .accessibilityLabel(L("action_add_item"))
        }
    }
}
