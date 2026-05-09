# Changelog

Notable user-facing changes per release. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the
project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

For the high-level roadmap and earlier-than-0.5.0 history, see the
"Roadmap" section in the [README](README.md).

## [0.5.7] - 2026-05-09

### Added

- **Hide / Show checked-off items toggle.** A check-circle icon in
  the Shop-at-Store top app bar that hides everything you've already
  checked off the list — both items you just bought *and* "always
  on the list" staples that aren't currently needed. Default is
  Show, preserving the existing behavior; the setting persists
  across launches. Mike reported that with several screens of
  struck-through rows above each unchecked item, the list was hard
  to use as a checklist while walking the aisles.
- **QuickAdd autocomplete.** The "Add an item…" field at the bottom
  of a store's list now searches your master Items library as you
  type. Tap a suggestion to tag the existing item to this store —
  no duplicate created. Hit Done with text that doesn't match
  anything → new master item created (existing behavior). Hit Done
  with text that exactly matches an existing item → silently
  re-tags the existing one. Mike-reported: typing a name that
  already existed in the master list was creating uncategorised
  duplicates; this fixes it at the data layer too.
- **In-app update prompt.** When you open StoreHop and a newer
  version is available on Play, the Play update sheet appears right
  inside the app — no need to navigate to the Play Store listing.
  Tap Update; the new build downloads in the background while you
  keep using your current shopping list. When it's ready, a small
  "Update ready · Restart now" bar appears at the bottom of the
  screen — tap it to install and relaunch in two seconds.

### Changed

- **Custom undo bar replaces the Material3 snackbar.** The
  "Marked X purchased / Undo" bar (Shop-at-Store) and the
  "Deleted X / Undo" bar (Store picker, after deleting a store)
  now share a hand-rolled component with three ways to dismiss:
  auto-dismiss after 3 seconds, a × close button, and swipe
  horizontally. Mike reported the previous Material3 snackbar wasn't
  auto-dismissing reliably across devices — accessibility services
  scaled the duration up by 5–10×, making a 4-second `Short`
  snackbar feel persistent. The 3-second timer is enforced
  manually so accessibility settings can't extend it.
- **Long-press anywhere on a store tile to reorder.** The previous
  6-dot drag-handle icon on each store tile is gone. Beta feedback:
  the icon wasn't discoverable ("she didn't know that's what that
  does"). Tap continues to navigate into the store; long-press on
  any part of the tile starts a drag-to-reorder.
- **Statistics → Activity card simplified.** Dropped the "Last 30
  days" and "Last 7 days" tiles. For users with under a month of
  history they all matched the all-time count, which made the card
  look broken. The 12-week trend chart already carries the recency
  signal much better than three rolling counters.
- **Shorter QuickAdd placeholder** — "Add an item…" instead of
  "Add an item to this store…" so the field doesn't wrap on smaller
  screens.

### Fixed

- The "Marked X purchased" undo snackbar lingered indefinitely on
  some devices because Material3 silently switches to an
  Indefinite duration whenever a snackbar has an action button.
  See "Custom undo bar" above for the replacement.
- In-app update flow no longer re-prompts when the activity returns
  to foreground after the user has already accepted Play's update
  sheet (was forcing a second tap to start the download).
- "Update ready · Restart now" bar now respects the Pixel
  gesture-nav inset; the button isn't covered by the system bar
  anymore.

### iOS

The iOS port (in development; not yet on the App Store) catches up
to Android parity for: the new undo bar (× + swipe + 3s),
QuickAdd autocomplete with the same name-match dedupe, CSV import /
export of items + categories, and a full Statistics screen with the
12-week trend chart (Charts framework). The hide-checked-off
toggle was added to iOS earlier in the day.

## [0.5.6] - 2026-05-08

### Changed

- The "Critical items needed" banner on the Shop screen now tells you
  *where to go first* instead of listing every priority item by name.
  Collapsed, it shows the total count plus the store that covers the
  most criticals — e.g. "5 critical items needed / Most at Pingo (3)"
  — so you can decide where to shop without scanning the per-store ⚠
  chips and comparing. Tap the banner to expand a per-store breakdown
  with the names. Also fixes the previous layout that grew unbounded
  once you had ~10+ criticals, eating the screen before any store rows
  were visible. Ties on count fall back to your manual store order.

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
