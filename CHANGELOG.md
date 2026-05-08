# Changelog

Notable user-facing changes per release. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the
project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

For the high-level roadmap and earlier-than-0.5.0 history, see the
"Roadmap" section in the [README](README.md).

## [0.5.5] - 2026-05-08

### Fixed

- Renaming a category or store now works even when the target name
  was previously used by something you've since deleted. Mike
  reported this after his CSV import: trying to rename "Pet" → "Pets"
  failed with a vague "Could not rename" error because a long-deleted
  "Pets" category was still owning the name behind the scenes
  (Storehop keeps soft-deleted rows around for cloud sync). The
  database constraint that caused the conflict has been replaced —
  deleted entries no longer block name reuse. Bumps schema to v6
  with a one-time migration that's a no-op for your data.
- The rename dialog also now shows a clear "A category named \"X\"
  already exists" inline message when the target name collides with
  one of your **existing** categories or stores, instead of the
  generic failure message. A case-only change of a row's own name
  (e.g. "Pets" → "pets") is still allowed.

## [0.5.4] - 2026-05-08

### Fixed

- Settings → Data: the four import / export buttons now stack
  vertically at full card width instead of squeezing into a 2x2 grid.
  Mike-reported: "Export categories" and "Import categories" wrapped
  to two lines while "Export items" / "Import items" stayed on one,
  leaving the buttons visibly mismatched. Stacking gives every action
  a single line at the same width and height.

## [0.5.3] - 2026-05-08

### Fixed

- The snackbar after a CSV categories import now correctly shows the
  number of duplicate categories that were skipped. Before, importing
  a categories CSV that contained a name that already existed would
  correctly skip the duplicate (preserving your existing category
  unchanged) but the snackbar still read "Skipped 0 duplicates,"
  which was misleading. The skip itself always worked; only the
  reported count was wrong.

## [0.5.2] - 2026-05-08

### Added

- CSV import + export of items and categories from Settings → Data.
  Pick a destination via the system file picker and Storehop writes a
  documented CSV (`name, category, stores, brand, notes, quantity,
  isStaple, isPriority` for items; `name, icon` for categories).
  Import is **non-destructive**: any item whose name already exists
  is skipped — your current data is never modified or deleted by an
  import. A snackbar reports `"Imported X items, Y categories,
  Z stores. Skipped N duplicates."` with an Undo action that
  soft-deletes only the rows just inserted.

### Changed

- Item, brand, store, and category name fields now auto-capitalize
  every word as you type, not just the first letter (was Sentences in
  v0.5.1, now Words). Mike asked for proper Title Case on his
  multi-word item names.
- The item-edit back arrow now shows a "Discard changes? / Keep
  editing" confirmation when you have unsaved edits, so a stray tap
  doesn't lose your work.

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
