import SwiftUI

struct ShopAtStoreView: View {
    let storeId: String
    let onEditAisles: () -> Void

    @Environment(AppContainer.self) private var container
    @State private var viewModel: ShopAtStoreViewModel?
    @State private var quickAddText = ""
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
                let vm = ShopAtStoreViewModel(
                    storeId: storeId,
                    shoppingRepository: container.shoppingRepository,
                    itemRepository: container.itemRepository,
                    storeRepository: container.storeRepository,
                    session: container.session,
                    sessionTracker: container.shoppingSessionTracker
                )
                vm.bind()
                viewModel = vm
            }
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
                ForEach(viewModel.sections) { section in
                    Section(header: Text(sectionHeader(section)).font(StorehopTypography.titleSmall)) {
                        ForEach(section.rows) { row in
                            ShoppingRowView(row: row)
                                .contentShape(Rectangle())
                                .onTapGesture {
                                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                    viewModel.togglePurchased(row: row)
                                }
                        }
                    }
                }
                // Bottom inset so quick-add bar doesn't cover the last row.
                Section { Color.clear.frame(height: 80).listRowBackground(Color.clear) }
            }
            .listStyle(.insetGrouped)
            .searchable(text: $vm.query, prompt: String(localized: "shop_search_placeholder"))
            .refreshable {
                // Data is already reactive via GRDB ValueObservation; the
                // refreshable affordance is a UX beat. Matches Android.
                try? await Task.sleep(nanoseconds: 500_000_000)
            }
            .navigationTitle(viewModel.store?.name ?? "")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button(String(localized: "action_edit_aisles"), systemImage: "list.number", action: onEditAisles)
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                    .accessibilityLabel(String(localized: "action_more_options"))
                }
            }

            VStack(spacing: 0) {
                if let snackbarName = viewModel.lastPurchaseDisplayName {
                    PurchasedSnackbar(
                        name: snackbarName,
                        onUndo: {
                            // Undo targets the most recent snapshot regardless of which
                            // row produced it — see ShopAtStoreViewModel.lastPurchaseSnapshot.
                            // We need the itemId; pass through the rawRows or pass it
                            // via the snackbar's bound row. Here we walk the sections.
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
                QuickAddBar(text: $quickAddText, focused: $quickAddFocused) {
                    viewModel.quickAdd(name: quickAddText)
                    quickAddText = ""
                    quickAddFocused = false
                }
            }
        }
        .animation(.easeInOut(duration: 0.2), value: viewModel.lastPurchaseDisplayName)
    }

    private func sectionHeader(_ section: ShoppingCategorySection) -> String {
        if let key = section.categoryNameKey {
            return String(localized: String.LocalizationValue(key))
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

private struct CriticalNeedsBanner: View {
    let names: [String]

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(StorehopColors.onPrimaryContainer)
            Text(names.joined(separator: ", "))
                .font(StorehopTypography.bodyMedium)
                .foregroundStyle(StorehopColors.onPrimaryContainer)
            Spacer()
        }
        .padding(12)
        .background(StorehopColors.primaryContainer, in: RoundedRectangle(cornerRadius: StorehopShape.cornerMedium))
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
    }
}

private struct QuickAddBar: View {
    @Binding var text: String
    var focused: FocusState<Bool>.Binding
    let onSubmit: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            TextField(String(localized: "shop_quick_add_placeholder"), text: $text)
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
            .accessibilityLabel(String(localized: "action_add_item"))
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 12)
        .background(StorehopColors.surface)
    }
}

private struct PurchasedSnackbar: View {
    let name: String
    let onUndo: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        HStack {
            Text(String(format: String(localized: "snackbar_purchased %@"), name))
                .font(StorehopTypography.bodyMedium)
                .foregroundStyle(StorehopColors.onSurface)
            Spacer()
            Button(String(localized: "action_undo"), action: onUndo)
                .font(StorehopTypography.labelLarge)
                .foregroundStyle(StorehopColors.primary)
        }
        .padding()
        .background(StorehopColors.surface, in: RoundedRectangle(cornerRadius: StorehopShape.cornerMedium))
        .shadow(radius: 4, y: 2)
        .transition(.move(edge: .bottom).combined(with: .opacity))
        .task {
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            onDismiss()
        }
    }
}
