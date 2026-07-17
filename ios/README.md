# Storehop iOS

Native iOS port of [Storehop](../README.md), tracking feature parity with the Android app.

Versioning intentionally follows Android — the iOS app is "the same Storehop, on iOS," not a separate v1.0.

## Status

**v0.9.1 — build 56.** At parity with Android v0.9.1 (Buy Today flag + banners, pinch-zoom image viewer, staple-by-default, un-check cascade, stranded-xref repair, category-sort scroll fix) after the post-ship parity audit: two small gaps closed ("All at \<store\>" banner subtitle, zoom on a freshly picked photo) and 10 ViewModel-layer tests backfilled. See [`CHANGELOG.md`](../CHANGELOG.md#091-ios---2026-07-16). Previous status: build 55 (v0.9.0 one-off stores) ready for upload; build 54 (Sign in with Apple + Premium upgrade card hardening) still in App Review on the 0.8.1 submission — the build 47–54 saga is under the 0.8.x changelog entries.

Design system mirrors Android: same Material 3 color tokens (sage `#5A7A5C` light / `#A4C0A0` dark, warm-neutral surfaces), same corner radii, same 12-style M3 typography mapped 1:1 to SwiftUI Dynamic Type, same in-UI icon vocabulary (SF Symbols for iOS / Material Icons for Android — platform-idiomatic by design). Light + dark mode both render the brand palette correctly across every screen — see [`StorehopUITests/DesignSystemTourTest.swift`](StorehopUITests/DesignSystemTourTest.swift) for the visual-regression artifact.

## First-time setup (on a Mac)

You need: macOS 14+, Xcode 16+, Homebrew.

```sh
# 1. Install XcodeGen — project.yml is the source of truth; .xcodeproj is generated.
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
xcodebuild -project Storehop.xcodeproj -scheme Storehop \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  CODE_SIGNING_ALLOWED=NO build

# Unit tests only (StorehopTests)
xcodebuild -project Storehop.xcodeproj -scheme Storehop \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -only-testing:StorehopTests \
  -skip-testing:StorehopTests/FirebaseAuthSessionProviderTests \
  CODE_SIGNING_ALLOWED=NO test

# Full matrix: unit + E2E (XCUITest, drives the real simulator)
xcodebuild -project Storehop.xcodeproj -scheme Storehop \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -skip-testing:StorehopTests/FirebaseAuthSessionProviderTests \
  CODE_SIGNING_ALLOWED=NO test
```

`FirebaseAuthSessionProviderTests` is skipped on CI because the simulator's keychain doesn't have the entitlement the framework needs. Run it locally where the keychain is granted.

## Running the app

```sh
# Launch on iPhone 17 simulator with the canonical fixtures pre-seeded
# (Lidl + Aldi stores, Dairy category, Milk/Eggs/Bread items).
open -a Simulator
APP=$(find ~/Library/Developer/Xcode/DerivedData/Storehop-*/Build/Products/Debug-iphonesimulator -name Storehop.app -type d | head -1)
xcrun simctl install booted "$APP"
xcrun simctl launch booted com.storehop.app -UITestE2E -E2ESeedFixtures
```

Production launch (real Firebase, no fixtures) needs a real `GoogleService-Info.plist` — see [`#firebase-setup`](#firebase-setup) below. Without one, the CI-placeholder plist boots the app cleanly but anonymous sign-in won't reach a real project so the StorePicker stays empty.

### Useful launch arguments

| Arg | Effect |
|---|---|
| `-UITestE2E` | Use the in-memory `AppContainer.e2e()` (no Firebase, `LocalOnly` session). |
| `-E2ESeedFixtures` | Pre-seed Lidl + Aldi + Milk/Eggs/Bread before view construction. |
| `-E2ESeedCriticalFixture` | Add a priority "Coffee" item @ Lidl (for the critical-banner E2E). |
| `-E2EForceLightTheme` / `-E2EForceDarkTheme` | Force the app's theme regardless of system / user pref. Reliable when `-AppleInterfaceStyle` isn't. |

## E2E test suite

13 XCUITest cases mirroring Android's instrumented suite — long-press → bulk store-tag, cross-store cascade (verified via UI proxy since XCUITest can't read host-app DB), item-add flow, plus/minus toggle, sort toggles (Items + Shop), search clear, inline new-category, critical-banner collapse, long-press edit, plus a launch smoke. See files under [`StorehopUITests/`](StorehopUITests/).

`DesignSystemTourTest` is a visual-regression artifact that walks 8 screens in both light + dark and attaches screenshots to the xcresult bundle. Run it any time you change theme tokens, asset catalogs, or want a per-build visual baseline:

```sh
rm -rf /tmp/storehop-tour.xcresult
xcodebuild test -project Storehop.xcodeproj -scheme Storehop \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -only-testing:StorehopUITests/DesignSystemTourTest \
  -resultBundlePath /tmp/storehop-tour.xcresult \
  CODE_SIGNING_ALLOWED=NO
# Open the result bundle in Xcode, OR pull the raw PNGs:
for f in /tmp/storehop-tour.xcresult/Data/*; do
  case $(file "$f") in *PNG*) cp "$f" "/tmp/${f##*/}.png";; esac
done
```

## Theme + design system

Color tokens live in [`Storehop/Resources/Assets.xcassets/Colors/`](Storehop/Resources/Assets.xcassets/Colors/) as Asset-Catalog Color Sets, each with explicit light + dark hex values that match Android's [`Color.kt`](../app/src/main/java/com/storehop/app/ui/theme/Color.kt) byte-for-byte. `Color+Storehop.swift` is the API surface.

**Important asset-catalog convention:** the `Colors/` group must **not** be a namespace (it's a plain organizational folder). The inner `Brand/`, `Surface/`, `Text/` groups **are** namespaces. The Swift code does `Color("Brand/Primary")` etc., so `Colors/Contents.json` must have **no** `provides-namespace` key.

The user's theme choice (System / Light / Dark) is stored under `storehop.themeMode` in `UserDefaults` and cloud-synced to `/userPrefs/{uid}` in Firestore via `UserPreferencesSync`. `RootView` applies `.preferredColorScheme(themeMode.preferredColorScheme)` so the override works regardless of system setting.

## App icon

Ships at `Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png` — 1024×1024, opaque RGB (no alpha, App Store compliant). Generated from [`design/shophop-icon-512.png`](../design/shophop-icon-512.png) via `sips` upscale + Core Graphics flatten onto sage `#5A7A5C` (matching Android's `ic_launcher_background`). iOS applies its own rounded-square mask at display time.

To regenerate from the source:

```sh
sips -s format png --resampleHeightWidth 1024 1024 \
     design/shophop-icon-512.png \
     --out /tmp/raw-1024.png
swift <<'SWIFT'
import CoreGraphics; import ImageIO; import UniformTypeIdentifiers; import Foundation
let src = CGImageSourceCreateWithURL(URL(fileURLWithPath: "/tmp/raw-1024.png") as CFURL, nil)!
let img = CGImageSourceCreateImageAtIndex(src, 0, nil)!
let ctx = CGContext(data: nil, width: 1024, height: 1024, bitsPerComponent: 8,
                    bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                    bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
ctx.setFillColor(red: 90/255, green: 122/255, blue: 92/255, alpha: 1)
ctx.fill(CGRect(x: 0, y: 0, width: 1024, height: 1024))
ctx.draw(img, in: CGRect(x: 0, y: 0, width: 1024, height: 1024))
let out = URL(fileURLWithPath: "ios/Storehop/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png")
let dst = CGImageDestinationCreateWithURL(out as CFURL, UTType.png.identifier as CFString, 1, nil)!
CGImageDestinationAddImage(dst, ctx.makeImage()!, nil)
CGImageDestinationFinalize(dst)
SWIFT
```

## Firebase setup

The app expects a `GoogleService-Info.plist` at the bundle root. **It is not committed to git** (matches Android `google-services.json`). To get one:

1. Open the Firebase Console for the existing Storehop project.
2. Add an iOS app with bundle id `com.storehop.app`.
3. Download `GoogleService-Info.plist`.
4. Drop it into `ios/Storehop/Resources/` (and re-run `xcodegen generate` so it gets included in the build).
5. Note the `REVERSED_CLIENT_ID` value — Xcode reads it as `$(GOOGLE_REVERSED_CLIENT_ID)` for the URL-scheme registration in `project.yml`.

For local unit testing without a real project, a CI-placeholder plist works (the test target uses no-op Firebase stubs). The `.github/workflows/ios-ci.yml` writes one inline before each CI run.

## App Store submission

See [`docs/ios-app-store-submission.md`](../docs/ios-app-store-submission.md) for the full walkthrough.

Required before first submission:

- [ ] Real `GoogleService-Info.plist` from the production Firebase project
- [ ] `DEVELOPMENT_TEAM` populated in `project.yml` (currently empty)
- [ ] `PrivacyInfo.xcprivacy` privacy manifest declaring required-reason API usage
- [ ] `premium_lifetime` in-app product created in App Store Connect (non-consumable, $7.99 USD)
- [ ] TestFlight build uploaded + 2-device manual smoke (Mike + Amanda household flow)
- [ ] App Store Connect metadata, screenshots, privacy answers

## Project layout

```
ios/
├── project.yml                    # XcodeGen spec — single source of truth
├── Storehop/
│   ├── App/                       # @main + DI container + E2E fixture seeder
│   ├── Auth/                      # Firebase auth wrappers + LocalOnly session
│   ├── Billing/                   # StoreKit2 + EntitlementRepository
│   ├── Data/                      # DAOs, DB, models, repos
│   ├── Sync/                      # SyncEngine + PullCoordinator + DTOs
│   ├── UI/                        # SwiftUI views, view models, theme tokens
│   └── Resources/                 # Assets.xcassets, xcstrings, seed JSONs
├── StorehopTests/                 # XCTest unit tests
└── StorehopUITests/               # XCUITest E2E suite
```

`Storehop.xcodeproj` is **generated** — do not edit by hand. To change build settings, dependencies, or target structure, edit `project.yml` and re-run `xcodegen generate`.

## Cross-platform invariants

The iOS app shares a Firestore project with Android, so the wire format must stay aligned:

- Same entity field names (camelCase, matching Kotlin `@SerialName` on the DTOs)
- Same seed IDs (`store_lidl`, `cat_produce`, etc.) and same `SEED_TIMESTAMP = 1_730_000_000_000`
- Per-store xref state on `item_store_xref.isNeeded` (not `items.isNeeded`)
- Cross-store cascade on purchase, with snapshot-timestamp-precise undo
- Soft-delete tombstones (`deletedAt`) — never hard-delete
- Same `premium_lifetime` IAP product ID (entitlements are platform-isolated; this is a naming convention only)

If you change anything that crosses the wire, change it on both platforms in the same release.
