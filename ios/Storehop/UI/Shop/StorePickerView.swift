import SwiftUI

struct StorePickerView: View {
    @Environment(AppContainer.self) private var container
    @State private var viewModel: StorePickerViewModel?
    @State private var showAddDialog = false
    @State private var pendingRename: StorePickerRow?
    @State private var pendingDelete: StorePickerRow?
    @State private var undoStoreId: String?
    @State private var undoMessage: String?

    let onPickStore: (String) -> Void
    let onEditAisles: (String) -> Void
    let onOpenSettings: () -> Void

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
                let vm = StorePickerViewModel(
                    storeRepository: container.storeRepository,
                    shoppingRepository: container.shoppingRepository,
                    session: container.session,
                    sessionTracker: container.shoppingSessionTracker
                )
                vm.bind()
                viewModel = vm
            }
        }
        .onDisappear { viewModel?.teardown() }
    }

    private func content(viewModel: StorePickerViewModel) -> some View {
        @Bindable var vm = viewModel
        return ZStack(alignment: .bottomTrailing) {
            List {
                if !viewModel.criticalAcrossStores.isEmpty {
                    Section {
                        CriticalNeedsBanner(names: viewModel.criticalAcrossStores)
                            .listRowBackground(Color.clear)
                            .listRowInsets(EdgeInsets())
                    }
                }
                Section {
                    ForEach(viewModel.rows) { row in
                        StorePickerRowView(row: row)
                            .contentShape(Rectangle())
                            .onTapGesture { onPickStore(row.store.id) }
                            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                Button(role: .destructive) {
                                    pendingDelete = row
                                } label: {
                                    Label(String(localized: "action_delete"), systemImage: "trash")
                                }
                                Button {
                                    pendingRename = row
                                } label: {
                                    Label(String(localized: "action_rename"), systemImage: "pencil")
                                }
                                .tint(StorehopColors.secondary)
                            }
                            .contextMenu {
                                Button(String(localized: "action_rename"), systemImage: "pencil") {
                                    pendingRename = row
                                }
                                Button(String(localized: "action_edit_aisles"), systemImage: "list.number") {
                                    onEditAisles(row.store.id)
                                }
                                Button(role: .destructive) {
                                    pendingDelete = row
                                } label: {
                                    Label(String(localized: "action_delete"), systemImage: "trash")
                                }
                            }
                    }
                    .onMove { source, destination in
                        var current = viewModel.rows
                        current.move(fromOffsets: source, toOffset: destination)
                        viewModel.commitOrder(current.map(\.store.id))
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle(String(localized: "title_shop"))
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        onOpenSettings()
                    } label: {
                        Image(systemName: "gearshape")
                    }
                    .accessibilityLabel(String(localized: "action_settings"))
                }
                ToolbarItem(placement: .topBarTrailing) {
                    EditButton()
                }
            }

            FloatingAddButton {
                showAddDialog = true
            }
            .padding(20)

            if let undoMessage, let undoStoreId {
                UndoBar(
                    message: undoMessage,
                    onUndo: { viewModel.undoDeleteStore(id: undoStoreId) },
                    onDismiss: clearUndo
                )
                .padding(.horizontal, 16)
                .padding(.bottom, 88)  // clear the FAB
            }
        }
        .sheet(isPresented: $showAddDialog) {
            StoreNameDialog(
                title: String(localized: "add_store_dialog_title"),
                initialName: "",
                actionTitle: String(localized: "action_add"),
                onSubmit: { name in
                    let err = await viewModel.addStore(name: name)
                    return err
                },
                onDismiss: { showAddDialog = false }
            )
        }
        .sheet(item: $pendingRename) { row in
            StoreNameDialog(
                title: String(localized: "rename_store_dialog_title"),
                initialName: row.store.name,
                actionTitle: String(localized: "action_save"),
                onSubmit: { name in
                    await viewModel.renameStore(id: row.store.id, name: name)
                },
                onDismiss: { pendingRename = nil }
            )
        }
        .alert(item: $pendingDelete) { row in
            Alert(
                title: Text(String(format: String(localized: "delete_store_dialog_title %@"), row.store.name)),
                message: Text(String(localized: "delete_store_dialog_message")),
                primaryButton: .destructive(Text(String(localized: "action_delete"))) {
                    viewModel.deleteStore(id: row.store.id)
                    showUndo(for: row)
                },
                secondaryButton: .cancel()
            )
        }
    }

    private func showUndo(for row: StorePickerRow) {
        undoStoreId = row.store.id
        undoMessage = String(format: String(localized: "undo_store_deleted %@"), row.store.name)
        // Auto-dismiss is owned by UndoBar's internal `.task` (3s); no
        // parent-side timer needed.
    }

    private func clearUndo() {
        undoStoreId = nil
        undoMessage = nil
    }
}

// MARK: - Subviews

private struct StorePickerRowView: View {
    let row: StorePickerRow

    var body: some View {
        HStack(spacing: 12) {
            ColorDot(name: row.store.name, color: row.store.colorArgb)
            VStack(alignment: .leading, spacing: 2) {
                Text(row.store.name)
                    .font(StorehopTypography.titleMedium)
                Text(subtitle)
                    .font(StorehopTypography.bodySmall)
                    .foregroundStyle(subtitleColor)
            }
            Spacer()
            if !row.criticalItemNames.isEmpty {
                CriticalChip(count: row.criticalItemNames.count)
            }
            Image(systemName: "chevron.right")
                .font(.footnote)
                .foregroundStyle(StorehopColors.onSurfaceVariant)
        }
        .padding(.vertical, 4)
    }

    private var subtitle: String {
        if row.neededCount > 0 {
            return String(format: String(localized: "store_items_needed %lld"), row.neededCount)
        }
        if row.pickedUpInSessionCount > 0 {
            return String(localized: "store_all_set")
        }
        return String(localized: "store_nothing_needed")
    }

    private var subtitleColor: Color {
        if row.neededCount == 0 && row.pickedUpInSessionCount > 0 {
            return StorehopColors.primary  // sage affirmation when trip done
        }
        return StorehopColors.onSurfaceVariant
    }
}

private struct ColorDot: View {
    let name: String
    let color: Int64?

    var body: some View {
        Circle()
            .fill(fillColor)
            .frame(width: 36, height: 36)
            .overlay {
                Text(initial)
                    .font(StorehopTypography.titleMedium)
                    .fontWeight(.bold)
                    .foregroundStyle(StorehopColors.onSurface)
            }
    }

    private var initial: String {
        guard let first = name.first else { return "?" }
        return String(first).uppercased()
    }

    private var fillColor: Color {
        if let color {
            // ARGB int → SwiftUI Color. Drop the alpha channel and treat
            // as solid for the dot.
            let r = Double((color >> 16) & 0xFF) / 255.0
            let g = Double((color >> 8) & 0xFF) / 255.0
            let b = Double(color & 0xFF) / 255.0
            return Color(red: r, green: g, blue: b)
        }
        return StorehopColors.surfaceVariant
    }
}

private struct CriticalChip: View {
    let count: Int

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.caption2)
            Text("\(count)")
                .font(StorehopTypography.labelMedium)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(StorehopColors.primary, in: Capsule())
        .foregroundStyle(StorehopColors.onPrimary)
    }
}

private struct CriticalNeedsBanner: View {
    let names: [String]

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(StorehopColors.onPrimaryContainer)
            VStack(alignment: .leading, spacing: 4) {
                Text(String(localized: "critical_needs_banner_title"))
                    .font(StorehopTypography.titleSmall)
                Text(names.joined(separator: ", "))
                    .font(StorehopTypography.bodyMedium)
            }
            .foregroundStyle(StorehopColors.onPrimaryContainer)
            Spacer()
        }
        .padding(12)
        .background(StorehopColors.primaryContainer, in: RoundedRectangle(cornerRadius: StorehopShape.cornerMedium))
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
    }
}

private struct FloatingAddButton: View {
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 8) {
                Image(systemName: "plus")
                Text(String(localized: "action_add_store"))
            }
            .font(StorehopTypography.labelLarge)
            .padding(.horizontal, 20)
            .padding(.vertical, 14)
            .background(StorehopColors.primary, in: Capsule())
            .foregroundStyle(StorehopColors.onPrimary)
            .shadow(radius: 4, y: 2)
        }
    }
}

// MARK: - Reusable name dialog

struct StoreNameDialog: View {
    let title: String
    @State private var name: String
    let actionTitle: String
    let onSubmit: (String) async -> String?
    let onDismiss: () -> Void

    @State private var error: String?
    @State private var saving = false
    @FocusState private var focused: Bool

    init(
        title: String,
        initialName: String,
        actionTitle: String,
        onSubmit: @escaping (String) async -> String?,
        onDismiss: @escaping () -> Void
    ) {
        self.title = title
        self._name = State(initialValue: initialName)
        self.actionTitle = actionTitle
        self.onSubmit = onSubmit
        self.onDismiss = onDismiss
    }

    var body: some View {
        NavigationStack {
            Form {
                TextField(String(localized: "add_store_field_label"), text: $name)
                    .textInputAutocapitalization(.sentences)
                    .focused($focused)
                if let error {
                    Text(error)
                        .foregroundStyle(.red)
                        .font(StorehopTypography.bodySmall)
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "action_cancel"), action: onDismiss)
                        .disabled(saving)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(actionTitle) {
                        Task {
                            saving = true
                            let result = await onSubmit(name)
                            saving = false
                            if result == nil { onDismiss() }
                            else { error = result }
                        }
                    }
                    .disabled(saving || name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .onAppear { focused = true }
        }
        .presentationDetents([.medium])
    }
}
