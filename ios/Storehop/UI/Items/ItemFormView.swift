import SwiftUI
import PhotosUI

struct ItemFormView: View {
    let itemId: String?  // nil = Add mode
    let onDismiss: () -> Void

    @Environment(AppContainer.self) private var container
    @State private var viewModel: ItemFormViewModel?
    @State private var photoPickerItem: PhotosPickerItem?
    @State private var showDeleteConfirm = false
    @State private var showAddCategoryDialog = false

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
                let vm = ItemFormViewModel(
                    itemId: itemId,
                    itemRepository: container.itemRepository,
                    categoryRepository: container.categoryRepository,
                    storeRepository: container.storeRepository,
                    imageUploader: container.imageUploader,
                    undoEventBus: container.undoEventBus,
                    session: container.session
                )
                vm.bind()
                viewModel = vm
            }
        }
        .onDisappear { viewModel?.teardown() }
    }

    private func content(viewModel: ItemFormViewModel) -> some View {
        @Bindable var vm = viewModel

        return Form {
            Section {
                TextField(String(localized: "item_name_label"), text: $vm.name)
                    .textInputAutocapitalization(.sentences)
                if vm.nameError {
                    Text(String(localized: "error_item_name_empty"))
                        .foregroundStyle(.red)
                        .font(StorehopTypography.bodySmall)
                }

                TextField(String(localized: "item_brand_label"), text: $vm.brand)
                    .textInputAutocapitalization(.sentences)
            }

            Section(header: Text(String(localized: "item_category_label"))) {
                Picker("", selection: $vm.categoryId) {
                    Text(String(localized: "item_category_none")).tag(String?.none)
                    ForEach(viewModel.categories) { cat in
                        Text(localizedCategoryName(cat)).tag(String?(cat.id))
                    }
                }
                .pickerStyle(.menu)
                .labelsHidden()
                // v0.6.1: inline "+ New category" affordance. SwiftUI's Picker
                // doesn't accept arbitrary buttons, so the Button sits in the
                // same Section right below it. On a successful add the VM
                // auto-selects the new id, so the picker label updates live.
                Button {
                    showAddCategoryDialog = true
                } label: {
                    Label(
                        String(localized: "action_new_category"),
                        systemImage: "plus.circle"
                    )
                }
            }

            Section(header: Text(String(localized: "item_stores_label"))) {
                if viewModel.stores.isEmpty {
                    Text(String(localized: "item_no_stores_yet"))
                        .font(StorehopTypography.bodySmall)
                        .foregroundStyle(StorehopColors.onSurfaceVariant)
                } else {
                    StoreChipFlow(
                        stores: viewModel.stores,
                        selected: viewModel.storeIds,
                        onToggle: viewModel.toggleStore
                    )
                }
            }

            Section {
                Toggle(String(localized: "item_staple_label"), isOn: $vm.isStaple)
                Toggle(String(localized: "item_priority_label"), isOn: $vm.isPriority)
            } footer: {
                VStack(alignment: .leading, spacing: 4) {
                    Text(String(localized: "item_staple_help"))
                    Text(String(localized: "item_priority_help"))
                }
                .font(StorehopTypography.bodySmall)
            }

            Section(header: Text(String(localized: "item_photo_label"))) {
                photoSection(viewModel: viewModel)
            }

            if let saveError = viewModel.saveError {
                Section {
                    Text(saveError)
                        .foregroundStyle(.red)
                        .font(StorehopTypography.bodyMedium)
                }
            }

            if viewModel.isEdit {
                Section {
                    Button(role: .destructive) {
                        showDeleteConfirm = true
                    } label: {
                        Label(String(localized: "action_delete_item"), systemImage: "trash")
                    }
                }
            }
        }
        .navigationTitle(viewModel.isEdit
                         ? String(localized: "edit_item_title")
                         : String(localized: "add_item_title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(String(localized: "action_cancel"), action: onDismiss)
                    .disabled(viewModel.isSubmitting)
            }
            ToolbarItem(placement: .confirmationAction) {
                Button(String(localized: "action_save")) {
                    viewModel.submit()
                }
                .disabled(
                    viewModel.isSubmitting ||
                    viewModel.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                )
            }
        }
        .alert(
            String(localized: "delete_item_confirm_title"),
            isPresented: $showDeleteConfirm
        ) {
            Button(String(localized: "action_delete"), role: .destructive) {
                viewModel.delete()
            }
            Button(String(localized: "action_cancel"), role: .cancel) {}
        } message: {
            Text(String(localized: "delete_item_confirm_message"))
        }
        .sheet(isPresented: $showAddCategoryDialog) {
            CategoryNameDialog(
                title: String(localized: "add_category_dialog_title"),
                initialName: "",
                actionTitle: String(localized: "action_add"),
                onSubmit: { name in await viewModel.addCategory(name: name) },
                onDismiss: { showAddCategoryDialog = false }
            )
        }
        .onChange(of: viewModel.saved) { _, saved in
            if saved { onDismiss() }
        }
        .onChange(of: viewModel.deleted) { _, deleted in
            if deleted { onDismiss() }
        }
        .onChange(of: photoPickerItem) { _, newItem in
            guard let newItem else { return }
            Task { @MainActor in
                if let data = try? await newItem.loadTransferable(type: Data.self),
                   let image = UIImage(data: data) {
                    viewModel.pickImage(image)
                }
            }
        }
        .overlay {
            if viewModel.isSubmitting || viewModel.isUploadingImage {
                Color.black.opacity(0.2).ignoresSafeArea()
                ProgressView(viewModel.isUploadingImage
                             ? String(localized: "form_uploading_image")
                             : String(localized: "form_saving"))
                    .padding()
                    .background(StorehopColors.surface, in: RoundedRectangle(cornerRadius: StorehopShape.cornerMedium))
            }
        }
    }

    private func localizedCategoryName(_ cat: Category) -> String {
        if let key = cat.nameKey {
            return String(localized: String.LocalizationValue(key))
        }
        return cat.name
    }

    private func photoSection(viewModel: ItemFormViewModel) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            ItemFormPhoto(image: viewModel.localImage, imageUrl: viewModel.imageUrl)
            HStack {
                PhotosPicker(
                    selection: $photoPickerItem,
                    matching: .images,
                    photoLibrary: .shared()
                ) {
                    Label(String(localized: "action_pick_photo"), systemImage: "photo.on.rectangle")
                }
                Spacer()
                if viewModel.localImage != nil || viewModel.imageUrl != nil {
                    Button(role: .destructive) {
                        viewModel.clearImage()
                        photoPickerItem = nil
                    } label: {
                        Label(String(localized: "action_remove_photo"), systemImage: "trash")
                    }
                }
            }
        }
    }
}

// MARK: - Subviews

private struct ItemFormPhoto: View {
    let image: UIImage?
    let imageUrl: String?

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: StorehopShape.cornerMedium)
                .fill(StorehopColors.surfaceVariant)
                .frame(height: 160)
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .frame(maxWidth: .infinity, maxHeight: 160)
                    .clipShape(RoundedRectangle(cornerRadius: StorehopShape.cornerMedium))
            } else if let imageUrl, let url = URL(string: imageUrl) {
                AsyncImage(url: url) { image in
                    image.resizable().scaledToFill()
                } placeholder: {
                    Image(systemName: "photo")
                        .font(.largeTitle)
                        .foregroundStyle(StorehopColors.onSurfaceVariant)
                }
                .frame(maxWidth: .infinity, maxHeight: 160)
                .clipShape(RoundedRectangle(cornerRadius: StorehopShape.cornerMedium))
            } else {
                VStack(spacing: 4) {
                    Image(systemName: "photo")
                        .font(.largeTitle)
                    Text(String(localized: "item_photo_empty"))
                        .font(StorehopTypography.bodySmall)
                }
                .foregroundStyle(StorehopColors.onSurfaceVariant)
            }
        }
    }
}

/// Multi-select chip row for tagging an item to stores. Wraps to multiple
/// lines when there are many stores. Tap toggles selection.
private struct StoreChipFlow: View {
    let stores: [Store]
    let selected: Set<String>
    let onToggle: (String) -> Void

    var body: some View {
        FlexibleHStack(spacing: 8) {
            ForEach(stores) { store in
                let isOn = selected.contains(store.id)
                Button {
                    onToggle(store.id)
                } label: {
                    HStack(spacing: 6) {
                        if isOn {
                            Image(systemName: "checkmark")
                                .font(.caption2)
                        }
                        Text(store.name)
                            .font(StorehopTypography.labelMedium)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(
                        isOn ? StorehopColors.primary : StorehopColors.surfaceVariant,
                        in: Capsule()
                    )
                    .foregroundStyle(
                        isOn ? StorehopColors.onPrimary : StorehopColors.onSurface
                    )
                }
                .buttonStyle(.plain)
            }
        }
    }
}

/// Minimal flow layout — wraps content to multiple lines as needed.
/// SwiftUI's `Layout` protocol makes this simple. Used for the store chips
/// where the chip count may exceed one line.
private struct FlexibleHStack: Layout {
    let spacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0
        var totalHeight: CGFloat = 0
        for sub in subviews {
            let s = sub.sizeThatFits(.unspecified)
            if x + s.width > maxWidth, x > 0 {
                totalHeight += rowHeight + spacing
                x = 0
                rowHeight = 0
            }
            rowHeight = max(rowHeight, s.height)
            x += s.width + spacing
            y = totalHeight + rowHeight
        }
        return CGSize(width: maxWidth, height: y)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x: CGFloat = bounds.minX
        var y: CGFloat = bounds.minY
        var rowHeight: CGFloat = 0
        for sub in subviews {
            let s = sub.sizeThatFits(.unspecified)
            if x + s.width > bounds.maxX, x > bounds.minX {
                x = bounds.minX
                y += rowHeight + spacing
                rowHeight = 0
            }
            sub.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(s))
            rowHeight = max(rowHeight, s.height)
            x += s.width + spacing
        }
    }
}
