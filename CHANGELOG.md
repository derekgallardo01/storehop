# Changelog

Notable user-facing changes per release. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the
project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

For the high-level roadmap and earlier-than-0.5.0 history, see the
"Roadmap" section in the [README](README.md).

## [0.5.1] - 2026-05-07

### Changed

- Marking an item purchased at one store now clears it from every
  other store the item is tagged to. A single shopping trip
  satisfies the need everywhere — buying mozzarella at Lidl drops
  it from the Aldi and Pingo Doce lists too. The Undo on the
  snackbar fully reverses every store the cascade flipped.
- Item name, brand, store name, and category name fields now
  auto-capitalize the first letter as you type. Search fields are
  unchanged.

### Added

- Custom user-added categories now appear in a store's Edit Aisles
  screen as soon as you tag an item with that category at that
  store, so they become drag-reorderable like the seeded ones.

## [0.5.0] - 2026-05-07

### Added

- Manage Categories screen: add, rename, soft-delete, and undo
  user-added categories from the Items tab overflow menu.
- Per-store Edit Aisles screen: drag-reorder how categories appear
  in each store's Shop-at-Store grouping, with the order persisted
  per store.

### Fixed

- Settings → bottom-nav routing: tapping Items or Shop from
  Settings now lands on the right tab deterministically (was
  inconsistent due to the canonical NavController + saveState
  pattern misbehaving when the current route is a non-tab peer).

## [0.4.0] - 2026-04-30

### Added

- Pull-side Firestore sync: cold-launching a returning user
  rehydrates their data from the cloud before any push jobs run.
- Settings cloud-sync banner with Retry action when the pull is
  pending or has errored.

## [0.3.x] - 2026-04 (multiple patch releases)

### Added

- Anonymous-first onboarding with optional Google Sign-In via
  Credential Manager.
- Push-side Firestore sync engine.
- Item photos with Firebase Storage upload.
- Share-list-as-text from Shop-at-Store.
- Per-store check-off (per-store xref.isNeeded) — superseded in
  0.5.1 by cross-store cascade.
- Drag-reorder stores in the Store Picker.
- Theme picker (Follow system / Light / Dark) and language picker
  (English / European Portuguese).
- Quick-add bar at the bottom of Shop-at-Store.
- Undo snackbars and haptic feedback for check-offs and deletes.
- Pull-to-refresh on Items and Shop-at-Store.
- Release signing config, native debug symbols bundled into the
  release AAB.
- Privacy policy + Play Store submission listing answers.

### Fixed

- Orphan-uid data recovery when an anonymous user signs in with
  Google: existing items, stores, and photos transfer to the
  Google account on every uid change, not just the first.

## [0.2] - 2026-03

### Added

- Shop tab, Items tab, item Add/Edit form.
- Item photos.
- Share-list-as-text.

## [0.1] - 2026-03

### Added

- Data layer schema and theme foundation; placeholder MainActivity.
