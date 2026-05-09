# Storehop

A native Android shopping-list app for people who shop at more than one
store. One master list of items is tagged across multiple stores; each
store gets its own per-aisle shopping view, and checking an item off
at one store cascades — buying mozzarella at Lidl drops it from the
Aldi and Pingo Doce lists too, so a single shopping trip satisfies
the need.

The app ships with a starter set of stores and categories. Anything not
on that list (a regional chain, a one-off shop, a category for a
specific household) is added or renamed by the user.

## Status

v0.5.8. Shipping to Google Play Closed testing. Feature-complete for
single-user v1: anonymous-first onboarding with optional Google
Sign-In, two-way Firestore + Storage cloud sync (push and pull), Shop
and Items tabs with item photos, share-list-as-text, theme + language
picker (English, European Portuguese, Spanish, Italian), drag-reorder
stores (long-press on any tile), per-store aisle ordering, cross-store
check-off cascade, Manage Categories, hide / show checked-off items
toggle, QuickAdd autocomplete against the master Items library, in-app
update prompt via Play Core, CSV import / export of items and
categories, and a Statistics screen with a 12-week trend chart. See
[`docs/play-store-submission.md`](docs/play-store-submission.md) for
the Play Console listing answers and
[`docs/privacy-policy.md`](docs/privacy-policy.md) for the privacy
policy hosted at the Play listing's required URL.

An iOS port lives in [`ios/`](ios/) — SwiftUI + GRDB + Firebase iOS
SDK, mirroring the Android architecture 1:1. As of v0.5.7 it's
caught up to feature parity (with the natural exception of the
in-app update prompt, since the App Store has no equivalent API).
Not yet shipped to TestFlight or the App Store.

## Tech stack

- Kotlin 2.1, Jetpack Compose, Material 3 (BOM 2024.12.01)
- Single-activity Compose Navigation; Hilt for DI
- Room 2.6 (KSP) for local persistence; sync-ready schema
- Firebase: Authentication (anonymous + Google via Credential Manager),
  Firestore push + pull sync, Storage for item photos
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
on `item_store_xref.isNeeded`; the default check-off cascades across
every tagged store (one trip clears the list everywhere), and the
manual `markNeededAtStore` path leaves a per-store override hook for
later if we ever want to expose it. The Room schema is exported to
`app/schemas/` and tracked in version control so migrations are
reviewable.

Seeded stores and categories use stable string IDs (for example
`store_lidl`, `cat_produce`) rather than generated UUIDs so the seed
pack remains stable across devices and across reseeds.

## Roadmap

- v0.1   Data layer schema and theme foundation; placeholder
         MainActivity.
- v0.2   Shop, Items, Add/Edit, item photos, share-list-as-text.
- v0.3   Anonymous-first auth, Google Sign-In, Firestore + Storage
         push sync, per-store need state, drag-reorder stores, settings
         (theme + language), undo + haptics, Play Store submission.
- v0.4.0 Pull-side Firestore sync (cold-launch rehydrate before push
         jobs run) and a Settings cloud-sync banner with Retry action.
- v0.5.0 Manage Categories screen (add / rename / soft-delete / undo)
         from the Items overflow menu, and per-store Edit Aisles
         drag-reorder for category order in Shop-at-Store. Settings →
         bottom-nav routing fix so Items / Shop tabs land deterministically.
- v0.5.1 Cross-store check-off cascade with full Undo (one shopping
         trip clears the item from every tagged store); first-letter
         auto-cap on item / brand / store / category name fields;
         custom user-added categories appear in a store's Edit Aisles
         as soon as they're tagged.
- v0.5.2 CSV import / export of items and categories from Settings →
         Data, with a non-destructive import (duplicates skipped) and a
         snackbar + Undo. Words-level Title Case auto-cap on name
         fields. "Discard changes? / Keep editing" confirmation on the
         item-edit back arrow.
- v0.5.3 Categories-import snackbar correctly counts skipped duplicates
         (was always reporting "Skipped 0 duplicates" even when the
         skip itself was working).
- v0.5.4 Settings → Data: the four import / export buttons stack
         vertically at full card width instead of squeezing into a 2x2
         grid with mismatched button heights.
- v0.5.5 Renaming a category or store no longer fails when a long-
         deleted row is still holding the target name. Schema v6 drops
         the UNIQUE(userId, name) index that was counting tombstones,
         with an alive-collision-only guard at the application layer
         and a clear inline error on the rename dialog when the
         collision is with an existing row.
- v0.5.6 The "Critical items needed" banner on the Shop screen now
         routes you to the right store first instead of listing every
         priority item by name — collapsed view shows the count plus
         the single store covering the most criticals; tap to expand
         a per-store breakdown.
- v0.5.7 Hide / show checked-off items toggle in the Shop-at-Store top
         bar; QuickAdd autocomplete against the master Items library
         (with name-match dedupe to fix duplicate-creation on existing
         items); in-app update prompt via Play Core (no more Play
         Store visits between iterations); custom undo bar with
         × button and swipe-to-dismiss; long-press anywhere on a
         store tile to reorder (drag-handle icon retired);
         Statistics → Activity card simplified to all-time + trend
         chart only.
- v0.5.8 Spanish (Castilian) and Italian language support. Settings →
         Language picker now offers four locales: English, European
         Portuguese, Spanish, Italian. First-pass translations are
         machine-quality; native-speaker review pending before any
         public Play Store promotion.
- v0.6+  Polish follow-ups (e.g. tightening the in-session staple
         flag's renewal behavior) and a v2 home-screen widget that
         actually does something useful.
