import Foundation
import Observation
@preconcurrency import FirebaseFirestore

/// Root composition container. Holds every singleton with cross-call
/// lifetime so ViewModels can pull what they need by reference. Constructor
/// injection only — no third-party DI framework.
@Observable
final class AppContainer {
    let clock: Clock
    let ids: any IdGenerator
    let session: any UserSessionProvider
    let householdSession: any HouseholdSessionProvider
    let database: StorehopDatabase

    // Auth + sync infrastructure (also exposed so SettingsViewModel can act
    // on auth and trigger pull retries).
    let firebaseAuthClient: any FirebaseAuthClient
    let googleSignInUseCase: GoogleSignInUseCase
    /// v0.8.1.3-ios (build 52): Sign in with Apple. Required by App Store
    /// Review Guideline 4.8 alongside Google Sign-In.
    let signInWithAppleUseCase: SignInWithAppleUseCase
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
    let householdMemberDao: HouseholdMemberDao

    // Repositories.
    let itemRepository: ItemRepository
    let storeRepository: StoreRepository
    let categoryRepository: CategoryRepository
    let shoppingRepository: ShoppingRepository
    let storeCategoryOrderRepository: StoreCategoryOrderRepository
    let purchaseHistoryRepository: PurchaseHistoryRepository
    let importExportRepository: ImportExportRepository
    let householdRepository: any HouseholdRepository

    // Singletons that span ViewModel lifetimes.
    let shoppingSessionTracker: ShoppingSessionTracker
    let undoEventBus: UndoEventBus
    let imageUploader: any ImageUploader
    let userPreferences: any UserPreferencesRepository
    /// v0.7.1 cloud-prefs sync. Optional — preview / some test paths
    /// skip the real Firestore wiring and pass nil.
    let userPreferencesSync: UserPreferencesSync?

    /// v0.8 StoreKit2 wrapper + entitlement source-of-truth. Optional
    /// for preview / test paths that don't need IAP wiring.
    let storeKitManager: StoreKitManager?
    let entitlementRepository: EntitlementRepository?

    init(
        clock: Clock,
        ids: any IdGenerator,
        session: any UserSessionProvider,
        householdSession: any HouseholdSessionProvider,
        householdSwitcher: any HouseholdSwitcher,
        database: StorehopDatabase,
        firebaseAuthClient: any FirebaseAuthClient,
        googleSignInUseCase: GoogleSignInUseCase,
        signInWithAppleUseCase: SignInWithAppleUseCase,
        pullCoordinator: any PullCoordinator,
        pullStateRepository: any PullStateRepository,
        userPreferences: any UserPreferencesRepository,
        firestoreClient: any FirestoreClient,
        householdRepositoryFactory: (
            _ memberDao: HouseholdMemberDao,
            _ writeDao: PullWriteDao
        ) -> any HouseholdRepository,
        imageUploader: any ImageUploader = NoOpImageUploader(),
        userPreferencesSync: UserPreferencesSync? = nil,
        storeKitManager: StoreKitManager? = nil,
        entitlementRepository: EntitlementRepository? = nil
    ) {
        self.clock = clock
        self.ids = ids
        self.session = session
        self.householdSession = householdSession
        self.database = database
        self.firebaseAuthClient = firebaseAuthClient
        self.googleSignInUseCase = googleSignInUseCase
        self.signInWithAppleUseCase = signInWithAppleUseCase
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
        self.householdMemberDao = HouseholdMemberDao(writer: writer)

        self.itemRepository = ItemRepository(
            writer: writer,
            itemDao: itemDao,
            xrefDao: itemStoreXrefDao,
            scoDao: storeCategoryOrderDao,
            purchaseDao: purchaseRecordDao,
            session: session,
            householdSession: householdSession,
            clock: clock,
            ids: ids
        )
        self.storeRepository = StoreRepository(
            writer: writer,
            storeDao: storeDao,
            xrefDao: itemStoreXrefDao,
            scoDao: storeCategoryOrderDao,
            session: session,
            householdSession: householdSession,
            clock: clock,
            ids: ids
        )
        self.categoryRepository = CategoryRepository(
            writer: writer,
            categoryDao: categoryDao,
            itemDao: itemDao,
            scoDao: storeCategoryOrderDao,
            session: session,
            householdSession: householdSession,
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
            householdSession: householdSession,
            clock: clock
        )
        self.purchaseHistoryRepository = PurchaseHistoryRepository(
            purchaseDao: purchaseRecordDao,
            session: session,
            clock: clock
        )
        self.importExportRepository = ImportExportRepository(
            writer: writer,
            categoryDao: categoryDao,
            storeDao: storeDao,
            itemDao: itemDao,
            categoryRepository: categoryRepository,
            storeRepository: storeRepository,
            itemRepository: itemRepository,
            session: session,
            householdSession: householdSession
        )

        // HouseholdRepository wiring needs `householdSwitcher` to flip the
        // active household after invite-accept / leave, plus a way to talk
        // to Firestore directly (the invite-doc CRUD doesn't fit the
        // narrow setDocument/peek/fetchAll FirestoreClient surface). The
        // factory closure lets `live()` inject Firestore.firestore() and
        // `preview()` inject a stub, without dragging Firebase types into
        // every test path.
        self.householdRepository = householdRepositoryFactory(
            householdMemberDao, pullWriteDao
        )

        self.shoppingSessionTracker = ShoppingSessionTracker(clock: clock)
        self.undoEventBus = UndoEventBus()
        self.imageUploader = imageUploader

        self.userPreferencesSync = userPreferencesSync
        self.storeKitManager = storeKitManager
        self.entitlementRepository = entitlementRepository
        self.syncEngine = SyncEngine(
            firestore: firestoreClient,
            session: session,
            householdSession: householdSession,
            pullStateRepo: pullStateRepository,
            itemDao: itemDao,
            categoryDao: categoryDao,
            storeDao: storeDao,
            xrefDao: itemStoreXrefDao,
            scoDao: storeCategoryOrderDao,
            purchaseDao: purchaseRecordDao,
            householdMemberDao: householdMemberDao,
            userPreferencesSync: userPreferencesSync
        )
    }

    static func live() -> AppContainer {
        do {
            let database = try StorehopDatabase.live()
            let writer = database.queue
            let authClient = LiveFirebaseAuthClient()
            let googleSignIn = GoogleSignInUseCase(authClient: authClient)
            let appleSignIn = SignInWithAppleUseCase(authClient: authClient)
            let pullState = InMemoryPullStateRepository()
            let firestore = LiveFirestoreClient()
            let pullCoordinator = FirestorePullCoordinator(
                firestore: firestore,
                pullWriteDao: PullWriteDao(writer: writer)
            )
            let clock = SystemClock()
            let prefs = LiveUserPreferencesRepository(clock: clock)
            // v0.7.1: cloud-sync user prefs via Firestore /userPrefs/{uid}
            // so theme/locale/sort-mode choices survive uninstall +
            // reinstall across signing-cert boundaries.
            let userPreferencesSync = UserPreferencesSync(
                firestore: Firestore.firestore(),
                prefs: prefs
            )
            let session = FirebaseAuthSessionProvider(
                authClient: authClient,
                migrationDao: LocalOnlyMigrationDao(writer: writer),
                householdMemberDao: HouseholdMemberDao(writer: writer),
                pullCoordinator: pullCoordinator,
                pullStateRepo: pullState,
                clock: clock,
                userPreferencesSync: userPreferencesSync
            )
            // v0.8: StoreKit2 wiring + entitlement source-of-truth.
            // Start the manager up here; it'll scan
            // Transaction.currentEntitlements on the first
            // start() call and begin listening for Transaction.updates.
            let storeKit = StoreKitManager()
            let entitlement = EntitlementRepository(
                storeKit: storeKit,
                session: session,
                userPrefs: prefs
            )
            return AppContainer(
                clock: clock,
                ids: UuidV4Generator(),
                session: session,
                householdSession: session,
                householdSwitcher: session,
                database: database,
                firebaseAuthClient: authClient,
                googleSignInUseCase: googleSignIn,
                signInWithAppleUseCase: appleSignIn,
                pullCoordinator: pullCoordinator,
                pullStateRepository: pullState,
                userPreferences: prefs,
                firestoreClient: firestore,
                householdRepositoryFactory: { memberDao, writeDao in
                    FirestoreHouseholdRepository(
                        firestore: Firestore.firestore(),
                        householdMemberDao: memberDao,
                        pullWriteDao: writeDao,
                        userSession: session,
                        householdSession: session,
                        householdSwitcher: session,
                        clock: clock
                    )
                },
                imageUploader: FirebaseImageUploader(session: session),
                userPreferencesSync: userPreferencesSync,
                storeKitManager: storeKit,
                entitlementRepository: entitlement
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
            let previewSession = LocalOnlyHouseholdSessionProvider()
            return AppContainer(
                clock: FixedClock(nowMs: DatabaseSeeder.seedTimestamp),
                ids: UuidV4Generator(),
                session: LocalOnlyUserSessionProvider(),
                householdSession: previewSession,
                householdSwitcher: previewSession,
                database: try StorehopDatabase.inMemoryForTests(),
                firebaseAuthClient: stubAuth,
                googleSignInUseCase: GoogleSignInUseCase(authClient: stubAuth),
                signInWithAppleUseCase: SignInWithAppleUseCase(authClient: stubAuth),
                pullCoordinator: StubPullCoordinator(),
                pullStateRepository: InMemoryPullStateRepository(),
                userPreferences: LiveUserPreferencesRepository(defaults: UserDefaults(suiteName: "preview")!),
                firestoreClient: NoOpFirestoreClient(),
                householdRepositoryFactory: { _, _ in StubHouseholdRepository() }
            )
        } catch {
            fatalError("Failed to build preview AppContainer: \(error)")
        }
    }

    /// E2E-test container. Same shape as `preview()` but uses an isolated
    /// UserDefaults suite that's wiped on init so each cold-launched test
    /// starts from a known-empty preferences state. Optionally seeds
    /// canonical fixtures (2 stores, 1 category, 3 items, 3 xrefs) so the
    /// UI test runner can drive against predictable data without round-
    /// tripping through the real DatabaseSeeder.
    ///
    /// Used by `StorehopApp.init()` when launched with the `-UITestE2E`
    /// argument from XCUITest. Production-side `live()` is unchanged.
    static func e2e(
        seedFixtures: Bool = false,
        seedCriticalCoffee: Bool = false,
        forceTheme: ThemeMode? = nil
    ) -> AppContainer {
        do {
            let database = try StorehopDatabase.inMemoryForTests()
            if seedFixtures {
                try E2EFixtureSeeder.seed(database: database)
                if seedCriticalCoffee {
                    try E2EFixtureSeeder.seedPriorityCoffeeAtLidl(database: database)
                }
            }
            let stubAuth = NoOpAuthClient()
            let e2eSession = LocalOnlyHouseholdSessionProvider()
            // Isolated suite + wipe so prior runs don't leak prefs into
            // the new test process. UserDefaults caches across the
            // simulator's lifetime; without the reset, a sort-mode flip
            // in test A would survive into test B.
            let suiteName = "e2e"
            UserDefaults().removePersistentDomain(forName: suiteName)
            let defaults = UserDefaults(suiteName: suiteName)!
            // Pre-seed the theme preference when the test requested an
            // explicit appearance, so `RootView`'s `.preferredColorScheme`
            // forces light/dark regardless of the simulator's global
            // appearance setting. Without this, `-AppleInterfaceStyle`
            // launch args alone don't reliably flip iOS 26+ simulators.
            if let forceTheme {
                defaults.set(forceTheme.rawValue, forKey: "storehop.themeMode")
            }
            return AppContainer(
                clock: FixedClock(nowMs: DatabaseSeeder.seedTimestamp),
                ids: UuidV4Generator(),
                session: LocalOnlyUserSessionProvider(),
                householdSession: e2eSession,
                householdSwitcher: e2eSession,
                database: database,
                firebaseAuthClient: stubAuth,
                googleSignInUseCase: GoogleSignInUseCase(authClient: stubAuth),
                signInWithAppleUseCase: SignInWithAppleUseCase(authClient: stubAuth),
                pullCoordinator: StubPullCoordinator(),
                pullStateRepository: InMemoryPullStateRepository(),
                userPreferences: LiveUserPreferencesRepository(defaults: defaults),
                firestoreClient: NoOpFirestoreClient(),
                householdRepositoryFactory: { _, _ in StubHouseholdRepository() }
            )
        } catch {
            fatalError("Failed to build e2e AppContainer: \(error)")
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
    func makeAppleCredential(idToken: String, rawNonce: String) -> AnyAuthCredential { AnyAuthCredential(payload: (idToken, rawNonce)) }
    func linkAnonymousWithApple(credential: AnyAuthCredential) async throws -> String { "preview-uid" }
    func signInWithApple(credential: AnyAuthCredential) async throws -> String { "preview-uid" }
}

/// Preview-only Firestore client. Discards writes silently and reports
/// every collection as empty so the orphan-claim path runs in previews.
private struct NoOpFirestoreClient: FirestoreClient {
    func setDocument<T: Encodable & Sendable>(at path: String, value: T) async throws {}
    func peekHasDocuments(at collectionPath: String) async throws -> Bool { false }
    func fetchAll<T: Decodable & Sendable>(_ type: T.Type, at collectionPath: String) async throws -> [T] { [] }
}

/// Preview-only HouseholdRepository. The screen renders fine over an
/// empty members list + a stub invite stream; SwiftUI Previews never run
/// the invite-accept / leave-household paths anyway.
private struct StubHouseholdRepository: HouseholdRepository {
    func observeMembers() -> AsyncStream<[HouseholdMember]> {
        AsyncStream { continuation in
            continuation.yield([])
            continuation.finish()
        }
    }
    func generateInvite() async throws -> InviteCode {
        InviteCode(token: "PREVIEW1", expiresAt: 0)
    }
    func acceptInvite(token: String) async -> InviteResult { .notFound }
    func leaveHousehold() async throws {}
}
