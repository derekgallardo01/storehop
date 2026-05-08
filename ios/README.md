# Storehop iOS

Native iOS port of [Storehop](../README.md), tracking feature parity with the Android app.

Versioning intentionally follows Android (start at 0.5.1 / build 10) — the iOS app is "the same Storehop, on iOS," not a separate v1.0.

## Status

**Phase 0 — bootstrap.** Project structure, XcodeGen spec, theme palette, seed JSONs, and a placeholder root view are in place. Data layer, sync, and UI screens land in subsequent phases. See [`.claude/plans/i-know-i-need-hashed-umbrella.md`](../.claude/plans/i-know-i-need-hashed-umbrella.md) for the full plan.

## First-time setup (on a Mac)

You need: macOS 14+, Xcode 15+, Homebrew.

```sh
# 1. Install XcodeGen — the project.yml below is the source of truth; .xcodeproj is generated.
brew install xcodegen

# 2. Generate the Xcode project from project.yml.
cd ios
xcodegen generate

# 3. Open in Xcode (or just `xed Storehop.xcodeproj` from terminal).
open Storehop.xcodeproj
```

Xcode will resolve SwiftPM dependencies (GRDB, Firebase, Google Sign-In) on first open. This takes ~3–5 minutes the first time.

## Building & testing

```sh
# Build for simulator
xcodebuild -project Storehop.xcodeproj -scheme Storehop -destination 'platform=iOS Simulator,name=iPhone 15' build

# Run unit tests
xcodebuild -project Storehop.xcodeproj -scheme Storehop -destination 'platform=iOS Simulator,name=iPhone 15' test
```

## App icon

Phase 0 does **not** ship the rendered AppIcon PNG. The shared brand source in [`design/shophop-icon-source.png`](../design/shophop-icon-source.png) is 670×657 — too small and not square for the iOS 1024×1024 requirement.

**Before Phase 12 (App Store submission), do one of:**

- **Option A — re-render from a higher-resolution master.** Open the original art in Figma/Sketch/Illustrator and export 1024×1024 PNG. Save as `ios/Storehop/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png`.
- **Option B — upscale the existing source on Mac with `sips`.** Lossy but quick:
  ```sh
  sips -s format png --resampleHeightWidth 1024 1024 \
       design/shophop-icon-source.png \
       --out ios/Storehop/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png
  ```

Until then the build will warn about a missing asset; the app still runs.

## Firebase setup

The app expects a `GoogleService-Info.plist` at the bundle root. **It is not committed to git** (matches Android `google-services.json`). To get one:

1. Open the Firebase Console for the existing Storehop project.
2. Add an iOS app with bundle id `com.storehop.app`.
3. Download `GoogleService-Info.plist`.
4. Drop it into `ios/Storehop/Resources/` (and re-run `xcodegen generate` so it gets included in the build).
5. Note the `REVERSED_CLIENT_ID` value — Xcode reads it as `$(GOOGLE_REVERSED_CLIENT_ID)` for the URL scheme registration in `project.yml`.

## Project layout

```
ios/
├── project.yml                    # XcodeGen spec — single source of truth
├── Storehop/
│   ├── App/                       # @main + DI container
│   ├── Auth/                      # Firebase auth wrappers (Phase 4)
│   ├── Data/                      # DAOs, DB, models, repos (Phases 1–3)
│   ├── Sync/                      # SyncEngine + PullCoordinator (Phases 9–10)
│   ├── UI/                        # SwiftUI views, view models (Phases 5–7)
│   └── Resources/                 # Assets.xcassets, xcstrings, seed JSONs
├── StorehopTests/                 # XCTest unit tests
└── StorehopUITests/               # UI smoke tests
```

`Storehop.xcodeproj` is **generated** — do not edit by hand. To change build settings, dependencies, or target structure, edit `project.yml` and re-run `xcodegen generate`.

## Cross-platform invariants

The iOS app shares a Firestore project with Android, so the wire format must stay aligned:

- Same entity field names (camelCase, matching Kotlin `@SerialName` on the DTOs)
- Same seed IDs (`store_lidl`, `cat_produce`, etc.) and same `SEED_TIMESTAMP = 1_730_000_000_000`
- Per-store xref state on `item_store_xref.isNeeded` (not `items.isNeeded`)
- Cross-store cascade on purchase, with snapshot-timestamp-precise undo
- Soft-delete tombstones (`deletedAt`) — never hard-delete

If you change anything that crosses the wire, change it on both platforms in the same release.
