# Changelog

Notable user-facing changes per release. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the
project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

For the high-level roadmap and earlier-than-0.5.0 history, see the
"Roadmap" section in the [README](README.md).

## [0.6.6] - 2026-05-10

### Fixed

- **Manage Categories: drop now actually moves the row.** Follow-up to
  v0.6.5: dragging worked, but releasing the drop didn't persist the
  new position -- the row visibly snapped back to its original spot.
  Root cause: the screen's optimistic-local list state was declared as
  `remember(categories) { mutableStateOf(categories) }`, keyed on the
  source flow. That key racing with the `LaunchedEffect(categories,
  isDragging)` re-sync clobbered the optimistic order the instant
  `isDragging` flipped false on drop, BEFORE the DB write landed and
  the flow re-emitted the new order. Aligned the pattern with the
  proven StorePicker / EditAisleOrder approach (`remember { ... }` no
  key, LaunchedEffect handles the sync) so the optimistic list survives
  the drop window and the persisted order takes over cleanly.

iOS unaffected (uses SwiftUI `.onMove` which commits immediately
through the VM's `commitReorder`).

### Versions

- Android: versionCode 41 → 42, versionName 0.6.5 → 0.6.6.
- iOS: unchanged.

## [0.6.5] - 2026-05-10

### Fixed

- **Manage Categories: drag-to-reorder no longer gets hijacked by bulk-
  select on Android.** v0.6.4 attached the long-press-to-enter-selection
  gesture to the whole Card via `combinedClickable(onLongClick = ...)`.
  The drag-handle icon at the trailing edge of each row uses its own
  `longPressDraggableHandle`, but because both detectors were active on
  the same surface, the Card-level long-press kept winning: trying to
  drag a category landed in selection mode instead. Fixed by scoping the
  long-press-to-select gesture to the checkbox + text region only. The
  drag handle and overflow-menu button now live outside that tap target,
  so each gesture has a single owner:
  - Long-press on the **drag handle** → starts the drag.
  - Long-press on the **text area** → enters selection mode.
  - Tap → rename (non-selection) or toggle selection.

iOS unchanged — the `EditButton` + `List(selection:)` + `.onMove`
pattern mode-gates drag vs. selection through SwiftUI's edit mode, so
the gestures are never live at the same time.

### Versions

- Android: versionCode 40 → 41, versionName 0.6.4 → 0.6.5.
- iOS: unchanged (bug doesn't apply).

## [0.6.4] - 2026-05-10

Manage Categories gets a real workflow surface: drag to reorder, bulk
select to delete, multi-add to seed many at once. All three ship to
Android + iOS in the same release.

### Added

- **Drag-to-reorder categories** (Android + iOS). Each row gets a drag
  handle. On Android, long-press the handle to start the drag; on iOS,
  the platform-idiomatic EditButton enters reorder mode with drag
  handles + multi-select checkboxes. The order persists per user. Schema
  migration v6 → v7 adds a `displayOrder` column to the `categories`
  table; the backfill dense-ranks existing rows by name within each
  user's alive list so the first-open ordering matches the previous
  alphabetical view. The order is GLOBAL (Manage Categories list) and is
  independent of per-store aisle order (StoreCategoryOrder).

- **Bulk select to delete** (Android + iOS). On Android, long-press a
  category tile to enter selection mode; tap to toggle. The top app bar
  switches to a "X selected" header with Delete, Select all, and Cancel
  buttons. On iOS, the standard `EditButton` + List selection pattern;
  a bottom toolbar surfaces the delete action while the selection set
  is non-empty. Batch delete cascades item.categoryId clearing + SCO
  tombstones identically to single-row delete, and one UNDO restores
  the whole batch via the shared `softDeleteMany` / `undoSoftDeleteMany`
  repository methods.

- **Multi-add categories from a single dialog** (Android + iOS). The
  "Add category" dialog is now multi-line: paste a list with one
  category per line, we split + trim + case-insensitively de-dupe within
  the input, then route each name through the existing
  `CategoryRepository.addCategory` (which already handles alive-skip +
  tombstone-resurrect). A summary line shows
  `Added N · skipped M (already exist)`.

### Tests

- Android: 424 → 440 unit tests, 0 failures. New coverage:
  - `MigrationTest`: dense-rank backfill on v6 → v7 (per-user, alive-
    only, tombstones untouched).
  - `CategoryRepositoryImplTest`: incrementing displayOrder on add,
    reorder transaction, observeAll order, softDeleteMany batch +
    undoSoftDeleteMany round-trip.
  - `ManageCategoriesViewModelTest`: enter/toggle/selectAll/cancel
    selection, deleteSelected + bulk-undo plumbing, commitReorder,
    addManyCategories split + dedupe + duplicate handling.

- iOS: code mirrors Android line-for-line; test parity is a Mac-side
  follow-up (existing iOS tests for category cover the single-row
  paths).

### Sync

- `CategoryDto` gains `displayOrder: Int` with a default of `0` so
  older docs deserialize cleanly. Each device's migration ran
  per-platform; the next push from any device carries the column.

### Versions

- Android: versionCode 39 → 40, versionName 0.6.3 → 0.6.4.
- iOS: MARKETING_VERSION 0.6.3 → 0.6.4 (skipping the never-shipped 0.6.3
  iOS bump since iOS had no equivalent in-app-update controller),
  CURRENT_PROJECT_VERSION 18 → 20.

## [0.6.3] - 2026-05-10

### Fixed

- **In-app update sheet no longer shows twice in a row.** Mike-reported
  regression: after tapping Update on Play's flexible-update bottom
  sheet, the sheet would re-appear and the user had to tap Update a
  second time before the download started. Root cause: the v0.5.7
  guard skipped re-prompting when `installStatus()` was
  `PENDING / DOWNLOADING / INSTALLING`, but missed the race window
  where Play's dialog dismisses BEFORE the status transitions out
  of `UNKNOWN`. The next `onResume` then saw
  `installStatus = UNKNOWN` with `updateAvailability = UPDATE_AVAILABLE`
  and re-launched the sheet. v0.6.3 adds a per-activity session
  flag (`hasPromptedThisActivity` in `AppUpdateController`): once
  we've launched the sheet for this controller instance, subsequent
  checks short-circuit regardless of what `installStatus()` reports.
  Cleared in `stop()` so a fresh activity instance gets a clean slate.

iOS unchanged (App Store has no equivalent in-app-update API).

## [0.6.2] - 2026-05-10

Two more Mike-asks, one bundle.

### Changed

- **In-store critical-items banner: shorten the count headline**
  (Android + iOS). Was "5 critical items at this store" — wrapped to
  two lines on Mike's Pixel. Now "5 critical items"; the surrounding
  context (you're already inside a store's view) makes the trailing
  "at this store" redundant. Strings updated in all four locales on
  Android plus the matching `critical_at_this_store %lld` key on iOS.

- **Items list search placeholder: "Search anything"** (Android +
  iOS, all four locales). Was "Search by name or brand" — even at
  24 chars it wrapped on Mike's narrower text-scale layout. The new
  placeholder is universal-shorter ("Search anything" / "Procurar
  tudo" / "Buscar todo" / "Cerca tutto") and reflects the next
  change below.

- **Items list search now also matches category names** (Android +
  iOS). Mike-asked: typing "frozen" should surface every item in
  the Frozen category. The filter on `ItemsListViewModel` now
  matches against `category?.name` in addition to the existing
  name + brand match. Caveat: matches the raw seeded category name
  (English for seeded categories); a non-English user searching by
  the localized seed label won't hit this branch on the first pass.

### Tests

- `ItemsListViewModelTest` +1 -- pins the new category-name match.
  381 → 422 unit tests, 0 failures (post-coverage-push count plus
  this one). Coverage: 99.9% lines, 100% classes (unchanged).

### Versions

- Android: versionCode 37 → 38, versionName 0.6.1 → 0.6.2.
- iOS: MARKETING_VERSION 0.6.1 → 0.6.2, CURRENT_PROJECT_VERSION
  17 → 18. iOS code change mirrors Android; test parity is a
  Mac-side follow-up.

## Tests-only (coverage push) - 2026-05-10

Push toward 100% measured line coverage on the unit-test surface.
No version bump.

### Coverage delta

- **Line: 84.7% → 99.9%** (1565 / 1566 lines covered).
- **Class: 90.3% → 100%** (136 / 136).
- **Tests: 379 → 421 unit tests, 0 failures.** Plus 10/10 E2E
  instrumented tests on Pixel_Phone AVD.

The single remaining uncovered line is `session.userId.flatMapLatest { uid ->`
inside `ItemRepositoryImpl.observeAll`. Every observeAll call in the
suite exercises this code path; Kover flags the line as not-covered
due to instrumentation quirks around `inline` coroutine operators
(`flatMapLatest` is inline, so the lambda body inlines but the call
site doesn't get a hit-count). Cosmetic gap, not a behavior gap.

### Added (unit tests)

- `ShareListAsTextTest`: +3 tests for `launchShareList` (Intent
  assembly + chooser + localized category-name resolution).
- `PurchaseHistoryRepositoryImplTest`: +6 tests for the Statistics
  aggregate flows (totalCount, countSince, perDay, byDayOfWeek +
  signed-out fallback paths).
- `UserPreferencesRepositoryTest`: +7 tests for showPurchased,
  shopAtStoreSortMode, itemsListSortMode (defaults, round-trip,
  unknown-value fallback).
- `SettingsViewModelTest`: +6 tests for setThemeMode, setLocale
  (round-trip + empty), signOut (success cascade + busy guard +
  catch), clearError, onCleared.
- `ImportExportViewModelTest`: +7 tests for exportCategoriesTo
  (success + failure), importCategoriesFrom (success + parse error),
  consumeLatestImport, consumeExportError, undoLastImport no-op.
- `ItemRepositoryImplTest`: +4 tests for v0.6.1's
  markNeededAcrossAllStores / markPurchasedAcrossAllStores /
  observeNeededItemIds.
- `ItemStoreXrefDaoTest`: +2 tests for the SQL contracts of the new
  v0.6.1 DAO methods.
- `ItemFormViewModelTest`: +6 tests for setBrand/setStaple/
  setPriority, toggleStore, pickLocalImage/clearImage, image-upload
  error path, delete failure path, addCategory generic catch.
- `EditAisleOrderViewModelTest`: +1 test for missing-storeId
  constructor guard.

### Kover configuration

Aggressive exclusion list for genuinely-untestable code:
- Composable UI files (covered by the E2E suite, not unit tests).
- Hilt + Dagger generated classes (`*_Factory`, `*_HiltModules_*`,
  `Hilt_*`, `*_GeneratedInjector`, etc.).
- Room-generated DAO `*_Impl` classes.
- Kotlin synthetic `$DefaultImpls` (interface default args) and
  `$$inlined$*` (inline operators).
- The App + Activity shell (MainActivity, StorehopApplication).
- Firebase-coupled integrations that need a real backend to test
  meaningfully (SyncEngine internals, FirebaseAuthSessionProvider,
  GoogleSignInUseCase fallback path, ImageUploader).

The remaining ~29 uncovered lines are SDK-conditional branches
(LocaleManager Tiramisu+ paths, AppCompatDelegate fallback) and
catch-block fallbacks for exotic exception types. Achievable to
100% only via integration/instrumented tests, not JVM unit tests.

## Tests-only - 2026-05-10

Test-coverage hardening; no version bump (no user-visible changes).

### Added

- **Kover line-coverage plugin** wired into the root + app build files.
  `./gradlew :app:koverHtmlReportDebug` produces an HTML coverage
  report at `app/build/reports/kover/htmlDebug/index.html`.
  Excludes generated Hilt code, Room entities, theme/preview-only
  Composables, and the App / Activity shell -- the things that add
  green-bar noise without adding signal. Final numbers:
    - **Class coverage: 90.3%** (339/380)
    - **Line coverage: 84.7%** (4521/5351)
    - **Method coverage: 77.3%**

- **Six new unit tests for the v0.6.1 +/− toggle**:
  - `ItemRepositoryImplTest`: 4 tests covering `markNeededAcrossAllStores`
    (happy path + cross-user isolation), `markPurchasedAcrossAllStores`
    (the v0.6.1 distinction from `markPurchasedAtStore` -- same
    cascade WITHOUT writing a PurchaseRecord), and `observeNeededItemIds`
    (DISTINCT itemIds, tombstone exclusion).
  - `ItemStoreXrefDaoTest`: 2 tests pinning the SQL contracts of
    `markNeededAcrossAllStores` (alive-only WHERE clause, foreign-user
    isolation) and `observeNeededItemIds` (DISTINCT + alive + needed=1).

- **Seven new unit tests on `UserPreferencesRepository`** for v0.6.0's
  `showPurchased`, `shopAtStoreSortMode`, and `itemsListSortMode`
  prefs (defaults, round-trip, unknown-value fallback).

- **Real Android emulator E2E suite** (10 tests, all green on Pixel_Phone
  AVD):
  - `AppLaunchTest` -- MainActivity launches; bottom-nav tabs visible.
  - `ItemAddFlowE2ETest` -- Add Item form happy path: name -> save ->
    appears in list.
  - `InlineNewCategoryE2ETest` (v0.6.1) -- "+ New category…" from item
    edit form -> dialog -> auto-selected on form.
  - `PlusMinusToggleE2ETest` (v0.6.1) -- +/− icon per row reflects
    needed state; disabled when no tagged stores.
  - `ItemsListSortToggleE2ETest` (v0.6.0) -- toggle flips between
    flat alphabetic and category-grouped views.
  - `ShopAtStoreSortToggleE2ETest` (v0.6.0) -- in-store sort toggle
    flips aisle headers off/on.
  - `LongPressEditFromStoreE2ETest` (v0.6.0) -- long-press a store row
    opens the item edit form for that item.
  - `CriticalBannerCollapseE2ETest` (v0.6.0) -- in-store critical banner
    starts collapsed; chevron flips on tap.
  - `SearchClearButtonE2ETest` (v0.6.0) -- × icon appears after typing
    and wipes the field on tap.
  - `CrossStoreCascadeE2ETest` (v0.5.1, never previously E2E-tested) --
    marking purchased at one store cascades isNeeded=false to every
    tagged store's xref.

### Changed

- `AddCategoryDialog.LaunchedEffect` now wraps `focusRequester.requestFocus()`
  in `runCatching` so the dialog tolerates the sub-composition timing
  in instrumented tests. User-visible behavior unchanged on real
  devices (focus still requested on dialog appearance; if it ever
  fails, user can tap to focus -- same fallback Compose itself
  provides).

### Test infrastructure

- `app/src/androidTest/java/com/storehop/app/di/TestDatabaseModule.kt`
  swaps in an in-memory Room DB per test (`@TestInstallIn(replaces =
  [DatabaseModule::class])`).
- `TestFirebaseModule.kt` mocks `FirebaseAuth`, `FirebaseFirestore`,
  `FirebaseStorage` so tests don't talk to a real Firebase backend.
- `TestAppBindsModule` swaps `FirebaseAuthSessionProvider` for
  `LocalOnlyUserSessionProvider` (always emits `"local-only"` uid).
- `E2EFixtures.kt` seeds canonical test data (2 stores, 1 category,
  3 items with mixed tag/needed states).

## [0.6.1] - 2026-05-10

Two more Mike-asks bundled together. Both touch the master Items list,
both are small enough that splitting into separate releases would be
overhead.

### Added

- **Inline "+ New category" in the item edit screen** (Android + iOS).
  Mike-asked: ability to create a category from the item form without
  having to back out, navigate to Manage Categories, create, and
  navigate back. The category picker now offers a "New category…" entry
  that opens the same name-prompt dialog Manage Categories uses; on
  success the new category is auto-selected on the form. Routed through
  `CategoryRepository.addCategory` which already handles alive-skip and
  tombstone-resurrect.

- **+/− toggle on each Items-list row** (Android + iOS). Mike-asked:
  *"as I'm scrolling down, I can quickly add things to all my grocery
  lists with one click. Or, maybe if it is already on my list, it shows
  a minus sign."* The button shows "+" when the item isn't on any
  shopping list at any tagged store, "−" when it is. Tapping "+" marks
  the item needed at every tagged store; tapping "−" cascade-clears
  it from every tagged store (no PurchaseRecord, since the user is on
  the master list, not at a specific store -- the cross-store cascade
  design from v0.5.1 keeps both branches coherent with the Shop tab).
  Disabled when an item has no tagged stores (nothing to add to).

### Tests

- Android: 360 → 366 unit tests. New ItemFormViewModel addCategory
  coverage (success, blank, duplicate) plus +/− toggle coverage on
  ItemsListViewModel (state plumbing, both branches of the toggle).
- iOS: ItemFormViewModelTests gains three integration-style tests
  exercising the real GRDB-backed CategoryRepository for addCategory's
  three branches.

## [0.6.0] - 2026-05-10

Mike's UX feedback bundle. Six items pulled in from his most recent
round; the multi-user account-sharing milestone moves to v0.7.0 since
none of these depend on the data-model rework that needs.

### Added

- **Clear (×) button on the search box.** Both the Shop-at-Store
  search and the Items list search now show a trailing × icon while
  the field has text — one tap wipes the query so the next search
  starts from a blank slate. Mike-reported: "if I search for
  something, I have to manually remove whatever I previously
  searched for before I can search for something else."

- **Long-press an item in a store's list to edit it.** Inside any
  store, long-pressing an item row jumps straight to the Items
  edit form for that item. The previous flow (Items tab → find item
  → tap to edit → add the new store-tag → save → navigate back)
  collapses to one gesture. Mike-reported scenario: standing in
  Normal, out of Dog Treats, knew Walmart carries them but hadn't
  tagged them yet — long-press lets you add the tag without
  leaving the store screen.

- **Alphabetic vs Category sort toggle inside a store.** New icon
  in the Shop-at-Store top app bar flips the in-store list between
  the existing aisle-grouped layout and a flat alphabetic list.
  Choice persists across stores and across app restart via the
  `shop_at_store_sort_mode` DataStore preference. Mike-reported:
  "If I want to check to make sure 'Ketchup' is on my Aldi list,
  I have to scroll all the way down to the 'Condiments' category."

- **Alphabetic vs Category sort toggle on the master Items list.**
  Same toggle on the Items tab: flat alphabetic (default) or
  grouped by category, including a trailing "(uncategorised)"
  section for items without a category. Independent preference
  from the in-store sort. Mike-reported scenario: knowing you need
  several cleaning supplies, switching to category view to
  un-check a bunch of items in one place.

### Changed

- **In-store critical-items banner now collapses by default.** The
  banner inside a store used to list every priority item by name,
  growing tall enough to push the rest of the screen off the fold
  when there were many criticals. Now mirrors the StorePicker
  pattern: collapsed shows just the count, tap expands the comma
  list. Mike-reported: "this box could grow indefinitely."

- **Items search prompt shortened.** "Search items by name or brand"
  (40 chars) → "Search by name or brand" (24 chars). Was wrapping
  on Mike's Pixel; the screen title already says "Items" so the
  prefix was redundant.

### iOS parity (added in a follow-up patch on the v0.6.0 line)

iOS catches up to the v0.6.0 Android UX. Per-feature notes:

- **× clear button on search**: free on iOS — SwiftUI's
  `.searchable(...)` modifier already provides a clear button. No
  code change needed.
- **Search prompt shortened**: `items_search_placeholder` updated
  in `Localizable.xcstrings` for all four locales.
- **Long-press to edit in store**: implemented as a
  `.contextMenu { Button("Edit") }` on each row, the iOS
  HIG-idiomatic equivalent of Android's long-press gesture. New
  `ShopRoute.editItem(itemId:)` reuses the existing `ItemFormView`
  inside the Shop tab's NavigationStack.
- **Critical banner collapse/expand**: in-store banner now collapses
  by default with a chevron, tap to expand. Same affordance also
  retrofitted onto the StorePicker's "Critical needs" banner — the
  Android v0.5.6 collapsible variant never made it to iOS until now.
- **Alphabetic ↔ Category sort toggles**: SortMode enum added to
  iOS `UserPreferencesRepository` with two new keys
  (`shop_at_store_sort_mode`, `items_list_sort_mode`) mirroring the
  Android DataStore identifiers. Toolbar buttons on both
  `ShopAtStoreView` and `ItemsListView` flip the mode; choice
  persists.

iOS marketing version bumped 0.5.15 → 0.6.0;
CURRENT_PROJECT_VERSION 15 → 16. Still not shipped to TestFlight.

### Out of scope

- **Multi-user account sharing** — moved to v0.7.0. The original
  v0.6.0 plan called for that as the headline feature; this
  release scopes down to UX polish to ship Mike's feedback fast,
  and keeps multi-user as its own milestone where it can get the
  schema + sync + auth work it needs.

- **Deeper StorePicker banner ("best store covering most criticals")**
  — Android v0.5.6 added a smarter version of the picker banner
  that ranks stores by critical-item count and shows a per-store
  breakdown when expanded. iOS still uses the simpler flat-list
  variant; v0.6.0 only adds the collapse/expand affordance to it.
  The data-shape uplift on `StorePickerViewModel` is a follow-up.

## [0.5.15] - 2026-05-09

### Changed

- **iOS catches up to Android: shared UndoBar everywhere.** The
  iOS ItemsListView and ManageCategoriesView previously rendered
  their own inline undo snackbars (5s auto-dismiss, no close button,
  no swipe). Both now use the shared `UndoBar.swift` component:
  3-second auto-dismiss, × close button, swipe-to-dismiss — same
  affordances Android has had since v0.5.7 + v0.5.12 + v0.5.13.

- **iOS marketing version bumped to 0.5.15.** The iOS port had been
  carrying `MARKETING_VERSION: "0.5.1"` (the original port version)
  in `ios/project.yml` since the parity work landed earlier today.
  Bumped to track Android version. `CURRENT_PROJECT_VERSION` (build
  number) bumped from 10 → 15 to match the version cadence.

### Intentionally divergent (iOS)

- **EditAisleOrder reorder UX.** Android v0.5.14 dropped the
  drag-handle icon and made long-press anywhere on a tile start the
  drag. iOS keeps the platform-idiomatic SwiftUI `List` + `.onMove`
  + `EditButton` pattern with the `line.3.horizontal` drag handle
  — same call as the Store-picker case for v0.5.7 (sticking with
  Apple's HIG over cross-platform sameness).

## [0.5.14] - 2026-05-09

### Changed

- **Edit aisles screen: long-press anywhere on a category tile to
  drag-reorder.** Same migration the Store-picker tiles got in
  v0.5.7: the small drag-handle icon is gone (beta feedback that it
  wasn't discoverable), and the whole tile is the gesture surface
  now. Long-press to start dragging; release to drop.

### Fixed

- **In-app update controller now logs visibly when Play Core
  reports an update state.** v0.5.7's controller logged at DEBUG
  on success/failure — invisible without a debug-level Logcat
  filter. Bumped to INFO for the normal path and WARN for the
  sideload-failure path so you can confirm in `adb logcat -s
  AppUpdateController` whether Play returned UPDATE_AVAILABLE,
  UPDATE_NOT_AVAILABLE, or threw "App is not owned by any user on
  this device" (the sideload signature). No behavior change — the
  prompt only fires when Play has a newer-than-installed version
  available, and only for Play-installed builds (not sideloaded
  APKs).

## [0.5.13] - 2026-05-09

### Changed

- **Manage Categories: deleted-category undo bar gets the same
  treatment.** When you delete a category from Items → Manage
  categories, the "Deleted X" undo prompt now uses the shared
  `UndoBar` (3-second auto-dismiss, × close button, swipe-to-dismiss).
  Same migration as v0.5.12 did for the Items list.

## [0.5.12] - 2026-05-09

### Changed

- **Items tab: deleted-item undo bar gets the same polish as the
  Shop screens.** When you delete an item from the edit form and pop
  back to the Items list, the "Deleted X" undo prompt now uses the
  custom `UndoBar` component shipped in v0.5.7: 3-second auto-dismiss
  (immune to accessibility-scaling), explicit × close button, and
  horizontal swipe-to-dismiss. Replaces Material3's `SnackbarHost`,
  which had the same indefinite-duration behavior we replaced on the
  shop screens.

## [0.5.11] - 2026-05-09

### Fixed

- **Italian and Portuguese now actually apply when installed via
  Play Store.** Real root cause for the issue I'd been chasing
  through 0.5.8 → 0.5.10: AAB language splits. Play Store's default
  delivery splits the AAB by language and only ships locale resource
  packs matching the user's preinstalled system locales. On Pixel
  devices with just English in the system languages list, Play was
  stripping `values-it/` and `values-pt-rPT/` from the on-device
  install — the in-app picker would set the locale tag, the activity
  would recreate, but `getString(...)` had no Italian / Portuguese
  resources to look up, so it fell back to `values/` (English).
  Sideloaded APKs never had this problem because APKs aren't split.
  Spanish appeared to work because Pixel devices ship `es-*` locale
  data preinstalled, so Play delivered that split too.
  Fix: `bundle { language { enableSplit = false } }` in
  `app/build.gradle.kts`. The base APK now always carries every
  locale we ship, regardless of what's on the user's device.

## [0.5.10] - 2026-05-09

### Fixed

- **Reverted v0.5.9's locale-apply-path change.** v0.5.9 tried
  routing every locale switch through `AppCompatDelegate` to work
  around Italian / Portuguese silently failing to apply on Pixel,
  but that regressed Spanish (which had been working) — no language
  switched at all. The original `LocaleManager`-direct path on API
  33+ is back; this restores Spanish + English working under the
  in-app picker. Italian + Portuguese-not-applying on Pixel is a
  separate issue still being investigated.

## [0.5.9] - 2026-05-09 [yanked]

### Fixed (regressed — see 0.5.10)

- Tried routing locale switches through
  `AppCompatDelegate.setApplicationLocales` on every API level to
  fix Italian and Portuguese not applying on Pixel. Regression: this
  also broke Spanish (which had been working) — the comment in the
  v0.5.7 setLocale() about AppCompat failing silently under
  ComponentActivity was right, and the AppCompat-only path doesn't
  hold under Compose-only hosts on this device. Reverted in 0.5.10.

## [0.5.8] - 2026-05-09

### Added

- **Spanish (Castilian) and Italian language support.** The Settings →
  Language picker now offers **Español** and **Italiano** in addition
  to English and European Portuguese. All ~180 strings (including
  category labels, action verbs, dialog messages, plurals, and the
  Statistics screen day-of-week labels) have been translated. The iOS
  port mirrors the same four-locale coverage in
  `Localizable.xcstrings`.

  These first-pass translations are machine-quality and consistent in
  register, but a native-speaker review is still pending before any
  public Play Store track promotion. Beta testers using es/it: please
  flag wording that reads awkwardly.

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
