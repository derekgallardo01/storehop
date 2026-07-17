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
                viewModel = ItemFormViewModel(
                    itemId: itemId,
                    itemRepository: container.itemRepository,
                    categoryRepository: container.categoryRepository,
                    storeRepository: container.storeRepository,
                    imageUploader: container.imageUploader,
                    undoEventBus: container.undoEventBus,
                    session: container.session
                )
            }
            // Rebind on every appear; bind() is idempotent (cancels
            // prior subscriptions first) so a re-appear after a sheet
            // dismiss picks up live updates again.
            viewModel?.bind()
        }
        .onDisappear { viewModel?.teardown() }
    }

    private func content(viewModel: ItemFormViewModel) -> some View {
        @Bindable var vm = viewModel

        return Form {
            Section {
                TextField(L("item_name_label"), text: $vm.name)
                    .textInputAutocapitalization(.sentences)
                if vm.nameError {
                    Text(L("error_item_name_empty"))
                        .foregroundStyle(.red)
                        .font(StorehopTypography.bodySmall)
                }

                TextField(L("item_brand_label"), text: $vm.brand)
                    .textInputAutocapitalization(.sentences)
            }

            Section(header: Text(L("item_category_label"))) {
                Picker("", selection: $vm.categoryId) {
                    Text(L("item_category_none")).tag(String?.none)
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
                        L("action_new_category"),
                        systemImage: "plus.circle"
                    )
                }
            }

            Section(header: Text(L("item_stores_label"))) {
                if viewModel.stores.isEmpty {
                    Text(L("item_no_stores_yet"))
                        .font(StorehopTypography.bodySmall)
                        .foregroundStyle(StorehopColors.onSurfaceVariant)
                } else {
                    StoreChipsRow(
                        stores: viewModel.stores,
                        selected: viewModel.storeIds,
                        onToggle: viewModel.toggleStore
                    )
                }
            }

            // v0.9.0 — hide staple + priority toggles entirely when
            // every picked store is one-off. The concepts don't
            // combine: a one-off couch isn't a "staple" or "recurring
            // priority buy"; surfacing the toggles would just look like
            // dead UI. Toggles reappear the moment a regular store is
            // mixed in.
            if !viewModel.allPickedStoresAreOneOff {
                Section {
                    Toggle(L("item_staple_label"), isOn: $vm.isStaple)
                    Toggle(L("item_priority_label"), isOn: $vm.isPriority)
                } footer: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L("item_staple_help"))
                        Text(L("item_priority_help"))
                    }
                    .font(StorehopTypography.bodySmall)
                }
            }

            // "Buy today" stays available even for one-off-only items —
            // urgency applies to any kind of trip, so it's outside the
            // one-off guard that hides the recurring/critical toggles.
            Section {
                Toggle(L("item_buy_today_label"), isOn: $vm.isBuyToday)
            } footer: {
                Text(L("item_buy_today_help"))
                    .font(StorehopTypography.bodySmall)
            }

            Section(header: Text(L("item_photo_label"))) {
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
                        Label(L("action_delete_item"), systemImage: "trash")
                    }
                }
            }
        }
        .navigationTitle(viewModel.isEdit
                         ? L("edit_item_title")
                         : L("add_item_title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(L("action_cancel"), action: onDismiss)
                    .disabled(viewModel.isSubmitting)
            }
            ToolbarItem(placement: .confirmationAction) {
                Button(L("action_save")) {
                    viewModel.submit()
                }
                .disabled(
                    viewModel.isSubmitting ||
                    viewModel.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                )
            }
        }
        .alert(
            L("delete_item_confirm_title"),
            isPresented: $showDeleteConfirm
        ) {
            Button(L("action_delete"), role: .destructive) {
                viewModel.delete()
            }
            Button(L("action_cancel"), role: .cancel) {}
        } message: {
            Text(L("delete_item_confirm_message"))
        }
        .sheet(isPresented: $showAddCategoryDialog) {
            CategoryNameDialog(
                title: L("add_category_dialog_title"),
                initialName: "",
                actionTitle: L("action_add"),
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
                             ? L("form_uploading_image")
                             : L("form_saving"))
                    .padding()
                    .background(StorehopColors.surface, in: RoundedRectangle(cornerRadius: StorehopShape.cornerMedium))
            }
        }
    }

    private func localizedCategoryName(_ cat: Category) -> String {
        if let key = cat.nameKey {
            return L(key)
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
                    Label(L("action_pick_photo"), systemImage: "photo.on.rectangle")
                }
                Spacer()
                if viewModel.localImage != nil || viewModel.imageUrl != nil {
                    Button(role: .destructive) {
                        viewModel.clearImage()
                        photoPickerItem = nil
                    } label: {
                        Label(L("action_remove_photo"), systemImage: "trash")
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
    @State private var enlarged = false

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
                    // A freshly picked photo is zoomable too, same as a
                    // saved one — parity with Android's ImagePickerTile.
                    .contentShape(RoundedRectangle(cornerRadius: StorehopShape.cornerMedium))
                    .onTapGesture { enlarged = true }
                    .fullScreenCover(isPresented: $enlarged) {
                        ZoomableImageView(image: image)
                    }
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
                // Tap the saved photo to see it full-screen and zoom in.
                .contentShape(RoundedRectangle(cornerRadius: StorehopShape.cornerMedium))
                .onTapGesture { enlarged = true }
                .fullScreenCover(isPresented: $enlarged) {
                    ZoomableImageView(imageUrl: imageUrl)
                }
            } else {
                VStack(spacing: 4) {
                    Image(systemName: "photo")
                        .font(.largeTitle)
                    Text(L("item_photo_empty"))
                        .font(StorehopTypography.bodySmall)
                }
                .foregroundStyle(StorehopColors.onSurfaceVariant)
            }
        }
    }
}

