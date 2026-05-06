# Storehop

A native Android shopping-list app. One master list of items is tagged
across multiple stores. Marking an item as purchased at one store
removes it from every store's list, and each store's view groups items
by that store's specific aisle order.

The app ships with a starter set of stores and categories. Anything not
on that list (a regional chain, a one-off shop, a category for a
specific household) is added or renamed by the user.

## Status

v0.1.0. Data layer and theme foundation only. No user-facing screens yet.

## Tech stack

- Kotlin 2.1, Jetpack Compose, Material 3
- Single-activity architecture, Hilt for dependency injection
- Room 2.6 (KSP) for local persistence; schema is sync-ready
- Min SDK 26, target and compile SDK 35
- Gradle 9.5, Android Gradle Plugin 8.10
- Languages: English and European Portuguese (pt-PT)

## Building

Open the project in Android Studio and let it sync. From the command
line, the standard tasks are:

    ./gradlew :app:assembleDebug         build the debug APK
    ./gradlew :app:installDebug          install on a connected device
    ./gradlew :app:testDebugUnitTest     run the unit-test suite

The unit-test suite uses Robolectric and runs without an emulator.

## Project layout

    app/src/main/
        java/com/storehop/app/
            data/
                dao/          Room DAOs
                db/           Database, seeder, JSON-backed seed data
                entity/       Sync-ready entities (UUID PKs, soft delete)
                repository/   Repository interfaces and implementations
                util/         IdGenerator, UserSessionProvider
            di/               Hilt modules
            ui/
                theme/        Material 3 theme
                util/         CategoryLabel localization helper
            MainActivity.kt
            StorehopApplication.kt
        assets/seed/          stores.json, categories.json, store_categories.json
        res/
            values/           English strings, theme colors, launcher icon
            values-pt-rPT/    European Portuguese strings

## Data layer

Every entity carries `id` (UUID), `createdAt`, `updatedAt`, `deletedAt`
(soft-delete tombstone), and `userId`. This shape supports offline
edits, deterministic merging across devices, and a future cloud-sync
layer without further schema changes. The Room schema is exported to
`app/schemas/` and tracked in version control so migrations are
reviewable.

Seeded stores and categories use stable string IDs (for example
`store_lidl`, `cat_produce`) rather than generated UUIDs so the seed
pack remains stable across devices and across reseeds.

## Roadmap

- v0.1  Data layer schema and theme foundation; placeholder MainActivity.
- v0.2  Home, Shop-at-Store, Items, and Aisle-order screens.
- v0.3  Google Sign-In with anonymous fallback.
- v0.4  Firestore sync engine.
- v0.5  Drag-reorder aisle UI, statistics, export and import.
