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
                viewModel = StorePickerViewModel(
                    storeRepository: container.storeRepository,
                    shoppingRepository: container.shoppingRepository,
                    session: container.session,
                    sessionTracker: container.shoppingSessionTracker
                )
            }
            viewModel?.bind()
        }
        .onDisappear { viewModel?.teardown() }
    }

    private func content(viewModel: StorePickerViewModel) -> some View {
        @Bindable var vm = viewModel
        return ZStack(alignment: .bottomTrailing) {
            List {
                if let banner = viewModel.criticalBannerState {
                    Section {
                        CriticalNeedsBanner(state: banner)
                            .listRowBackground(Color.clear)
                            .listRowInsets(EdgeInsets())
                    }
                }
                if viewModel.rows.isEmpty {
                    Section {
                        EmptyState(
                            systemImage: "storefront",
                            title: L("storepicker_empty_title"),
                            message: L("storepicker_empty_body")
                        )
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
                                    Label(L("action_delete"), systemImage: "trash")
                                }
                                Button {
                                    pendingRename = row
                                } label: {
                                    Label(L("action_rename"), systemImage: "pencil")
                                }
                                .tint(StorehopColors.secondary)
                            }
                            .contextMenu {
                                Button(L("action_rename"), systemImage: "pencil") {
                                    pendingRename = row
                                }
                                Button(L("action_edit_aisles"), systemImage: "list.number") {
                                    onEditAisles(row.store.id)
                                }
                                // v0.9.0 — flip one-off classification.
                                // Idempotent at the repository layer;
                                // safe to tap repeatedly.
                                Button {
                                    Task { await viewModel.setOneOff(id: row.store.id, isOneOff: !row.store.isOneOff) }
                                } label: {
                                    Label(
                                        row.store.isOneOff
                                            ? L("store_action_unmark_one_off")
                                            : L("store_action_mark_one_off"),
                                        systemImage: row.store.isOneOff ? "checkmark.circle.fill" : "circle"
                                    )
                                }
                                Button(role: .destructive) {
                                    pendingDelete = row
                                } label: {
                                    Label(L("action_delete"), systemImage: "trash")
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
            .navigationTitle(L("title_shop"))
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        onOpenSettings()
                    } label: {
                        Image(systemName: "gearshape")
                    }
                    .accessibilityLabel(L("action_settings"))
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
                title: L("add_store_dialog_title"),
                initialName: "",
                actionTitle: L("action_add"),
                showOneOffToggle: true,
                onSubmit: { name, isOneOff in
                    let err = await viewModel.addStore(name: name, isOneOff: isOneOff)
                    return err
                },
                onDismiss: { showAddDialog = false }
            )
        }
        .sheet(item: $pendingRename) { row in
            StoreNameDialog(
                title: L("rename_store_dialog_title"),
                initialName: row.store.name,
                actionTitle: L("action_save"),
                // Rename doesn't surface the one-off toggle — that's a
                // separate property and is flipped through the row's
                // context menu instead. The `isOneOff` argument here is
                // unused by `renameStore`.
                onSubmit: { name, _ in
                    await viewModel.renameStore(id: row.store.id, name: name)
                },
                onDismiss: { pendingRename = nil }
            )
        }
        .alert(item: $pendingDelete) { row in
            Alert(
                title: Text(String(format: L("delete_store_dialog_title %@"), row.store.name)),
                message: Text(L("delete_store_dialog_message")),
                primaryButton: .destructive(Text(L("action_delete"))) {
                    viewModel.deleteStore(id: row.store.id)
                    showUndo(for: row)
                },
                secondaryButton: .cancel()
            )
        }
    }

    private func showUndo(for row: StorePickerRow) {
        undoStoreId = row.store.id
        undoMessage = String(format: L("undo_store_deleted %@"), row.store.name)
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
            return String(format: L("store_items_needed %lld"), row.neededCount)
        }
        if row.pickedUpInSessionCount > 0 {
            return L("store_all_set")
        }
        return L("store_nothing_needed")
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

/// Collapsed by default: shows just the title + count chevron. Tap to
/// Routing-aware critical-needs banner — v0.6.10 iOS parity catch-up
/// with Android v0.5.6. Collapsed shows the total count + a "Most at
/// <store> (<N>)" hint so the user knows where to shop first. Expanded
/// shows the per-store breakdown, with each store's critical names
/// comma-joined on its own line.
///
/// On Android the same data shape is `CriticalBannerState`; on iOS it
/// lives in `StorePickerViewModel.criticalBannerState`. Single-store
/// case hides the "Most at" hint (just shows the count) since there's
/// no routing decision to make.
private struct CriticalNeedsBanner: View {
    let state: CriticalBannerState
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .center, spacing: 12) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(StorehopColors.onPrimaryContainer)
                VStack(alignment: .leading, spacing: 2) {
                    Text(String(
                        format: L("critical_needs_count %lld"),
                        state.totalCount
                    ))
                        .font(StorehopTypography.titleSmall.weight(.semibold))
                        .foregroundStyle(StorehopColors.onPrimaryContainer)
                    if !state.singleStore {
                        Text(String(
                            format: L("critical_needs_most_at %@ %lld"),
                            state.topStoreName,
                            state.topStoreCount
                        ))
                            .font(StorehopTypography.bodySmall)
                            .foregroundStyle(StorehopColors.onPrimaryContainer)
                    }
                }
                Spacer()
                Image(systemName: expanded ? "chevron.up" : "chevron.down")
                    .foregroundStyle(StorehopColors.onPrimaryContainer)
                    .accessibilityHidden(true)
            }
            if expanded {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(Array(state.byStore.enumerated()), id: \.offset) { _, entry in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(String(
                                format: L("critical_needs_store_header %@ %lld"),
                                entry.0,
                                entry.1.count
                            ))
                                .font(StorehopTypography.labelMedium.weight(.semibold))
                                .foregroundStyle(StorehopColors.onPrimaryContainer)
                            Text(entry.1.joined(separator: ", "))
                                .font(StorehopTypography.bodyMedium)
                                .foregroundStyle(StorehopColors.onPrimaryContainer)
                        }
                    }
                }
                .padding(.top, 8)
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
    }
}

private struct FloatingAddButton: View {
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 8) {
                Image(systemName: "plus")
                Text(L("action_add_store"))
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
    /// v0.9.0 — when `true`, the dialog shows an extra "Mark as
    /// one-off store" toggle below the name field. Rename callers
    /// leave this `false` since renaming a store shouldn't bundle a
    /// classification flip; Add-Store callers turn it on so the user
    /// can pick the flag at creation time.
    let showOneOffToggle: Bool
    @State private var isOneOff: Bool
    let onSubmit: (String, Bool) async -> String?
    let onDismiss: () -> Void

    @State private var error: String?
    @State private var saving = false
    @FocusState private var focused: Bool

    init(
        title: String,
        initialName: String,
        actionTitle: String,
        showOneOffToggle: Bool = false,
        initialIsOneOff: Bool = false,
        onSubmit: @escaping (String, Bool) async -> String?,
        onDismiss: @escaping () -> Void
    ) {
        self.title = title
        self._name = State(initialValue: initialName)
        self.actionTitle = actionTitle
        self.showOneOffToggle = showOneOffToggle
        self._isOneOff = State(initialValue: initialIsOneOff)
        self.onSubmit = onSubmit
        self.onDismiss = onDismiss
    }

    var body: some View {
        NavigationStack {
            Form {
                TextField(L("add_store_field_label"), text: $name)
                    .textInputAutocapitalization(.sentences)
                    .focused($focused)
                if showOneOffToggle {
                    Section {
                        Toggle(isOn: $isOneOff) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(L("store_form_one_off_toggle"))
                                    .font(StorehopTypography.bodyMedium)
                                Text(L("store_form_one_off_subtitle"))
                                    .font(StorehopTypography.bodySmall)
                                    .foregroundStyle(StorehopColors.onSurfaceVariant)
                            }
                        }
                    }
                }
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
                    Button(L("action_cancel"), action: onDismiss)
                        .disabled(saving)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(actionTitle) {
                        Task {
                            saving = true
                            let result = await onSubmit(name, isOneOff)
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
