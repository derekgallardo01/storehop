# StoreHop — landing-page assets

Reproducible pipeline for the marketing screenshots and walkthrough video. The
capture runs against the **instrumented test graph** (offline `LocalOnly` auth,
no Firebase, no Google account) seeded with a curated demo dataset, so results
are deterministic.

## Outputs (git-ignored — regenerate, don't commit)

```
docs/marketing/
  screenshots/phone/{light,dark}/NN_<screen>.png    # 1080x2400
  screenshots/tablet/{light,dark}/NN_<screen>.png   # 2560x1600
  video/walkthrough.mp4   walkthrough.gif
```

Screens captured: Shop / Store Picker, Shop-at-Store, Items list, Item form,
Settings, Statistics, and (best-effort) Manage Categories, Household, Edit Aisle
Order — each in light **and** dark.

## Prerequisites

- Android SDK with the `Pixel_Phone` (Pixel 7, 1080x2400) and `Pixel_Tablet`
  (2560x1600) AVDs, plus `platform-tools` (`adb`) and `emulator`. The scripts
  auto-locate the SDK at `%LOCALAPPDATA%\Android\Sdk` (or `$ANDROID_SDK_ROOT`).
- `ffmpeg` on `PATH` (video only).

## Regenerate

```powershell
# Screenshots: phone + tablet, light + dark (boots each AVD, captures, pulls PNGs)
pwsh scripts/capture-android-screenshots.ps1
# ...or narrow it:
pwsh scripts/capture-android-screenshots.ps1 -Avds Pixel_Phone -Themes light

# Walkthrough video -> docs/marketing/video/walkthrough.{mp4,gif}
pwsh scripts/record-android-video.ps1 -Theme light
```

Both scripts build the debug + androidTest APKs (pass `-SkipBuild` to reuse
existing ones), boot the emulator, set the theme + a clean demo-mode status bar
(fixed 12:00 clock, full battery/signal, no notifications), run the tour/flow,
and collect the artifacts.

## How the demo data works

`DemoDataSeeder` ([app/src/main/java/com/storehop/app/data/demo/DemoDataSeeder.kt](../../app/src/main/java/com/storehop/app/data/demo/DemoDataSeeder.kt))
builds a curated dataset (≈5 stores incl. a one-off, ≈28 items across 8
categories with brands, staple/critical/Buy-today flags, needed-vs-checked mix,
and ~8 weeks of backdated purchase history for Statistics) via the public
repositories. Items carry no photos — the UI's colored initial-avatars keep the
demo free of copyrighted imagery.

Three consumers share it:
- `ScreenshotTourTest` / `DemoFlowTest` (androidTest) inject it directly.
- **Debug Settings loader:** Settings → Data → *Demo data (debug)* → **Load / Clear**
  (visible only in debug builds).
- **adb (debug builds), for seeding a real device before a manual recording:**
  ```
  adb shell am broadcast -a com.storehop.app.action.LOAD_DEMO_DATA  -p com.storehop.app
  adb shell am broadcast -a com.storehop.app.action.CLEAR_DEMO_DATA -p com.storehop.app
  ```

All of the above are `BuildConfig.DEBUG` / `src/debug`-only, so nothing ships in
the release build.

## Device frames

The raw PNGs are unframed (clean status bar), ready to drop into a device-mockup
tool for the landing page. Framing isn't done here (no ImageMagick/frame assets
on the build machine).

## iOS (run on a Mac)

`DemoDataSeeder.swift` + the extended `DesignSystemTourTest` mirror the Android
harness. On a Mac with Xcode:

1. Build the app; the seeder + tour are gated to DEBUG/UI-test builds.
2. Run the `DesignSystemTour` UI test (see [../app-store-screenshots.md](../app-store-screenshots.md)
   for the simulator device sizes) to capture the same screen set in light + dark.
3. Collect the PNGs from the test attachments / derived-data path noted in that doc.
