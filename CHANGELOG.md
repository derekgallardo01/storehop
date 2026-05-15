# Changelog

Notable user-facing changes per release. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the
project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

For the high-level roadmap and earlier-than-0.5.0 history, see the
"Roadmap" section in the [README](README.md).

## [0.8.0] - 2026-05-12

**Premium IAP — inviter-pays household sharing + gated CSV export
(Android).** v0.7.1.2 added the Play Billing Library to the
classpath; v0.8.0 actually wires it up. The app stays Free at install,
but two power-user surfaces now require a one-time $7.99 unlock.

### Pricing + scope

- **Product**: one-time IAP, product ID `premium_lifetime`. Price set
  in Play Console (configured at $7.99 USD at ship; Play handles
  local-market conversions automatically).
- **Free**: master list, multi-store tagging, cross-store cascade,
  Firestore + Storage cloud sync, Statistics, **CSV import** (kept
  free as the onboarding hook for users moving from another grocery
  app), four UI languages, theme + sort prefs (cloud-synced as of
  v0.7.1), Force-sync-now (v0.7.1), and **joining + using an existing
  shared household** including marking items purchased and the
  cross-store cascade.
- **Premium**: generating new household invite codes + CSV export
  (items and categories).

### Inviter-pays model

Per Apple / Google IAP policy, entitlements are per-platform and
device-local — no cloud sync. The "Mike + Amanda" canonical case
(Mike asked for shared lists in v0.6.0 planning) works under
**inviter-pays**: Mike buys Premium once → he can generate invite
codes → Amanda accepts free → Amanda uses the shared household
free. Only invite *creation* is gated; *joining* + everything-else-
after-joining is unconditionally free. Mirrors how Family Sharing
works for paid apps and avoids forcing every household member to
pay independently.

### Grandfather clause

Two paths grant the silent `LegacyUser` entitlement (functionally
identical to `Premium` for UI gating):

1. **Date-based**: any user whose Firebase account
   `creationTimestamp` predates `V0_8_RELEASE_DATE_MS` (the
   constant in `EntitlementRepository.kt`) — covers the closed-test
   cohort that's been beta-testing for free.
2. **Email allowlist** (`PREMIUM_VIP_EMAILS`): explicit set of
   beta-tester emails that bypass the date check entirely so they
   keep free Premium even after creating fresh Firebase accounts.
   Currently lists the dev account, Mike (`mikehaynes@gmail.com`),
   and Amanda (`amandafrost79@gmail.com`).

Mechanism: on every uid emission, the check recomputes the flag
from scratch — VIP email match OR pre-v0.8 timestamp → grant; else
clear. This **fixes a sticky-flag bug** where the legacy flag, once
set, never cleared: if a VIP previously signed in on a device and a
non-VIP signed in afterward, the non-VIP would inherit Premium.
Now sign-out + sign-in-as-non-VIP correctly flips entitlement back
to NotEntitled.

Mike + Amanda + the dev account all hit the VIP-email branch; every
other existing tester hits the date-based branch.

### Added (Android)

- **BillingManager** (`app/src/main/java/com/storehop/app/billing/BillingManager.kt`)
  wraps `BillingClient` 7.1.1. Started from `StorehopApplication.onCreate`;
  manages connection lifecycle, product queries, purchase flow,
  acknowledgement (required by Google within 3 days or auto-refund),
  and the "Restore purchases" path.
- **EntitlementRepository**
  (`app/src/main/java/com/storehop/app/billing/EntitlementRepository.kt`)
  is the single source of truth for `Entitlement`
  (`NotEntitled` | `Premium` | `LegacyUser`). Combines BillingManager's
  live purchases flow with the grandfather flag. Persists the result
  to local DataStore as a fast-startup cache so cold launches don't
  flicker through `NotEntitled` while BillingClient connects (~500 ms).
- **Settings → Storehop Premium** upsell card (`UpgradeToPremiumCard`
  in `SettingsScreen.kt`). Visible only when not entitled; lists the
  two unlocked features, primary CTA "Unlock for $7.99" (price is
  the Play-localized `formattedPrice` so EU / UK / non-USD markets see
  the correct symbol + decimal), plus a "Restore purchases" link for
  users who bought on another device of the same Google account.
- **Gated buttons** on `HouseholdScreen` (Generate Invite) and
  `SettingsScreen → Data` (Export Items, Export Categories). Locked
  buttons render the Play-localized price and tap-to-purchase. Import
  buttons unchanged.

### Changed (Android)

- `UserPreferencesRepository`: 3 new local-only DataStore keys
  (`legacy_user_granted`, `legacy_check_done_for_uid`,
  `cached_entitlement`). **Explicitly excluded** from
  `UserPreferencesSnapshot` + the cloud-sync push — per platform
  policy, entitlement state stays device-local.
- `HouseholdViewModel` + `SettingsViewModel` gain entitlement +
  `premiumPrice` flows; tests pass relaxed mocks for the two new deps.
- Strings: 17 new keys × 4 locales (en / pt-PT / es / it) for the
  upsell card, gated button labels, and purchase-result snackbars.

### Firestore security rules

No changes — entitlement state is local-only and never reaches
Firestore. The v0.7.1 rules ship unchanged.

### Fixed (mid-cycle patches before final ship)

- **`.1` — VIP allowlist** (commit `42bc23f`). The date-based
  grandfather isn't enough for testers whose Firebase accounts may
  have been created after the v0.8 cutoff (notably Amanda, who just
  joined Mike's household). Added an explicit
  `PREMIUM_VIP_EMAILS` set + a real bug fix: the legacy flag now
  recomputes per-uid instead of staying sticky-true forever (which
  would have leaked Premium to non-VIPs signing in on a VIP's
  device). versionCode 60 → 61.
- **`.2` — versionCode 61 → 62** (commit `c38aa42`). Play Console
  rejected the 61 upload with "version code already used" — looks
  like a Play-side cache from an earlier draft. Bumped to 62, no
  code changes.
- **`.3` — VIP allowlist: add Mom** (`nachamartinez@gmail.com`).
  Same VIP-email branch as Mike + Amanda. Mirrored to both
  `PREMIUM_VIP_EMAILS` (Android) and `premiumVipEmails` (iOS).
  Android versionCode 62 → 63.
- **`.4` — pull no longer resurrects local pending-sync rows.**
  Mike reported a "uncheck Aldi → Save → Aldi comes back" bug. Root
  cause: `PullWriteDao.replaceAllForUid` was upserting every cloud
  row blindly, including ones whose primary key matched a local
  row with `pendingSync = 1`. When a pull raced a push, the cloud's
  still-alive row (the push hadn't fired yet) resurrected Mike's
  soft-delete, and the mapper hardcoded `pendingSync = false` so the
  push side never even tried again. Fix: each entity DAO gains a
  `pendingPushIds(householdId)` / `pendingPushKeys(householdId)`
  snapshot read; PullWriteDao filters cloud lists against those
  sets before each `upsertFromCloud`. Local pending edits survive a
  pull until they push successfully. Four new `PullWriteDaoTest`
  cases pin the contract: pending xref preserved, pending xref +
  unrelated cloud xref both correctly handled, non-pending xref
  still overwritten by cloud, pending item preserved.
  Android versionCode 63 → 64.
- **`.5` — item form no longer shows tombstoned stores as checked.**
  Same Mike-reported symptom as `.4` (uncheck Aldi → Save → Aldi
  comes back), but a *different* root cause that survived the .4
  pull-guard fix. The data layer correctly tombstones the xref —
  Shop → Aldi confirms the item is gone — but reopening the item
  form re-renders Aldi as selected, and saving in that state
  resurrects the alive xref. Root cause:
  `ItemWithCategoryAndStores` (the Room `@Relation` + `@Junction`
  used to load the row) generates a JOIN that does NOT apply a
  `WHERE deletedAt IS NULL` filter on the bridging
  `item_store_xref` table, so soft-deleted xrefs still surface
  their target store via the join. The form was reading
  `row.stores.map { it.id }` straight off that leaky join.
  Tactical fix: new `ItemRepository.aliveStoreIdsForItem(itemId)`
  reads via `ItemStoreXrefDao.findForItem` (which *does* filter
  tombstones), and `ItemFormViewModel.init` sources `storeIds`
  from that instead of from `row.stores`. New
  `ItemFormViewModelTest` case pins the contract: a row whose
  `@Junction` join still includes Aldi but whose alive-xref set
  is `{lidl}` resolves to `storeIds = {lidl}`. Two other
  consumers of `row.stores` (CSV export, Items-list +/− toggle)
  still read from the leaky join; the architectural fix
  (`@DatabaseView` for `alive_item_store_xref` + schema v8 → v9
  migration) is deferred to v0.8.1 so this patch can ship to Mike
  tonight with minimal risk. Android versionCode 64 → 65.

### Open items requiring user action

- **Play Console**: Create the `premium_lifetime` in-app product
  (one-time, $7.99). Already unblocked since v0.7.1.2 bundled Play
  Billing Library.
- **Verify** the grandfather cutoff `V0_8_RELEASE_DATE_MS` in
  `EntitlementRepository.kt` matches the actual ship moment.

### Tests

Ten new cases in `EntitlementRepositoryTest` pin the entitlement
contract: VIP email matches grant `LegacyUser`, case-insensitive
comparison, date-based grandfather, anonymous user stays
`NotEntitled`, sticky-flag re-evaluation on uid change, purchase
precedence over legacy, pending-purchase doesn't grant. Test infra
uses `TestScope.backgroundScope` for the application scope so the
long-lived collectors in `start()` auto-cancel; DataStore IO runs
on the test scheduler so `advanceUntilIdle()` drains writes.

### iOS catch-up + StoreKit2 mirror (bundled into iOS 0.8.0)

iOS skips marketing versions 0.7.0 and 0.7.1 — those never reached
TestFlight — and bundles every Android catch-up + the v0.8 IAP
work into a single **iOS 0.8.0** release. What's in it:

**v0.7.1 catch-up:**
- `UserPreferencesRepository` gains `snapshot`, `applyRemoteSnapshot`,
  `updatedAt`, `snapshotStream` — same shape as the Android
  DataStore-backed version. Every setter bumps `updatedAt` via an
  injected `Clock`.
- New `UserPreferencesSync.swift` reconciles `/userPrefs/{uid}` on
  every Firebase auth-state emission with the same LWW semantics
  (push if local newer / absent, apply if cloud newer, no-op if
  equal). 500 ms debounced observe-and-push loop after reconcile.
- `FirebaseAuthSessionProvider` accepts an optional
  `UserPreferencesSync` and fires `reconcile(uid:)` after each
  publish, so the next launch of iOS 0.8.0 captures existing prefs
  to cloud even if the user never opens Settings.
- `SyncEngine` gains `observeAllPendingCount(householdId:)` (sums
  every entity DAO's pending count + `household_members`) and
  `flushAllPending(householdId:uid:timeoutNanos:)` (synchronous push
  + wait for drain or 30s timeout).
- 7 DAOs gain `countPendingPush(householdId:)` (or `countPendingPush()`
  for `HouseholdMemberDao` which is uid-scoped).
- **Closes the v0.7.0 oversight** that Android fixed in v0.7.1.3:
  `SyncEngine` now has a 7th push job for `household_members` rows
  at `/memberships/{uid}/households/{hid}`. The personal-household
  membership row created at first launch finally pushes to cloud
  instead of sitting at `pendingSync = 1` forever.
- New `ForceSyncSection` SwiftUI Section in Settings → Data with the
  same four-state machine (idle / syncing / safeToUninstall /
  failed) and 9 new localized strings × 4 locales.

**v0.8 StoreKit2 + entitlement:**
- New `Entitlement` enum (`.notEntitled` / `.premium` / `.legacyUser`)
  + `isUnlocked` extension. Mirrors Android's sealed class.
- New `StoreKitManager` wrapping StoreKit2. Uses
  `Product.products(for:)` for the price lookup,
  `Transaction.currentEntitlements` for the initial scan,
  `Transaction.updates` for the live listener, and
  `Transaction.finish()` to acknowledge (StoreKit2's equivalent of
  Google's `acknowledgePurchase`). Exposes `product` and
  `hasPremiumPurchase` AsyncStreams plus `purchase()` and
  `restorePurchases()`.
- New `EntitlementRepository` — same VIP allowlist
  (Derek + Mike + Amanda) and `V0_8_RELEASE_DATE_MS` cutoff as
  Android, so the two platforms grandfather the same cohort.
  Sticky-flag fix included: the legacy flag recomputes per-uid so a
  non-VIP signing in on a VIP's device doesn't inherit Premium.
- `UserPreferencesRepository` gains 3 local-only entitlement keys
  (`legacy_user_granted`, `legacy_check_done_for_uid`,
  `cached_entitlement`), explicitly excluded from
  `UserPreferencesSnapshot` per Apple / Google IAP policy.
- UI gates: `HouseholdView` gates Generate Invite; `DataSettingsSection`
  gates Export buttons (Import stays free); new
  `UpgradeToPremiumCard` SwiftUI Section in `SettingsView` between
  ForceSyncSection and Statistics, visible only when not entitled.
- 11 new v0.8 keys × 4 locales in `Localizable.xcstrings`. iOS uses
  `%@` format specifier vs Android's `%1$s` for the App-Store-
  localized price.

**iOS versions:**
- `MARKETING_VERSION`: 0.6.10 → **0.8.0** (skipping 0.7.x —
  never reached TestFlight at those versions).
- `CURRENT_PROJECT_VERSION`: 24 → **40** (major-feature jump).

**Open items for iOS ship:**
- **App Store Connect**: create `premium_lifetime` in-app product
  ($7.99 USD, one-time, non-consumable). Same product ID as Android
  by convention, but Apple + Google entitlements are independent —
  a user buying on Android does NOT get iOS for free.
- **TestFlight push** + 2-device manual smoke test for the
  Mike + Amanda household flow on iOS.

### Versions

- Android: `versionCode 54 → 62`, `versionName 0.7.1 → 0.8.0`.
  Sequence: 60 (base v0.8.0) → 61 (.1 VIP allowlist) → 62 (.2
  Play re-upload). Two mid-cycle bumps for the patches above —
  Play Console doesn't accept re-uploads at the same code.
- iOS: `MARKETING_VERSION 0.6.10 → 0.8.0` (skips 0.7.x — never
  shipped to TestFlight), `CURRENT_PROJECT_VERSION 24 → 40`.

## [0.7.1] - 2026-05-11

**Lossless sideload-APK → Play Store transition (Android).** Closes
the two local-only data surfaces that would otherwise vanish when a
user uninstalls a sideloaded APK and reinstalls from Play Closed
Testing (signing certs differ → in-place update refused →
uninstall mandatory). The load-bearing case is Mike's beta cycle —
he's been running upload-key-signed APKs since v0.3.x and is now
ready to switch to the Play distribution.

### Added (Android)

- **Cloud-synced user preferences** at Firestore `/userPrefs/{uid}`.
  Theme (system/light/dark), locale tag (en/pt-PT/es/it/""),
  hide-checked-off toggle, and the Shop / Items sort modes now write
  through to Firestore on every change (500 ms debounce) and pull
  back on every auth-state-stream emission. Last-write-wins by
  `updatedAt`, same model as every other entity. The **load-bearing
  case**: cold-launching v0.7.1 captures the user's existing prefs
  to Firestore on the first auth tick — even if they never open
  Settings — so that the post-uninstall Play install can rehydrate
  them on Google sign-in. Adds `localeTag` to DataStore (was OS-only
  via per-app locale API, lost on cert-change uninstall).
- **Settings → Data → "Sync before uninstalling" card**. New section
  with a "Force sync now" button. Tapping it pushes every
  `pendingSync = 1` row to Firestore + the prefs doc (skipping the
  500 ms debounce) and shows "Safe to uninstall" when the queue
  drains. 30-second timeout; if rows are stuck the UX shows the
  remaining count + a retry button. Wired so Mike can verify cleanly
  before he uninstalls.

### Changed (Android)

- **SyncEngine** gains `observeAllPendingCount(householdId)` and
  `flushAllPending(householdId, uid)`. Each entity DAO gains
  `countPendingPush(householdId)`. `HouseholdMemberDao` gains
  `countPendingPush()` (no household scope — memberships are uid-
  scoped). These don't change the steady-state push path; the new
  count flows just expose what the existing push loop is already
  draining.

### Firestore security rules

- New `/userPrefs/{uid}` block — read + write only when
  `request.auth.uid == uid`. Mirrors the existing
  `/memberships/{uid}/...` pattern. **Deploy via Firebase Console
  before sharing the v0.7.1 APK** or pref writes silently
  `PERMISSION_DENIED` (the app stays functional — prefs just don't
  cloud-sync until rules deploy).

### Sideload → Play migration runbook

See [`docs/v0.7.1-migration.md`](docs/v0.7.1-migration.md) for the
end-to-end checklist. Summary:
1. User receives v0.7.1 sideloaded APK (still upload-key signed, so
   in-place updates over v0.7.0 — no uninstall yet).
2. User opens v0.7.1. Prefs auto-push to cloud on the first auth
   tick.
3. User opens Settings → Data → "Force sync now". Waits for
   "Safe to uninstall."
4. User uninstalls the sideloaded build.
5. User installs from Play Closed Testing (app-signing-key signed).
6. User signs in with the same Google account. Cloud pull
   rehydrates items + photos + memberships + **prefs**. Identical
   UI state to step 3.

### Tests

- Android: 8 new cases on `UserPreferencesSyncTest` pinning every
  reconcile branch (cloud-absent push, cloud-older push, cloud-newer
  pull, equal-no-op, blank-uid guard, error-swallow). 6 new cases
  on `UserPreferencesRepositoryTest` covering localeTag round-trip,
  the updatedAt-on-every-setter contract, the aggregated snapshot
  Flow, and `applyRemoteSnapshot` preserving the cloud's updatedAt
  (load-bearing for LWW).

### iOS (deferred to v0.7.2 or a future session)

The iOS port carries the v0.7.0 multi-user Phase 5 code on `main` but
the v0.7.1 cloud-prefs + Force-sync work hasn't been mirrored yet —
that's a separate Mac-side session. iOS stays at marketing version
0.6.10 until both ship together.

### Fixed (mid-cycle patches before final ship)

- **`.1` — Play Console BILLING declaration.** Added
  `<uses-permission android:name="com.android.vending.BILLING"/>`
  so Play Console's in-app products UI unlocks for the upcoming
  paid → free + Premium IAP transition.
- **`.2` — Play Billing Library bundled.** Play Console 2024+
  rejects uploads that declare the permission without bundling
  the library ("must update to at least version 6.0.1"). Added
  `com.android.billingclient:billing-ktx:7.1.1` as a runtime dep
  and dropped the standalone manifest permission (the library's
  own manifest auto-merges it). BillingClient is on the classpath
  but not yet wired in code; entitlement check + UI gate lands
  in v0.8.
- **`.3` — Membership push wired in SyncEngine.** v0.7.0 created
  the personal-household membership row locally with
  `pendingSync = 1` but never had a push job. The single-user case
  worked anyway because firestore.rules has a
  `request.auth.uid == householdId` fallback that bypasses needing
  the cloud membership doc. v0.7.1's Force-sync count includes the
  stuck row, so the gap surfaced as "1 item(s) couldn't sync" —
  the queue could never reach zero. Added a sixth push job inside
  `launchPushJobsFor` watching `householdMemberDao.observePendingPush()`
  and writing each row to `/memberships/{uid}/households/{hid}` as
  a Map payload (no DTO class — the shape is small and mirrors the
  inline write in `HouseholdRepositoryImpl.leaveHousehold`).
  Confirmed via a real Force-sync attempt on the dev account
  after the patch landed.

### Versions

- Android: `versionCode 50 → 54`, `versionName 0.7.0 → 0.7.1`.
  (Three mid-cycle versionCode bumps for the patches above — Play
  Console doesn't accept re-uploads at the same code.)
- iOS: unchanged (still 0.6.10 marketing).

## [0.7.0] - 2026-05-11

**Multi-user account sharing — household model (Android).** Mike asked
for it in v0.6.0 planning: *"allowing multiple people to access one
account is probably a good one. I could see allowing Amanda to access
my list and add items and check off items."* This release lands the
full data + sync + invite + UI stack to support that flow on Android.
The iOS port carries the matching Phase 5 code on `main` (schema v8,
HouseholdRepository, HouseholdView, household-scoped DAOs, parity
unit tests) but is still tagged at v0.6.10 marketing version pending a
Mac-side `xcodebuild` + 2-device smoke test before the version bump
ships to TestFlight. The Android 0.7.0 client + the iOS 0.6.10 build
+ the iOS Phase 5 main branch all read/write the same Firestore
project at the same paths without breaking each other.

### Added (Android)

- **Household abstraction**: every entity (items, stores, categories,
  item_store_xref, store_category_order, purchase_records) gains a
  `householdId` column. Single-member households use
  `householdId == userId` so v0.6.x behaviour is preserved
  byte-for-byte. The schema-v7→v8 migration backfills existing rows
  in place and re-flags `pendingSync = 1` so the household scope syncs
  to Firestore on the next push. New `household_members` table mirrors
  Firestore's `/memberships/{uid}/households/{hid}` collection.
- **Settings → Household screen**. Member list + Generate invite code
  (8-char Crockford base32, 24h TTL, single-use) + Join with code +
  Leave household (destructive, with confirmation). English strings
  only this release; other locales follow.
- **Invite flow**. `HouseholdRepository.generateInvite()` writes
  `/invites/{token}` with `{householdId, createdBy, createdAt,
  expiresAt, accepted}` fields. `acceptInvite(token)` validates +
  stamps + wipes local rows + inserts new membership + triggers a
  full pull from the shared household's path. Typed failures
  (`NotFound`, `Expired`, `AlreadyUsed`) render precise inline errors
  in the join form.
- **First-launch bootstrap**: `FirebaseAuthSessionProvider` resolves a
  user's active household alongside the uid on every auth change,
  publishing both ids atomically so observing repos never see a
  mismatched pair. Cold-launch short-circuit still skips the
  Firestore peek when pullState is SUCCEEDED + uid unchanged.

### Changed (Android)

- **Repository queries switch from `userId`-scoping to `householdId`-
  scoping** across all 8 DAOs. `userId` remains as creator/audit
  metadata copied from the parent row at insert time; `householdId`
  is the new access scope. Cross-cascade methods (xref soft-delete,
  SCO cascade, purchase-record cascade) all use parent's
  `householdId`. The single deliberate exception is the Statistics
  queries on `PurchaseRecordDao` — those stay scoped by purchaser
  `userId` so Mike sees what HE bought, not Mike + Amanda combined
  (per the v0.7.0 design call).
- **Sync DTOs** add a `householdId` field; the inverse mapper falls
  back to `userId` when the field is absent (legacy v0.6.x docs).
  Firestore wire path stays `/users/{X}/.../{doc}` — X is now
  `householdId`, but the segment name is preserved so existing users'
  cloud data persists at the same path after upgrade. The
  Firestore security rules (see `firestore.rules` at the repo root —
  deploy via the Firebase Console BEFORE 0.7.0 reaches Play Closed
  Testing) scope access by the new `householdId` field inside each
  document rather than path semantics.
- **Cross-store cascade extends to households** — when Amanda marks
  Milk purchased at Aldi, Mike's "Milk needed at Lidl" entry drops
  too. This is the explicit design intent of sharing; document
  prominently in case users find it surprising.
- **Concurrent edits** stay last-write-wins via `updatedAt`. Per-field
  merging deferred to v0.7.x.
- **Settings → About** loses the "Source code on GitHub" link. The
  privacy policy link stays as the only outbound link. Same change
  applied to iOS so the two ports' About sections stay parallel.

### Downgrade safety (important)

**v0.7.0 → v0.6.9 reverts must uninstall + reinstall, not sideload
on top.** The schema migration v7 → v8 leaves the local DB at a
version v0.6.9's Room doesn't recognise — sideloading the v0.6.9
APK over a v0.7.0 install crashes on every launch
(`IllegalStateException` from Room). The safe revert path is:

  1. Tap "Uninstall" on the device's app info screen (Android wipes
     the local DB).
  2. Install v0.6.9 fresh.
  3. Sign in with the same Google account; Firestore re-populates
     every row. v0.6.9 silently ignores the new `householdId` field
     in cloud docs.
  4. Cost: any `pendingSync = 1` rows that never reached Firestore
     before the uninstall are lost. Anything cloud-side is fine.

For future downgrades (v0.7.x → v0.7.0 etc.), v0.7.0's Room builder
now calls `fallbackToDestructiveMigrationOnDowngrade()` so the
fallback path is automatic — local DB wipes + re-pulls instead of
crashing. The flag only helps once v0.7.0 itself is in users' hands;
the v0.6.9 revert above is the one path it can't retroactively fix.

### iOS port (Phase 5)

- Every Android v0.7.0 surface has a mirrored iOS counterpart on
  `main`: schema migration `v8_household_scope`, HouseholdMember
  entity + DAO, household-scoped DAO queries (10 DAOs), join-aware
  `(uid, householdId)` SyncEngine + `pullForHousehold` rename on
  PullCoordinator, Sync DTOs with householdId, FirestoreHouseholdRepository
  (invite generate / accept / leave), HouseholdView + ViewModel, and
  the same 8-char Crockford base32 / 24h TTL invite contract.
- Test parity matches Android one-for-one as of this release:
  HouseholdViewModelTests, HouseholdRepositoryTests (token spec),
  MigrationTests v7→v8 backfill cases, SettingsViewModelTests
  retryPull → pullForHousehold assertion. Wider FirestoreHouseholdRepository
  flows still rely on Android's repo-impl tests + the 2-device smoke
  test until iOS gains a Firebase-emulator harness.
- iOS marketing version stays at 0.6.10 in this release. Bumping iOS
  to 0.7.0 + TestFlight push is gated on a Mac-side `xcodebuild test`
  + 2-device manual smoke run.

### Deferred to v0.7.x

- Real-time `addSnapshotListener` updates (still one-shot pull).
- Member roles (everyone in a household is equal access-wise).
- Multiple households per user.
- Per-list / per-store ACLs (groceries shared, hobby private).
- Activity log ("Amanda added Bread").
- Email / deep-link invites.
- Cloud-side membership lookup on second-device sign-in (today the
  second device needs to be invited like a new member).
- iOS Firebase-emulator unit-test harness so FirestoreHouseholdRepository's
  invite/accept/leave flows can be unit-tested without a real Firestore
  round-trip.

## [0.6.10] - 2026-05-11

iOS-only parity catch-up: closes three long-standing gaps where the
iOS port lagged behind Android. **Android: unchanged** (no version
bump, no behaviour change — the Android code shipped these features
in v0.5.6 and v0.6.8 already).

### Added (iOS)

- **StorePicker "best-store-covering-most-criticals" banner uplift**
  (Android v0.5.6 parity). The cross-store critical-needs banner used
  to show a flat comma-list of critical item names when expanded. Now
  computes a routing-aware `CriticalBannerState` (total count + the
  store covering the most criticals + per-store breakdown) and
  surfaces it as: collapsed shows "N critical items needed / Most at
  <store> (<count>)"; expanded shows a per-store breakdown with each
  store's critical names underneath its own header. Single-store case
  drops the "Most at" line (no routing decision to make).
  `StorePickerViewModel` adds `criticalBannerState: CriticalBannerState?`
  in place of the old `criticalAcrossStores: [String]`.

- **Empty-state illustrations on Items / Shop-at-Store / Store Picker**
  (Android v0.6.8 parity). Shared
  [`ui/util/EmptyState.swift`](ios/Storehop/UI/Util/EmptyState.swift)
  view: large SF Symbol + title + body (mirrors the Compose Composable
  in `ui/util/EmptyState.kt`). Items uses `tray` (no-query) /
  `magnifyingglass` (search miss); Shop uses `cart` / `magnifyingglass`;
  Store Picker uses `storefront` (previously no empty state at all —
  the SwiftUI List just rendered blank). Strings added in all four
  locales (en, pt-PT, es, it).

### Changed (iOS)

- **Dark-theme contrast tweak** (Android v0.6.8 parity). The
  `Text/OnSurfaceVariant` and `Brand/Secondary` Color Sets in
  `Assets.xcassets` had light-mode value `#6F6A60`, giving 4.20:1
  contrast on `Surface/SurfaceVariant` (`#EFECE6`) — fails WCAG AA
  for normal text. Darkened to `#5E594F` (5.30:1, AA-compliant). Dark
  variant unchanged (already 7.80:1).

### iOS test parity (still pending)

- StorePickerViewModelTests doesn't exist on iOS (only Android has
  the `CriticalSummary*` test suite). The iOS implementation mirrors
  Android line-for-line and was hand-verified against the
  `StorePickerViewModelTest` matrix; a Mac-side test pass is the
  follow-up.

### Versions

- iOS: MARKETING_VERSION 0.6.9 → 0.6.10, CURRENT_PROJECT_VERSION 23 → 24.
- Android: unchanged (versionCode 45, versionName 0.6.9).

## [0.6.9] - 2026-05-11

Mike-reported follow-up to v0.6.7: the Store Picker was reporting
"10 critical items needed" with 5 per store, but tapping into a store
showed only 1. The in-store screen was correct; the picker was
over-counting.

### Fixed

- **Store Picker no longer treats marked-purchased staples as
  critical.** Root cause: v0.6.7's "Bug B" fix extended the picker's
  source query to include staples, then changed the repo partition to
  treat `isStaple && !purchasedThisSession` rows as "needed" for
  chip/banner purposes. The intent was "a priority staple the user
  checked off last week is still on the list, just struck-through
  in-store — the picker badge should also surface it." Mike's
  evidence contradicts that mental model: when he marks a staple
  purchased, he expects it to drop off the picker even if it's a
  staple. Reverted the partition to `isNeeded` only, dropped the
  staple OR clause from `observeStorePickerItems`, and removed the
  `isStaple` + `purchasedThisSession` fields from `StorePickerItemRow`.
  The in-store view still surfaces struck-through staples (the
  `isStaple` OR clause in `shoppingListForStore` is unchanged) — that
  was always the correct UX. The picker badge + banner are now
  strictly the count of explicitly-needed items.

  v0.6.7's other fix ("Bug A": Shop-at-Store banner filters by
  `isPriority && isNeeded`) is unchanged and still correct — it's
  what produces the right "1 critical item" count Mike sees in-store.

### Tests

- `ShoppingRepositoryImplTest`:
  - Inverted: `observeStorePickerRows excludes priority staple bought
    prior session from criticals` (was previously the opposite
    assertion under v0.6.7). Now pins Mike's mental model: a
    marked-purchased staple is gone from the picker badge regardless
    of session timing.
  - Kept: `observeStorePickerRows excludes priority staple bought
    this session from criticals` (also correct under the new partition).

Total: 443 unit tests, 0 failures.

### Re-classified

- TODO(0.6) in `ShoppingDao.kt` about
  `renewStaplesForNewSession` is now flagged as an opt-in toggle
  rather than a default — Mike's evidence implies auto-renewal would
  surprise users ("I marked it purchased, why is it back?"). The
  toggle, if implemented, would live in Settings → Display or
  Settings → Data.

### Versions

- Android: versionCode 44 → 45, versionName 0.6.8 → 0.6.9.
- iOS: MARKETING_VERSION 0.6.8 → 0.6.9, CURRENT_PROJECT_VERSION 22 → 23.

## Tests-only — 2026-05-11

New `:benchmark` module for Macrobenchmark cold-start + scroll-FPS
measurement. No version bump (no app behaviour change).

### Added

- **`:benchmark` Gradle module** (`com.android.test` plugin). Targets
  the `:app` module's new `benchmark` build type (release-like:
  R8 minify + resource shrinking on, but `isProfileable = true` so
  Macrobenchmark can attach Perfetto traces). Two test classes:
  - `StartupBenchmark`: cold + warm startup with
    `CompilationMode.None` and `CompilationMode.Partial` variants.
    First-launch DatabaseSeeder path is implicitly measured by cold
    runs; subsequent cold runs reflect what 99% of users see.
  - `ScrollBenchmark`: drives the Items list under UiAutomator,
    flings 3× down + 3× up, reports `FrameTimingMetric` (median +
    95th-percentile frame durations in ms). 60fps target = 16.6ms
    p95.
- **New `benchmark` build type on `:app`** that mirrors release
  (minify + shrink) but with `isProfileable = true`. Not shipped to
  Play. Required for Macrobenchmark to read traces.
- **libs.versions.toml**: `androidx.benchmark:benchmark-macro-junit4`
  1.3.3, `androidx.test.uiautomator:uiautomator` 2.3.0, and the
  `com.android.test` plugin alias.

### How to run

```
./gradlew :benchmark:connectedBenchmarkAndroidTest
```

on a connected physical device (emulators give noisy results — Pixel
6+ recommended). Results land under
`benchmark/build/outputs/connected_android_test_additional_output/`.

### Not in CI

Macrobenchmark needs a real device or a properly-configured AVD. GitHub
Actions ubuntu runners don't have either; opt-in locally pre-release.
Initial baseline numbers will land in CHANGELOG once we run on a Pixel.

## Tests-only — 2026-05-11

Android CI workflow + a small defensive-cast tweak in `StorehopTheme`.
No version bump (no user-visible changes).

### Added

- **`.github/workflows/android-ci.yml`** — mirrors the existing iOS CI
  pattern. Runs on push (branch wildcard, paths-filtered to `app/`,
  `gradle/`, build files, and the workflow itself) and PR-to-main.
  Sets up JDK 17 + the Gradle cache, then runs
  `./gradlew :app:testDebugUnitTest :app:lintDebug`. Uploads unit-test
  + lint reports as artifacts on every run (retained 7 days). 30-min
  timeout. iOS already had its own workflow; Android catches up.

### Changed

- **`StorehopTheme` cast is now defensive.** Previously
  `(view.context as Activity).window` would throw `ClassCastException`
  if the Compose tree was ever hosted under a non-Activity context
  (Paparazzi screenshot renderers, Compose-for-Web, etc.). The
  `view.isInEditMode` guard didn't fire in those cases. Now uses
  `as? Activity` and skips the SideEffect when the cast is null —
  same runtime behaviour on a real device, but the theme no longer
  crashes headless render targets.

### Out of scope (attempted, deferred)

- **Paparazzi screenshot tests** — attempted, then reverted. Paparazzi
  1.3.5's test listener calls
  `TestResultsProvider.hasOutput(Long, TestOutputEvent.Destination)`,
  a Gradle internal API removed in Gradle 9.x. Applying the Paparazzi
  plugin breaks the entire `testDebugUnitTest` task regardless of
  whether any screenshot tests are present. Versions 1.3.6 / 2.0.0
  aren't published. Wait for a Gradle-9-compatible Paparazzi release,
  or evaluate Roborazzi (uses the standard Robolectric test runner
  and doesn't hook the test listener).

## [0.6.8] - 2026-05-11

UX polish bundle: empty-state illustrations, a dark-theme contrast fix,
and a Settings reorg with section headers + a new About section.

### Added

- **Empty-state illustrations** (Android). Items, Shop-at-Store, and
  Store Picker tabs now render a large Material Symbols icon + a title
  + a body line when there's nothing to show, instead of a single flat
  caption. Items uses `Inventory2` (no-query) / `SearchOff` (search
  miss); Shop uses `ShoppingCart` (no-query) / `SearchOff` (search
  miss); Store Picker uses `Store` (previously no empty state at all —
  blank list). Strings split into title + body keys in all four
  locales (`*_empty_no_query_title` / `_body`, `*_empty_search_title`
  / `_body`, plus `storepicker_empty_title` / `_body`). Shared
  `EmptyState(icon, title, body)` Composable lives in
  `ui/util/EmptyState.kt` so future surfaces can pull the same look.

- **Settings → About** (Android + iOS). New section at the bottom of
  Settings with the app version (`VERSION_NAME` + `VERSION_CODE`),
  a Privacy policy link
  (`derekgallardo01.github.io/storehop/privacy-policy`), and a Source
  code link (`github.com/derekgallardo01/storehop`). Links open in the
  user's default browser. Surfaces metadata that previously required
  digging through Play Store or the README.

### Changed

- **Settings: grouped sections** (Android). The previously-flat list
  now uses section headers (`Account` / `Display` / `Data` / `About`)
  with the Statistics deep-link floated above as a featured tile.
  iOS already used native Section headers (Form pattern); the iOS
  port gains the new About section at the bottom to mirror Android.

- **Dark-theme contrast tweak** (Android, both light + dark
  schemes). `WarmGrayL` (used by `secondary` and `onSurfaceVariant`)
  darkened from `#6F6A60` to `#5E594F`. The old value was 4.20:1 on
  `surfaceVariant` (`#EFECE6`), failing WCAG AA (4.5:1) for normal
  text. The new value is 5.30:1, comfortably above AA. Dark scheme
  was already 7.80:1 — no change needed there. Audit found no other
  failures: every other Light/Dark token pair was already ≥6:1.

### Tests

- Android: 443 unit tests, 0 failures (no test-count delta — the
  changes are UI-layer only; existing tests cover the ViewModels +
  data layer unchanged).

### Versions

- Android: versionCode 43 → 44, versionName 0.6.7 → 0.6.8.
- iOS: MARKETING_VERSION 0.6.7 → 0.6.8, CURRENT_PROJECT_VERSION 21 → 22.

## [0.6.7] - 2026-05-11

Mike-reported: *"Something is amiss re the display of critical items in
the 'where are you shopping?' and store listing pages. Some items are
not showing. Some items are showing when they shouldn't."* Two
intersecting bugs — fixed together.

### Fixed

- **Shop-at-Store banner no longer flags purchased priority items as
  critical** (Android). The in-store critical-items banner was sourcing
  its names from the unfiltered `allRows` filtered only by `isPriority`.
  `allRows` includes items still visible because of the session-window
  OR clause (priority items checked off this session) and the staple OR
  clause (priority staples carried over from prior trips), so a row
  could be struck-through in the list below while the banner still
  counted it as critical. Now filters by `isPriority && isNeeded`:
  "critical" means "still unbought." iOS already had this filter; no
  iOS change needed for Bug A.

- **Store Picker chip + banner now surface priority staples carried
  over from prior sessions** (Android + iOS). The picker's source query
  (`observeStorePickerItems`) only returned rows where `isNeeded=1` or
  the xref was purchased within the active session — missing the
  staple OR clause that `shoppingListForStore` has. A priority staple
  the user checked off last week (`isNeeded=0`, `isStaple=1`,
  `lastPurchasedAt` outside session) was silently dropped from the
  picker's badge even though the in-store list still shows it. Fixed:
  extended the SQL WHERE to mirror `shoppingListForStore`'s OR-clause
  shape, added a SQL-side `purchasedThisSession` flag (CASE on
  `lastPurchasedAt >= sessionStartMs`), and the repo partition now
  treats a row as "still on the list" when `isNeeded=1` OR
  (`isStaple=1` AND not bought this session). So a priority staple
  unbought-this-session counts as critical; bought-this-session moves
  to the picked-up bucket.

iOS port mirrors the SQL + repo partition change line-for-line. The
DAO test that inlined the picker SQL was updated to match.

### Not in scope (deferred)

The deeper fix is to *auto-renew* priority staples at session start
(flip `isNeeded=1` on every priority staple whose `lastPurchasedAt`
predates the session), per the TODO(0.6) at
[`ShoppingDao.kt:30-41`](app/src/main/java/com/storehop/app/data/dao/ShoppingDao.kt#L30-L41).
That would make the in-store row also re-appear as "needs buying" this
trip rather than as "purchased / struck-through". Tracked as a v0.7+
roadmap item per project memory; this release keeps the change surface
narrow to the display fix Mike reported.

### Tests

- Android: 440 → 443 unit tests, 0 failures.
  - `ShopAtStoreViewModelTest`: +1 pinning that priority items with
    `isNeeded=false` (session-window survivors + prior-session staples)
    are excluded from `criticalNames`.
  - `ShoppingRepositoryImplTest`: +2 pinning the new picker partition
    semantics (priority staple unbought-this-session is critical;
    bought-this-session is not).
- iOS: `ShoppingDaoTests.testStorePickerItemsReturnsOneRowPerStore`
  updated to use the new SQL shape + four-argument binding.

### Versions

- Android: versionCode 42 → 43, versionName 0.6.6 → 0.6.7.
- iOS: MARKETING_VERSION 0.6.4 → 0.6.7 (catches up the version skip
  from v0.6.5 / v0.6.6 which were Android-only), CURRENT_PROJECT_VERSION
  20 → 21.

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
