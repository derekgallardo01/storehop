import SwiftUI

struct ManageCategoriesView: View {
    @Environment(AppContainer.self) private var container
    @Environment(\.editMode) private var editMode
    @State private var viewModel: ManageCategoriesViewModel?
    @State private var showAddDialog = false
    @State private var pendingRename: Category?
    @State private var pendingDelete: Category?
    @State private var pendingBulkDelete: Bool = false

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
                let vm = ManageCategoriesViewModel(
                    categoryRepository: container.categoryRepository,
                    session: container.session
                )
                vm.bind()
                viewModel = vm
            }
        }
        .onDisappear { viewModel?.teardown() }
    }

    private func content(viewModel: ManageCategoriesViewModel) -> some View {
        @Bindable var vm = viewModel
        return ZStack(alignment: .bottom) {
            List(selection: $vm.selectedIds) {
                if viewModel.categories.isEmpty {
                    Section {
                        Text(String(localized: "categories_empty"))
                            .font(StorehopTypography.bodyMedium)
                            .foregroundStyle(StorehopColors.onSurfaceVariant)
                    }
                } else {
                    Section {
                        ForEach(viewModel.categories) { category in
                            CategoryRow(category: category)
                                .tag(category.id)
                                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                    if !category.isSeeded {
                                        Button(role: .destructive) {
                                            pendingDelete = category
                                        } label: {
                                            Label(String(localized: "action_delete"), systemImage: "trash")
                                        }
                                        Button {
                                            pendingRename = category
                                        } label: {
                                            Label(String(localized: "action_rename"), systemImage: "pencil")
                                        }
                                        .tint(StorehopColors.secondary)
                                    }
                                }
                        }
                        .onMove { source, destination in
                            // v0.6.4: drag-reorder. SwiftUI hands us the
                            // index translation; we apply it locally + ship
                            // the new top-to-bottom id sequence to the VM.
                            var reordered = viewModel.categories
                            reordered.move(fromOffsets: source, toOffset: destination)
                            viewModel.commitReorder(orderedIds: reordered.map(\.id))
                        }
                    }
                }
                Section { Color.clear.frame(height: 80).listRowBackground(Color.clear) }
            }
            .listStyle(.insetGrouped)
            .navigationTitle(String(localized: "title_manage_categories"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    EditButton()
                }
                if editMode?.wrappedValue.isEditing == true && !vm.selectedIds.isEmpty {
                    ToolbarItem(placement: .bottomBar) {
                        Button(role: .destructive) {
                            pendingBulkDelete = true
                        } label: {
                            Label(
                                String(format: String(localized: "manage_categories_selected_count %lld"), vm.selectedIds.count) +
                                    " · " + String(localized: "action_delete"),
                                systemImage: "trash"
                            )
                        }
                    }
                }
            }

            // FAB hides while in edit mode -- mirror Apple's Notes app
            // pattern where the compose button steps aside during
            // selection.
            if editMode?.wrappedValue.isEditing != true {
                FloatingAddCategoryButton { showAddDialog = true }
                    .padding(20)
            }

            if let undoName = viewModel.pendingUndoName {
                UndoBar(
                    message: String(format: String(localized: "undo_category_deleted %@"), undoName),
                    onUndo: { viewModel.undoDelete() },
                    onDismiss: { viewModel.dismissUndo() }
                )
                .padding(.horizontal, 16)
                .padding(.bottom, 88)
            }
            if viewModel.pendingBulkUndoDeletedAt != nil {
                UndoBar(
                    message: String(
                        format: String(localized: "undo_categories_deleted_count %lld"),
                        viewModel.pendingBulkUndoCount
                    ),
                    onUndo: { viewModel.undoBulkDelete() },
                    onDismiss: { viewModel.dismissBulkUndo() }
                )
                .padding(.horizontal, 16)
                .padding(.bottom, 88)
            }
        }
        .sheet(isPresented: $showAddDialog) {
            BulkAddCategoryDialog(
                onSubmit: { raw in await viewModel.addManyCategories(raw: raw) },
                onDismiss: { showAddDialog = false }
            )
        }
        .sheet(item: $pendingRename) { category in
            CategoryNameDialog(
                title: String(localized: "rename_category_dialog_title"),
                initialName: category.name,
                actionTitle: String(localized: "action_save"),
                onSubmit: { name in await viewModel.renameCategory(id: category.id, name: name) },
                onDismiss: { pendingRename = nil }
            )
        }
        .alert(item: $pendingDelete) { category in
            Alert(
                title: Text(String(format: String(localized: "delete_category_dialog_title %@"), category.name)),
                message: Text(String(localized: "delete_category_dialog_message")),
                primaryButton: .destructive(Text(String(localized: "action_delete"))) {
                    viewModel.deleteCategory(category)
                },
                secondaryButton: .cancel()
            )
        }
        .alert(
            String(
                format: String(localized: "delete_categories_dialog_title %lld"),
                viewModel.selectedIds.count
            ),
            isPresented: $pendingBulkDelete,
            actions: {
                Button(String(localized: "action_delete"), role: .destructive) {
                    Task { await viewModel.deleteSelected() }
                }
                Button(String(localized: "action_cancel"), role: .cancel) {}
            },
            message: { Text(String(localized: "delete_category_dialog_message")) }
        )
        .animation(.easeInOut(duration: 0.2), value: viewModel.pendingUndoName)
        .animation(.easeInOut(duration: 0.2), value: viewModel.pendingBulkUndoDeletedAt)
    }
}

private struct CategoryRow: View {
    let category: Category

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: category.isSeeded ? "lock.fill" : "tag.fill")
                .foregroundStyle(StorehopColors.onSurfaceVariant)
                .imageScale(.small)
            VStack(alignment: .leading, spacing: 2) {
                Text(localizedName)
                    .font(StorehopTypography.bodyLarge)
                if category.isSeeded {
                    Text(String(localized: "category_seeded_subtitle"))
                        .font(StorehopTypography.bodySmall)
                        .foregroundStyle(StorehopColors.onSurfaceVariant)
                }
            }
        }
    }

    private var localizedName: String {
        if let key = category.nameKey {
            return String(localized: String.LocalizationValue(key))
        }
        return category.name
    }
}

/// v0.6.4: multi-line add dialog. Paste a list, one category per line;
/// whitespace-only lines drop, case-insensitive duplicates within the
/// input are deduped, and the result populates a final summary.
private struct BulkAddCategoryDialog: View {
    let onSubmit: (String) async -> BulkAddResult
    let onDismiss: () -> Void

    @State private var text: String = ""
    @State private var saving = false
    @State private var summary: BulkAddResult?
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(String(localized: "add_category_multi_help"))
                        .font(StorehopTypography.bodySmall)
                        .foregroundStyle(StorehopColors.onSurfaceVariant)
                    TextEditor(text: $text)
                        .frame(minHeight: 120, maxHeight: 200)
                        .focused($focused)
                        .textInputAutocapitalization(.sentences)
                }
                if let summary {
                    Section {
                        Text(
                            String(
                                format: String(localized: "add_category_summary %1$lld %2$lld"),
                                summary.added, summary.duplicates
                            )
                        )
                        .font(StorehopTypography.bodySmall)
                    }
                }
            }
            .navigationTitle(String(localized: "add_category_dialog_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "action_cancel"), action: onDismiss).disabled(saving)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "action_add")) {
                        Task {
                            saving = true
                            let result = await onSubmit(text)
                            saving = false
                            if result.added > 0 || result.duplicates > 0 {
                                onDismiss()
                            } else {
                                summary = result
                            }
                        }
                    }
                    .disabled(saving || text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .onAppear { focused = true }
        }
        .presentationDetents([.medium, .large])
    }
}

private struct FloatingAddCategoryButton: View {
    let onTap: () -> Void

    var body: some View {
        HStack {
            Spacer()
            Button(action: onTap) {
                HStack(spacing: 8) {
                    Image(systemName: "plus")
                    Text(String(localized: "action_add_category"))
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
}
