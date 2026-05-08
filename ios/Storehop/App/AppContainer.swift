import Foundation
import Observation

/// Root composition container. Holds every singleton with cross-call
/// lifetime so ViewModels can pull what they need by reference. Constructor
/// injection only — no third-party DI framework.
@Observable
final class AppContainer {
    let clock: Clock
    let ids: any IdGenerator
    let session: any UserSessionProvider
    let database: StorehopDatabase

    // Auth + sync infrastructure (also exposed so SettingsViewModel can act
    // on auth and trigger pull retries).
    let firebaseAuthClient: any FirebaseAuthClient
    let googleSignInUseCase: GoogleSignInUseCase
    let pullCoordinator: any PullCoordinator
    let pullStateRepository: any PullStateRepository
    let firestoreClient: any FirestoreClient
    let syncEngine: SyncEngine

    // DAOs.
    let storeDao: StoreDao
    let categoryDao: CategoryDao
    let itemDao: ItemDao
    let itemStoreXrefDao: ItemStoreXrefDao
    let storeCategoryOrderDao: StoreCategoryOrderDao
    let purchaseRecordDao: PurchaseRecordDao
    let shoppingDao: ShoppingDao
    let pullWriteDao: PullWriteDao
    let localOnlyMigrationDao: LocalOnlyMigrationDao

    // Repositories.
    let itemRepository: ItemRepository
    let storeRepository: StoreRepository
    let categoryRepository: CategoryRepository
    let shoppingRepository: ShoppingRepository
    let storeCategoryOrderRepository: StoreCategoryOrderRepository
    let purchaseHistoryRepository: PurchaseHistoryRepository

    // Singletons that span ViewModel lifetimes.
    let shoppingSessionTracker: ShoppingSessionTracker
    let undoEventBus: UndoEventBus
    let imageUploader: any ImageUploader
    let userPreferences: any UserPreferencesRepository

    init(
        clock: Clock,
        ids: any IdGenerator,
        session: any UserSessionProvider,
        database: StorehopDatabase,
        firebaseAuthClient: any FirebaseAuthClient,
        googleSignInUseCase: GoogleSignInUseCase,
        pullCoordinator: any PullCoordinator,
        pullStateRepository: any PullStateRepository,
        userPreferences: any UserPreferencesRepository,
        firestoreClient: any FirestoreClient,
        imageUploader: any ImageUploader = NoOpImageUploader()
    ) {
        self.clock = clock
        self.ids = ids
        self.session = session
        self.database = database
        self.firebaseAuthClient = firebaseAuthClient
        self.googleSignInUseCase = googleSignInUseCase
        self.pullCoordinator = pullCoordinator
        self.pullStateRepository = pullStateRepository
        self.userPreferences = userPreferences
        self.firestoreClient = firestoreClient
        let writer = database.queue

        self.storeDao = StoreDao(writer: writer)
        self.categoryDao = CategoryDao(writer: writer)
        self.itemDao = ItemDao(writer: writer)
        self.itemStoreXrefDao = ItemStoreXrefDao(writer: writer)
        self.storeCategoryOrderDao = StoreCategoryOrderDao(writer: writer)
        self.purchaseRecordDao = PurchaseRecordDao(writer: writer)
        self.shoppingDao = ShoppingDao(writer: writer)
        self.pullWriteDao = PullWriteDao(writer: writer)
        self.localOnlyMigrationDao = LocalOnlyMigrationDao(writer: writer)

        self.itemRepository = ItemRepository(
            writer: writer,
            itemDao: itemDao,
            xrefDao: itemStoreXrefDao,
            scoDao: storeCategoryOrderDao,
            purchaseDao: purchaseRecordDao,
            session: session,
            clock: clock,
            ids: ids
        )
        self.storeRepository = StoreRepository(
            writer: writer,
            storeDao: storeDao,
            xrefDao: itemStoreXrefDao,
            scoDao: storeCategoryOrderDao,
            session: session,
            clock: clock,
            ids: ids
        )
        self.categoryRepository = CategoryRepository(
            writer: writer,
            categoryDao: categoryDao,
            itemDao: itemDao,
            scoDao: storeCategoryOrderDao,
            session: session,
            clock: clock,
            ids: ids
        )
        self.shoppingRepository = ShoppingRepository(
            shoppingDao: shoppingDao,
            storeDao: storeDao,
            session: session
        )
        self.storeCategoryOrderRepository = StoreCategoryOrderRepository(
            scoDao: storeCategoryOrderDao,
            session: session,
            clock: clock
        )
        self.purchaseHistoryRepository = PurchaseHistoryRepository(
            purchaseDao: purchaseRecordDao,
            session: session,
            clock: clock
        )

        self.shoppingSessionTracker = ShoppingSessionTracker(clock: clock)
        self.undoEventBus = UndoEventBus()
        self.imageUploader = imageUploader

        self.syncEngine = SyncEngine(
            firestore: firestoreClient,
            session: session,
            pullStateRepo: pullStateRepository,
            itemDao: itemDao,
            categoryDao: categoryDao,
            storeDao: storeDao,
            xrefDao: itemStoreXrefDao,
            scoDao: storeCategoryOrderDao,
            purchaseDao: purchaseRecordDao
        )
    }

    static func live() -> AppContainer {
        do {
            let database = try StorehopDatabase.live()
            let writer = database.queue
            let authClient = LiveFirebaseAuthClient()
            let googleSignIn = GoogleSignInUseCase(authClient: authClient)
            let pullState = InMemoryPullStateRepository()
            let firestore = LiveFirestoreClient()
            let pullCoordinator = FirestorePullCoordinator(
                firestore: firestore,
                pullWriteDao: PullWriteDao(writer: writer)
            )
            let session = FirebaseAuthSessionProvider(
                authClient: authClient,
                migrationDao: LocalOnlyMigrationDao(writer: writer),
                pullCoordinator: pullCoordinator,
                pullStateRepo: pullState
            )
            return AppContainer(
                clock: SystemClock(),
                ids: UuidV4Generator(),
                session: session,
                database: database,
                firebaseAuthClient: authClient,
                googleSignInUseCase: googleSignIn,
                pullCoordinator: pullCoordinator,
                pullStateRepository: pullState,
                userPreferences: LiveUserPreferencesRepository(),
                firestoreClient: firestore,
                imageUploader: FirebaseImageUploader(session: session)
            )
        } catch {
            fatalError("Failed to initialize Storehop database: \(error)")
        }
    }

    static func preview() -> AppContainer {
        do {
            // Preview builds use entirely in-memory dependencies — no
            // Firebase, no real defaults. Callers can swap parts after
            // construction if a specific preview state is needed.
            let stubAuth = NoOpAuthClient()
            return AppContainer(
                clock: FixedClock(nowMs: DatabaseSeeder.seedTimestamp),
                ids: UuidV4Generator(),
                session: LocalOnlyUserSessionProvider(),
                database: try StorehopDatabase.inMemoryForTests(),
                firebaseAuthClient: stubAuth,
                googleSignInUseCase: GoogleSignInUseCase(authClient: stubAuth),
                pullCoordinator: StubPullCoordinator(),
                pullStateRepository: InMemoryPullStateRepository(),
                userPreferences: LiveUserPreferencesRepository(defaults: UserDefaults(suiteName: "preview")!),
                firestoreClient: NoOpFirestoreClient()
            )
        } catch {
            fatalError("Failed to build preview AppContainer: \(error)")
        }
    }
}

/// Preview-only auth client. SwiftUI Previews don't have Firebase configured
/// so we substitute a no-op that returns a fixed local-only account.
private struct NoOpAuthClient: FirebaseAuthClient {
    var currentUserId: String? { get async { "preview-uid" } }
    var currentUserIsAnonymous: Bool { get async { true } }
    var currentAccount: AccountInfo {
        get async {
            AccountInfo(uid: "preview-uid", isAnonymous: true, email: nil, displayName: nil, photoURL: nil)
        }
    }
    var accountStream: AsyncStream<AccountInfo> {
        AsyncStream { c in
            c.yield(AccountInfo(uid: "preview-uid", isAnonymous: true, email: nil, displayName: nil, photoURL: nil))
        }
    }
    var authStateStream: AsyncStream<String?> {
        AsyncStream { c in c.yield("preview-uid") }
    }
    func signInAnonymously() async throws -> String { "preview-uid" }
    func signOut() throws {}
    func makeGoogleCredential(idToken: String) -> AnyAuthCredential { AnyAuthCredential(payload: idToken) }
    func linkAnonymousWithGoogle(credential: AnyAuthCredential) async throws -> String { "preview-uid" }
    func signInWithGoogle(credential: AnyAuthCredential) async throws -> String { "preview-uid" }
}

/// Preview-only Firestore client. Discards writes silently and reports
/// every collection as empty so the orphan-claim path runs in previews.
private struct NoOpFirestoreClient: FirestoreClient {
    func setDocument<T: Encodable & Sendable>(at path: String, value: T) async throws {}
    func peekHasDocuments(at collectionPath: String) async throws -> Bool { false }
    func fetchAll<T: Decodable & Sendable>(_ type: T.Type, at collectionPath: String) async throws -> [T] { [] }
}
