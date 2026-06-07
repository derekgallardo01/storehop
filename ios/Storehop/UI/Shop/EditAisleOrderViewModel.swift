import Foundation
import Observation

@Observable
@MainActor
final class EditAisleOrderViewModel {
    let storeId: String
    var store: Store?
    var orderedCategories: [Category] = []

    private let scoRepository: StoreCategoryOrderRepository
    private let storeRepository: StoreRepository
    private let categoryRepository: CategoryRepository
    private let session: any UserSessionProvider

    private let storeBinder = SessionBinder()
    private let scoBinder = SessionBinder()
    private let categoriesBinder = SessionBinder()

    private var rawSco: [StoreCategoryOrder] = []
    private var allCategories: [Category] = []

    init(
        storeId: String,
        scoRepository: StoreCategoryOrderRepository,
        storeRepository: StoreRepository,
        categoryRepository: CategoryRepository,
        session: any UserSessionProvider
    ) {
        self.storeId = storeId
        self.scoRepository = scoRepository
        self.storeRepository = storeRepository
        self.categoryRepository = categoryRepository
        self.session = session
    }

    func bind() {
        let storeId = self.storeId
        storeBinder.bind(
            session: session,
            emptyValue: nil as Store?
        ) { [storeRepository] uid in
            storeRepository.observeById(userId: uid, id: storeId)
        } onValue: { [weak self] store in
            self?.store = store
        }

        scoBinder.bind(
            session: session,
            emptyValue: [StoreCategoryOrder]()
        ) { [scoRepository] _ in
            scoRepository.observeForStore(storeId: storeId)
        } onValue: { [weak self] rows in
            self?.rawSco = rows
            self?.recompose()
        }

        categoriesBinder.bind(
            session: session,
            emptyValue: [Category]()
        ) { [categoryRepository] uid in
            categoryRepository.observeAll(userId: uid, includeArchived: false)
        } onValue: { [weak self] cats in
            self?.allCategories = cats
            self?.recompose()
        }
    }

    func teardown() {
        storeBinder.cancel()
        scoBinder.cancel()
        categoriesBinder.cancel()
    }

    private func recompose() {
        let byId = Dictionary(uniqueKeysWithValues: allCategories.map { ($0.id, $0) })
        orderedCategories = rawSco
            .sorted { $0.displayOrder < $1.displayOrder }
            .compactMap { byId[$0.categoryId] }
    }

    /// Persist a new aisle order. Repository wraps the rewrite in a transaction.
    func commitOrder(_ orderedIds: [String]) {
        Task {
            try? await scoRepository.reorderCategoriesForStore(
                storeId: storeId,
                orderedCategoryIds: orderedIds
            )
        }
    }
}
