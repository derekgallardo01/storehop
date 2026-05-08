import SwiftUI

struct ManageCategoriesView: View {
    @Environment(AppContainer.self) private var container
    @State private var viewModel: ManageCategoriesViewModel?
    @State private var showAddDialog = false
    @State private var pendingRename: Category?
    @State private var pendingDelete: Category?

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
        ZStack(alignment: .bottom) {
            List {
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
                    }
                }
                Section { Color.clear.frame(height: 80).listRowBackground(Color.clear) }
            }
            .listStyle(.insetGrouped)
            .navigationTitle(String(localized: "title_manage_categories"))
            .navigationBarTitleDisplayMode(.inline)

            FloatingAddCategoryButton { showAddDialog = true }
                .padding(20)

            if let undoName = viewModel.pendingUndoName {
                undoSnackbar(name: undoName, viewModel: viewModel)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 88)
            }
        }
        .sheet(isPresented: $showAddDialog) {
            CategoryNameDialog(
                title: String(localized: "add_category_dialog_title"),
                initialName: "",
                actionTitle: String(localized: "action_add"),
                onSubmit: { name in await viewModel.addCategory(name: name) },
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
        .animation(.easeInOut(duration: 0.2), value: viewModel.pendingUndoName)
    }

    private func undoSnackbar(name: String, viewModel: ManageCategoriesViewModel) -> some View {
        HStack {
            Text(String(format: String(localized: "undo_category_deleted %@"), name))
                .font(StorehopTypography.bodyMedium)
            Spacer()
            Button(String(localized: "action_undo")) { viewModel.undoDelete() }
                .font(StorehopTypography.labelLarge)
                .foregroundStyle(StorehopColors.primary)
        }
        .padding()
        .background(StorehopColors.surface, in: RoundedRectangle(cornerRadius: StorehopShape.cornerMedium))
        .shadow(radius: 4, y: 2)
        .transition(.move(edge: .bottom).combined(with: .opacity))
        .task {
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            viewModel.dismissUndo()
        }
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

private struct CategoryNameDialog: View {
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
                TextField(String(localized: "add_category_field_label"), text: $name)
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
                    Button(String(localized: "action_cancel"), action: onDismiss).disabled(saving)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(actionTitle) {
                        Task {
                            saving = true
                            let result = await onSubmit(name)
                            saving = false
                            if result == nil { onDismiss() } else { error = result }
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
