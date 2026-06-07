import SwiftUI

struct ShopAtStoreView: View {
    let storeId: String
    let onEditAisles: () -> Void
    let onEditItem: (String) -> Void

    @Environment(AppContainer.self) private var container
    @State private var viewModel: ShopAtStoreViewModel?
    @FocusState private var quickAddFocused: Bool

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
                viewModel = ShopAtStoreViewModel(
                    storeId: storeId,
                    shoppingRepository: container.shoppingRepository,
                    itemRepository: container.itemRepository,
                    storeRepository: container.storeRepository,
                    preferencesRepository: container.userPreferences,
                    session: container.session,
                    sessionTracker: container.shoppingSessionTracker
                )
            }
            viewModel?.bind()
        }
        .onDisappear { viewModel?.teardown() }
    }

    private func content(viewModel: ShopAtStoreViewModel) -> some View {
        @Bindable var vm = viewModel
        return ZStack(alignment: .bottom) {
            List {
                if !viewModel.criticalNames.isEmpty {
                    Section {
                        CriticalNeedsBanner(names: viewModel.criticalNames)
                            .listRowBackground(Color.clear)
                            .listRowInsets(EdgeInsets())
                    }
                }
                // Empty state when there are no rows in the active sort mode.
                let isEmpty = viewModel.sortMode == .alphabetic
                    ? viewModel.alphabeticRows.isEmpty
                    : viewModel.sections.isEmpty
                if isEmpty {
                    Section {
                        let trimmed = viewModel.query.trimmingCharacters(in: .whitespacesAndNewlines)
                        if trimmed.isEmpty {
                            EmptyState(
                                systemImage: "cart",
                                title: L("shop_empty_no_query_title"),
                                message: L("shop_empty_no_query_body")
                            )
                            .listRowBackground(Color.clear)
                            .listRowInsets(EdgeInsets())
                        } else {
                            EmptyState(
                                systemImage: "magnifyingglass",
                                title: L("shop_empty_search_title"),
                                message: String(
                                    format: L("shop_empty_search_body %@"),
                                    trimmed
                                )
                            )
                            .listRowBackground(Color.clear)
                            .listRowInsets(EdgeInsets())
                        }
                    }
                } else {
                    switch viewModel.sortMode {
                    case .category:
                        ForEach(viewModel.sections) { section in
                            Section(header: Text(sectionHeader(section)).font(StorehopTypography.titleSmall)) {
                                ForEach(section.rows) { row in
                                    shoppingRow(row, viewModel: viewModel)
                                }
                            }
                        }
                    case .alphabetic:
                        Section {
                            ForEach(viewModel.alphabeticRows) { row in
                                shoppingRow(row, viewModel: viewModel)
                            }
                        }
                    }
                }
                // Bottom inset so quick-add bar doesn't cover the last row.
                Section { Color.clear.frame(height: 80).listRowBackground(Color.clear) }
            }
            .listStyle(.insetGrouped)
            .searchable(text: $vm.query, prompt: L("shop_search_placeholder"))
            .refreshable {
                // Data is already reactive via GRDB ValueObservation; the
                // refreshable affordance is a UX beat. Matches Android.
                try? await Task.sleep(nanoseconds: 500_000_000)
            }
            .navigationTitle(viewModel.store?.name ?? "")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        let next: SortMode = viewModel.sortMode == .category ? .alphabetic : .category
                        viewModel.setSortMode(next)
                    } label: {
                        Image(systemName: viewModel.sortMode == .category
                              ? "arrow.up.arrow.down"
                              : "list.bullet.indent")
                    }
                    .accessibilityLabel(L(viewModel.sortMode == .category
                        ? "sort_alphabetic_cd"
                        : "sort_category_cd"))
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        viewModel.setShowPurchased(!viewModel.showPurchased)
                    } label: {
                        Image(systemName: viewModel.showPurchased
                              ? "checkmark.circle.fill"
                              : "checkmark.circle")
                    }
                    .accessibilityLabel(L(viewModel.showPurchased
                        ? "action_hide_purchased"
                        : "action_show_purchased"))
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button(L("action_edit_aisles"), systemImage: "list.number", action: onEditAisles)
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                    .accessibilityLabel(L("action_more_options"))
                }
            }

            VStack(spacing: 0) {
                if let snackbarName = viewModel.lastPurchaseDisplayName {
                    UndoBar(
                        message: String(format: L("snackbar_purchased %@"), snackbarName),
                        onUndo: {
                            // Undo targets the most recent snapshot regardless of which
                            // row produced it — see ShopAtStoreViewModel.lastPurchaseSnapshot.
                            if let rowItemId = findLastPurchasedItemId(in: viewModel) {
                                viewModel.undoLastPurchase(itemId: rowItemId)
                            } else {
                                viewModel.dismissPurchaseSnackbar()
                            }
                        },
                        onDismiss: viewModel.dismissPurchaseSnackbar
                    )
                    .padding(.horizontal, 16)
                    .padding(.bottom, 4)
                }
                if !viewModel.quickAddSuggestions.isEmpty {
                    QuickAddSuggestionsList(
                        suggestions: viewModel.quickAddSuggestions,
                        onPick: { itemId in
                            viewModel.pickExistingItem(itemId: itemId)
                            quickAddFocused = false
                        }
                    )
                }
                QuickAddBar(text: $vm.quickAddInput, focused: $quickAddFocused) {
                    viewModel.submitQuickAddText()
                    quickAddFocused = false
                }
            }
        }
        .animation(.easeInOut(duration: 0.2), value: viewModel.lastPurchaseDisplayName)
    }

    /// Single row builder shared between both sort modes. The single tap
    /// toggles purchased; the context menu (iOS HIG-idiomatic alternative
    /// to Android's long-press) offers the "Edit" navigation, the new
    /// v0.6.0 affordance for tagging stores without leaving the screen.
    private func shoppingRow(_ row: ShoppingRow, viewModel: ShopAtStoreViewModel) -> some View {
        ShoppingRowView(row: row)
            .contentShape(Rectangle())
            .onTapGesture {
                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                viewModel.togglePurchased(row: row)
            }
            .contextMenu {
                Button {
                    onEditItem(row.itemId)
                } label: {
                    Label(L("action_edit_item"), systemImage: "pencil")
                }
            }
    }

    private func sectionHeader(_ section: ShoppingCategorySection) -> String {
        if let key = section.categoryNameKey {
            return L(key)
        }
        return section.categoryName
    }

    private func findLastPurchasedItemId(in viewModel: ShopAtStoreViewModel) -> String? {
        guard let name = viewModel.lastPurchaseDisplayName else { return nil }
        for section in viewModel.sections {
            if let row = section.rows.first(where: { $0.itemName == name }) {
                return row.itemId
            }
        }
        return nil
    }
}

// MARK: - Subviews

private struct ShoppingRowView: View {
    let row: ShoppingRow

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: row.isNeeded ? "circle" : "checkmark.circle.fill")
                .foregroundStyle(row.isNeeded ? StorehopColors.outline : StorehopColors.primary)
                .imageScale(.large)
            ItemThumbnail(name: row.itemName, imageUrl: row.imageUrl)
            VStack(alignment: .leading, spacing: 2) {
                Text(row.itemName)
                    .font(StorehopTypography.bodyLarge)
                    .strikethrough(!row.isNeeded)
                    .foregroundStyle(row.isNeeded ? StorehopColors.onSurface : StorehopColors.onSurfaceVariant)
                if let brand = row.brand, !brand.isEmpty {
                    Text(brand)
                        .font(StorehopTypography.bodySmall)
                        .foregroundStyle(StorehopColors.onSurfaceVariant)
                }
            }
            Spacer()
            if row.isPriority {
                Image(systemName: "star.fill")
                    .foregroundStyle(.yellow)
                    .imageScale(.small)
            }
            if row.isStaple {
                Image(systemName: "pin.fill")
                    .foregroundStyle(StorehopColors.secondary)
                    .imageScale(.small)
            }
            if let qty = row.quantity, !qty.isEmpty {
                Text("× \(qty)")
                    .font(StorehopTypography.labelMedium)
                    .foregroundStyle(StorehopColors.onSurfaceVariant)
            }
        }
        .opacity(row.isNeeded ? 1.0 : 0.6)
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
                } placeholder: {
                    placeholderCircle
                }
                .frame(width: 36, height: 36)
                .clipShape(Circle())
            } else {
                placeholderCircle
            }
        }
    }

    private var placeholderCircle: some View {
        Circle()
            .fill(StorehopColors.surfaceVariant)
            .frame(width: 36, height: 36)
            .overlay {
                Text(name.first.map { String($0).uppercased() } ?? "?")
                    .font(StorehopTypography.labelMedium)
                    .foregroundStyle(StorehopColors.onSurfaceVariant)
            }
    }
}

/// Collapsed by default: with many criticals the comma-joined list grew
/// tall enough to push the rest of the screen off the fold. Mirrors
/// Android's `CriticalBanner` collapse pattern from v0.6.0.
private struct CriticalNeedsBanner: View {
    let names: [String]
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .center, spacing: 12) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(StorehopColors.onPrimaryContainer)
                Text(String(format: L("critical_at_this_store %lld"), names.count))
                    .font(StorehopTypography.titleSmall.weight(.semibold))
                    .foregroundStyle(StorehopColors.onPrimaryContainer)
                Spacer()
                Image(systemName: expanded ? "chevron.up" : "chevron.down")
                    .foregroundStyle(StorehopColors.onPrimaryContainer)
                    .accessibilityHidden(true)
            }
            if expanded {
                Text(names.joined(separator: ", "))
                    .font(StorehopTypography.bodyMedium)
                    .foregroundStyle(StorehopColors.onPrimaryContainer)
                    .padding(.top, 6)
                    .padding(.leading, 28)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(StorehopColors.primaryContainer, in: RoundedRectangle(cornerRadius: StorehopShape.cornerMedium))
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .onTapGesture { withAnimation(.easeInOut(duration: 0.2)) { expanded.toggle() } }
        .accessibilityElement(children: .combine)
        .accessibilityHint(L(expanded
            ? "critical_banner_collapse_cd"
            : "critical_banner_expand_cd"))
        // Stable seam for XCUITest — accessibilityHint isn't queryable
        // through `app.elements[...]`, but accessibilityIdentifier is.
        // Tests assert the expand/collapse transition by watching the
        // identifier swap.
        .accessibilityIdentifier(expanded
            ? "critical_banner_expanded"
            : "critical_banner_collapsed")
    }
}

private struct QuickAddBar: View {
    @Binding var text: String
    var focused: FocusState<Bool>.Binding
    let onSubmit: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            TextField(L("shop_quick_add_placeholder"), text: $text)
                .focused(focused)
                .textInputAutocapitalization(.sentences)
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .background(StorehopColors.surfaceVariant, in: RoundedRectangle(cornerRadius: StorehopShape.cornerMedium))
                .submitLabel(.done)
                .onSubmit(onSubmit)
            Button(action: onSubmit) {
                Image(systemName: "plus.circle.fill")
                    .foregroundStyle(StorehopColors.primary)
                    .imageScale(.large)
            }
            .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            .accessibilityLabel(L("action_add_item"))
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 12)
        .background(StorehopColors.surface)
    }
}

/// Autocomplete list shown above [QuickAddBar] while the user is adding an
/// item. Tap a row → tag that existing master-Items entry to this store
/// (no duplicate). Capped height so the list never pushes the input field
/// off-screen on small devices.
private struct QuickAddSuggestionsList: View {
    let suggestions: [QuickAddSuggestion]
    let onPick: (String) -> Void

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                ForEach(suggestions) { suggestion in
                    Button {
                        onPick(suggestion.itemId)
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(suggestion.name)
                                    .font(StorehopTypography.bodyLarge)
                                    .foregroundStyle(StorehopColors.onSurface)
                                let secondary = [
                                    suggestion.brand?.nilIfEmpty,
                                    suggestion.categoryName?.nilIfEmpty,
                                ]
                                .compactMap { $0 }
                                .joined(separator: " · ")
                                if !secondary.isEmpty {
                                    Text(secondary)
                                        .font(StorehopTypography.bodySmall)
                                        .foregroundStyle(StorehopColors.onSurfaceVariant)
                                }
                            }
                            Spacer()
                            if suggestion.isStaple {
                                Image(systemName: "pin.fill")
                                    .foregroundStyle(StorehopColors.secondary)
                                    .imageScale(.small)
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                    }
                    .buttonStyle(.plain)
                    Divider().padding(.leading, 16)
                }
            }
        }
        .frame(maxHeight: 240)
        .background(StorehopColors.surface)
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}

