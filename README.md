# Storehop

A native Android shopping-list app for people who shop at more than one
store. One master list of items is tagged across multiple stores; each
store gets its own per-aisle shopping view, and per-store check-off
keeps each store's list independent.

The app ships with a starter set of stores and categories. Anything not
on that list (a regional chain, a one-off shop, a category for a
specific household) is added or renamed by the user.

## Status

v0.3.4. Shipping to Google Play Internal testing. Feature-complete for
single-user v1: anonymous-first onboarding, optional Google Sign-In with
cloud sync (Firestore + Storage), Shop and Items tabs, item photos,
share-list-as-text, theme + language picker, drag-reorder stores. See
[`docs/play-store-submission.md`](docs/play-store-submission.md) for the
Play Console listing answers and
[`docs/privacy-policy.md`](docs/privacy-policy.md) for the privacy
policy hosted at the Play listing's required URL.

## Tech stack

- Kotlin 2.1, Jetpack Compose, Material 3 (BOM 2024.12.01)
- Single-activity Compose Navigation; Hilt for DI
- Room 2.6 (KSP) for local persistence; sync-ready schema
- Firebase: Authentication (anonymous + Google via Credential Manager),
  Firestore push sync, Storage for item photos
- Coil for image loading; DataStore Preferences for theme + locale state
- Min SDK 26, target and compile SDK 35
- Gradle 9.5, Android Gradle Plugin 8.10
- Languages: English and European Portuguese (pt-PT) via the per-app
  locale API on Android 13+, AppCompat backport on 8.0–12

## Building

Open the project in Android Studio and let it sync. From the command
line, the standard tasks are:

    ./gradlew :app:assembleDebug         build the debug APK
    ./gradlew :app:installDebug          install on a connected device
    ./gradlew :app:testDebugUnitTest     run the unit-test suite
    ./gradlew :app:bundleRelease         build the signed release AAB

Release signing reads `keystore.properties` at the repo root (gitignored;
see `app/build.gradle.kts` for the expected keys). When that file is
absent the release task succeeds but the AAB is left unsigned, so CI
and fresh checkouts can still run `assembleDebug` and the unit tests.

The unit-test suite uses Robolectric and runs without an emulator.

## Project layout

    app/src/main/
        java/com/storehop/app/
            auth/             Google Sign-In, FirebaseAuth session provider
            data/
                dao/          Room DAOs
                db/           Database, migrations, seeder, JSON-backed seeds
                entity/       Sync-ready entities (UUID PKs, soft delete)
                prefs/        DataStore-backed user preferences
                repository/   Repository interfaces and implementations
                storage/      Firebase Storage uploader for item photos
                util/         IdGenerator, UserSessionProvider
            di/               Hilt modules (App, Database, Firebase, Prefs, Repo)
            sync/             Push side of the Firestore sync engine + DTOs
            ui/
                auth/         Sign-in screen
                items/        Items master list, Add/Edit form
                nav/          Compose Navigation routes
                settings/     Account, theme, language picker
                shop/         Store picker, Shop-at-Store, share-as-text
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
(soft-delete tombstone), `userId`, and `pendingSync`. This shape
supports offline edits, deterministic merging across devices, and the
push-side Firestore sync engine in `sync/`. Per-store need state lives
on `item_store_xref.isNeeded` so checking an item off at one store
doesn't affect any other store it's tagged to. The Room schema is
exported to `app/schemas/` and tracked in version control so migrations
are reviewable.

Seeded stores and categories use stable string IDs (for example
`store_lidl`, `cat_produce`) rather than generated UUIDs so the seed
pack remains stable across devices and across reseeds.

## Roadmap

- v0.1  Data layer schema and theme foundation; placeholder MainActivity.
- v0.2  Shop, Items, Add/Edit, item photos, share-list-as-text.
- v0.3  Anonymous-first auth, Google Sign-In, Firestore + Storage sync,
        per-store need state, drag-reorder stores, settings (theme +
        language), undo + haptics, Play Store submission.
- v0.4+ Pull-side sync, purchase history view, sign-out / wipe flow.
