import Foundation
import Observation
import UIKit

/// State for the Add / Edit Item form. Same ViewModel backs both modes —
/// `itemId == nil` is Add; non-nil is Edit (loads the row + tagged stores
/// on first construction).
@Observable
@MainActor
final class ItemFormViewModel {
    let itemId: String?
    var isEdit: Bool { itemId != nil }

    var name: String = "" {
        didSet { nameError = false; saveError = nil }
    }
    var brand: String = ""
    var categoryId: String?
    var storeIds: Set<String> = []
    /// v0.9: new items default to "Always on the list." Edit mode overrides
    /// this with the stored value on load (`applyLoaded`); the one-off guard in
    /// `submit()` coerces it back to false when every picked store is one-off.
    var isStaple: Bool = true
    var isPriority: Bool = false
    /// v0.9 "Buy Today!" transient urgency flag.
    var isBuyToday: Bool = false

    /// Already-uploaded image URL from a prior save.
    var imageUrl: String?
    /// Local UIImage the user just picked but hasn't uploaded yet.
    var localImage: UIImage?

    /// Live category list for the picker.
    var categories: [Category] = []
    /// Live store list for the multi-select chips.
    var stores: [Store] = []

    /// v0.9.0 — true when at least one store is picked AND every picked
    /// store is a one-off store. Drives the staple + priority toggle
    /// visibility in the form: those concepts don't combine with
    /// "one-off purchase" semantically (a one-off couch can't be a
    /// "staple"; a one-off shelf bracket can't be a recurring
    /// "priority" buy). Empty selection returns `false` so the toggles
    /// stay visible while the user is still picking.
    var allPickedStoresAreOneOff: Bool {
        guard !storeIds.isEmpty else { return false }
        let pickedSet = storeIds
        let pickedStores = stores.filter { pickedSet.contains($0.id) }
        // If we have ids in `storeIds` but the live `stores` array hasn't
        // surfaced them yet (e.g. a freshly loaded edit-form before the
        // store list arrives), fall through to `false` rather than
        // wrongly hiding the toggles.
        guard !pickedStores.isEmpty else { return false }
        return pickedStores.allSatisfy { $0.isOneOff }
    }

    var isLoading: Bool = false
    var isSubmitting: Bool = false
    var isUploadingImage: Bool = false
    var nameError: Bool = false
    var saveError: String?
    /// Set by `submit()` on success / `delete()` on success — the view
    /// observes this and dismisses the form.
    var saved: Bool = false
    var deleted: Bool = false

    private let itemRepository: ItemRepository
    private let categoryRepository: CategoryRepository
    private let storeRepository: StoreRepository
    private let imageUploader: any ImageUploader
    private let undoEventBus: UndoEventBus
    private let session: any UserSessionProvider

    private let categoriesBinder = SessionBinder()
    private let storesBinder = SessionBinder()

    init(
        itemId: String?,
        itemRepository: ItemRepository,
        categoryRepository: CategoryRepository,
        storeRepository: StoreRepository,
        imageUploader: any ImageUploader,
        undoEventBus: UndoEventBus,
        session: any UserSessionProvider
    ) {
        self.itemId = itemId
        self.itemRepository = itemRepository
        self.categoryRepository = categoryRepository
        self.storeRepository = storeRepository
        self.imageUploader = imageUploader
        self.undoEventBus = undoEventBus
        self.session = session
        self.isLoading = itemId != nil
    }

    func bind() {
        categoriesBinder.bind(
            session: session,
            emptyValue: [Category]()
        ) { [categoryRepository] uid in
            categoryRepository.observeAll(userId: uid, includeArchived: false)
        } onValue: { [weak self] cats in
            self?.categories = cats
        }
        storesBinder.bind(
            session: session,
            emptyValue: [Store]()
        ) { [storeRepository] uid in
            storeRepository.observeAll(userId: uid, includeArchived: false)
        } onValue: { [weak self] stores in
            self?.stores = stores
        }

        if let itemId {
            Task { @MainActor [weak self] in
                guard let self else { return }
                let uid = await session.currentUserId
                guard let uid else { self.isLoading = false; return }
                // Snapshot read; the form only loads once and writes
                // through updateItem on save.
                let row = try? await self.itemRepository.fetchSnapshot(userId: uid, id: itemId)
                if let row {
                    self.applyLoaded(row)
                } else {
                    self.isLoading = false
                    self.saveError = L("error_item_not_found")
                }
            }
        }
    }

    func teardown() {
        categoriesBinder.cancel()
        storesBinder.cancel()
    }

    private func applyLoaded(_ row: ItemWithCategoryAndStores) {
        name = row.item.name
        brand = row.item.brand ?? ""
        categoryId = row.item.categoryId
        storeIds = Set(row.stores.map(\.id))
        isStaple = row.item.isStaple
        isPriority = row.item.isPriority
        isBuyToday = row.item.isBuyToday
        imageUrl = row.item.imageUrl
        isLoading = false
    }

    func toggleStore(_ id: String) {
        if storeIds.contains(id) {
            storeIds.remove(id)
        } else {
            storeIds.insert(id)
        }
    }

    /// Create a new category from the inline "+ New category" affordance
    /// in the category picker. Returns nil on success, or a localized error
    /// string the dialog renders inline. On success, the new category id
    /// is auto-selected on the form.
    ///
    /// Mirrors Android's `ItemFormViewModel.addCategory` and
    /// `ManageCategoriesViewModel.addCategory` validation pattern (empty /
    /// duplicate / generic), routed through `CategoryRepository.addCategory`
    /// which handles alive-skip and tombstone-resurrect.
    func addCategory(name: String) async -> String? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return L("error_category_name_empty")
        }
        do {
            let newId = try await categoryRepository.addCategory(name: trimmed)
            // Auto-select so the just-created category is already chosen
            // when the dialog dismisses.
            categoryId = newId
            return nil
        } catch CategoryRepositoryError.duplicateName(let n) {
            return String(format: L("error_category_name_duplicate %@"), n)
        } catch {
            return L("error_could_not_add_category")
        }
    }

    func pickImage(_ image: UIImage) {
        localImage = image
    }

    func clearImage() {
        localImage = nil
        imageUrl = nil
    }

    func submit() {
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedName.isEmpty else {
            nameError = true
            return
        }
        isSubmitting = true
        saveError = nil

        let snapshot = (
            name: trimmedName,
            brand: brand.trimmingCharacters(in: .whitespacesAndNewlines),
            categoryId: categoryId,
            storeIds: storeIds,
            // v0.9: staples and one-offs don't combine. When every picked store
            // is a one-off store the toggle rows are hidden, so coerce the
            // (defaulted-on) staple flag back to false before persisting.
            isStaple: allPickedStoresAreOneOff ? false : isStaple,
            isPriority: isPriority,
            isBuyToday: isBuyToday,
            imageUrl: imageUrl,
            localImage: localImage
        )

        Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                let savedId: String
                if let existingId = self.itemId {
                    try await self.itemRepository.updateItem(
                        id: existingId,
                        name: snapshot.name,
                        categoryId: snapshot.categoryId,
                        storeIds: snapshot.storeIds,
                        quantity: nil,
                        notes: nil,
                        brand: snapshot.brand.isEmpty ? nil : snapshot.brand,
                        imageUrl: snapshot.imageUrl,
                        isStaple: snapshot.isStaple,
                        isPriority: snapshot.isPriority,
                        isBuyToday: snapshot.isBuyToday
                    )
                    savedId = existingId
                } else {
                    savedId = try await self.itemRepository.addItem(
                        name: snapshot.name,
                        categoryId: snapshot.categoryId,
                        storeIds: snapshot.storeIds,
                        quantity: nil,
                        notes: nil,
                        brand: snapshot.brand.isEmpty ? nil : snapshot.brand,
                        imageUrl: snapshot.imageUrl,
                        isStaple: snapshot.isStaple,
                        isPriority: snapshot.isPriority,
                        isBuyToday: snapshot.isBuyToday
                    )
                }

                if let local = snapshot.localImage {
                    self.isUploadingImage = true
                    let url = try await self.imageUploader.upload(image: local, itemId: savedId)
                    try await self.itemRepository.updateItem(
                        id: savedId,
                        name: snapshot.name,
                        categoryId: snapshot.categoryId,
                        storeIds: snapshot.storeIds,
                        quantity: nil,
                        notes: nil,
                        brand: snapshot.brand.isEmpty ? nil : snapshot.brand,
                        imageUrl: url,
                        isStaple: snapshot.isStaple,
                        isPriority: snapshot.isPriority,
                        isBuyToday: snapshot.isBuyToday
                    )
                }

                self.isSubmitting = false
                self.isUploadingImage = false
                self.saved = true
            } catch {
                self.isSubmitting = false
                self.isUploadingImage = false
                self.saveError = L("error_could_not_save_item")
            }
        }
    }

    func delete() {
        guard let id = itemId else { return }
        let displayName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        isSubmitting = true
        Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                try await self.itemRepository.softDelete(id: id)
                self.isSubmitting = false
                self.deleted = true
                // Hand the undo prompt to ItemsListView via the bus.
                await self.undoEventBus.emit(.itemDeleted(itemId: id, itemName: displayName))
            } catch {
                self.isSubmitting = false
                self.saveError = L("error_could_not_delete_item")
            }
        }
    }
}

