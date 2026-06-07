# iOS App Store submission — step-by-step

The Apple-side companion to [`play-store-submission.md`](play-store-submission.md). Follow this in order; each step has a verification beat so you know it landed.

---

## 0. Prerequisites (one-time, before anything)

- [ ] **Apple Developer Program membership** ($99/year). Sign up at [developer.apple.com/programs/enroll/](https://developer.apple.com/programs/enroll/). Approval takes hours to days.
- [ ] **macOS 14+** and **Xcode 16+** installed (Xcode is free on the Mac App Store).
- [ ] You've successfully built + run the app on the simulator. From the `ios/` README:
  ```sh
  cd ios && xcodegen generate
  xcodebuild build -project Storehop.xcodeproj -scheme Storehop \
    -destination 'platform=iOS Simulator,name=iPhone 17' CODE_SIGNING_ALLOWED=NO
  ```

---

## 1. Real Firebase config

The app needs to talk to your production Firebase project (the same one Android uses).

1. Open the [Firebase Console](https://console.firebase.google.com/) → your Storehop project → ⚙ Settings → **Your apps**.
2. Click **Add app** → iOS icon. Bundle ID: `com.storehop.app` (matches `project.yml`'s `productBundleIdentifier`).
3. Download `GoogleService-Info.plist`.
4. Drop it into `ios/Storehop/Resources/`.
5. Re-run `xcodegen generate` so Xcode picks it up.

**Verify:** launch the app on the simulator (no `-UITestE2E` flag). The StorePicker should show the seeded `store_lidl`, `store_continente`, `store_pingo_doce`, `store_aldi` after Firestore's first pull (a few seconds).

> ⚠ Do not commit `GoogleService-Info.plist`. It's gitignored already (matches Android's `google-services.json` treatment).

---

## 2. Signing team

```yaml
# ios/project.yml
settings:
  base:
    DEVELOPMENT_TEAM: ABC123XYZ4   # ← your Apple Developer Team ID
```

Find your Team ID at [developer.apple.com/account](https://developer.apple.com/account) → **Membership details** → **Team ID** (10 chars, all caps + digits).

After editing, `cd ios && xcodegen generate`.

In Xcode → project → **Signing & Capabilities** tab → **Storehop** target → enable **Automatically manage signing**. Xcode will create + download a provisioning profile on first try.

**Verify:** Product → Archive (or `xcodebuild archive` from CLI) completes without a code-signing error.

---

## 3. App Store Connect app record

1. Sign in to [App Store Connect](https://appstoreconnect.apple.com/).
2. **My Apps** → **+** → **New App**.
3. Fill in:
   - **Platform**: iOS
   - **Name**: Storehop
   - **Primary Language**: English (U.S.)
   - **Bundle ID**: `com.storehop.app` (must match `project.yml` exactly)
   - **SKU**: any string you'll remember; `storehop-ios-001` works fine.
   - **User Access**: Full Access (you).
4. Click **Create**.

You now have a placeholder record. The app icon, screenshots, description, and pricing get filled in steps 7 and 8.

---

## 4. `premium_lifetime` in-app product

This is the one-time $7.99 unlock that gates household-invite creation + CSV export. Mirrors Android's Play Console product with the same ID (entitlements are platform-isolated; the matching ID is just naming convention).

1. App Store Connect → your Storehop app → **Monetization → In-App Purchases** → **+**.
2. Choose **Non-Consumable**.
3. **Reference Name**: `Storehop Premium`
4. **Product ID**: `premium_lifetime` (must match exactly — `StoreKitManager.swift` reads this).
5. **Price**: $7.99 USD (tier 8 in old terminology). Apple will auto-calculate local prices for every other market.
6. **Display Name (in user-visible languages you support)**:
   - English: `Storehop Premium`
   - Portuguese: `Storehop Premium`
   - Spanish: `Storehop Premium`
   - Italian: `Storehop Premium`
7. **Description**:
   - English: `One-time unlock for sharing your list with household members + exporting items / categories as CSV. Lifetime, no subscription.`
   - (Translate the three others if you want non-English localization; English fallback is OK)
8. **Review Information** (for Apple's review team):
   - **Screenshot**: a 640×920+ PNG of the upgrade card in-app. Capture from the simulator: open the app while signed in to a non-VIP Firebase account, scroll to Settings → "Storehop Premium" card, screenshot.
   - **Review Notes**: `Single non-consumable IAP. Unlocks two features: generating household invite codes (otherwise locked) and exporting items/categories as CSV. Inviter-pays model: invitees can join + use a shared household unconditionally.`

Click **Save**. Apple needs the product **and** at least one screenshot for it to become buyable in TestFlight builds.

> The IAP can sit in "Ready to Submit" / "Waiting for Review" indefinitely. It ships alongside the app binary at the same review.

---

## 5. Privacy manifest (`PrivacyInfo.xcprivacy`)

Apple requires this from 2024+. It declares which "required-reason APIs" your app uses.

Create `ios/Storehop/Resources/PrivacyInfo.xcprivacy`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>NSPrivacyTracking</key>
  <false/>
  <key>NSPrivacyCollectedDataTypes</key>
  <array>
    <dict>
      <key>NSPrivacyCollectedDataType</key>
      <string>NSPrivacyCollectedDataTypeEmailAddress</string>
      <key>NSPrivacyCollectedDataTypeLinked</key><true/>
      <key>NSPrivacyCollectedDataTypeTracking</key><false/>
      <key>NSPrivacyCollectedDataTypePurposes</key>
      <array><string>NSPrivacyCollectedDataTypePurposeAppFunctionality</string></array>
    </dict>
    <dict>
      <key>NSPrivacyCollectedDataType</key>
      <string>NSPrivacyCollectedDataTypeName</string>
      <key>NSPrivacyCollectedDataTypeLinked</key><true/>
      <key>NSPrivacyCollectedDataTypeTracking</key><false/>
      <key>NSPrivacyCollectedDataTypePurposes</key>
      <array><string>NSPrivacyCollectedDataTypePurposeAppFunctionality</string></array>
    </dict>
    <dict>
      <key>NSPrivacyCollectedDataType</key>
      <string>NSPrivacyCollectedDataTypeUserID</string>
      <key>NSPrivacyCollectedDataTypeLinked</key><true/>
      <key>NSPrivacyCollectedDataTypeTracking</key><false/>
      <key>NSPrivacyCollectedDataTypePurposes</key>
      <array><string>NSPrivacyCollectedDataTypePurposeAppFunctionality</string></array>
    </dict>
    <dict>
      <key>NSPrivacyCollectedDataType</key>
      <string>NSPrivacyCollectedDataTypePhotosorVideos</string>
      <key>NSPrivacyCollectedDataTypeLinked</key><true/>
      <key>NSPrivacyCollectedDataTypeTracking</key><false/>
      <key>NSPrivacyCollectedDataTypePurposes</key>
      <array><string>NSPrivacyCollectedDataTypePurposeAppFunctionality</string></array>
    </dict>
  </array>
  <key>NSPrivacyAccessedAPITypes</key>
  <array>
    <dict>
      <key>NSPrivacyAccessedAPIType</key>
      <string>NSPrivacyAccessedAPICategoryUserDefaults</string>
      <key>NSPrivacyAccessedAPITypeReasons</key>
      <array><string>CA92.1</string></array>
    </dict>
    <dict>
      <key>NSPrivacyAccessedAPIType</key>
      <string>NSPrivacyAccessedAPICategoryFileTimestamp</string>
      <key>NSPrivacyAccessedAPITypeReasons</key>
      <array><string>C617.1</string></array>
    </dict>
    <dict>
      <key>NSPrivacyAccessedAPIType</key>
      <string>NSPrivacyAccessedAPICategorySystemBootTime</string>
      <key>NSPrivacyAccessedAPITypeReasons</key>
      <array><string>35F9.1</string></array>
    </dict>
    <dict>
      <key>NSPrivacyAccessedAPIType</key>
      <string>NSPrivacyAccessedAPICategoryDiskSpace</string>
      <key>NSPrivacyAccessedAPITypeReasons</key>
      <array><string>E174.1</string></array>
    </dict>
  </array>
</dict>
</plist>
```

The four `NSPrivacyAccessedAPITypes` reasons:
- `CA92.1` — UserDefaults for user prefs (theme, locale, sort)
- `C617.1` — File timestamp inspection (CSV import-export bookkeeping)
- `35F9.1` — System boot time (Firebase telemetry baseline)
- `E174.1` — Disk space queries (GRDB checkpointing)

Add it to `project.yml`'s `resources:` list if it doesn't auto-pick up:

```yaml
resources:
  - path: Storehop/Resources/PrivacyInfo.xcprivacy
```

Then `xcodegen generate` and rebuild. The archive validator will flag a missing manifest with a clear error if anything's wrong.

---

## 6. Build the archive

```sh
cd ios && xcodegen generate
xcodebuild archive \
  -project Storehop.xcodeproj \
  -scheme Storehop \
  -destination 'generic/platform=iOS' \
  -archivePath build/Storehop.xcarchive \
  -allowProvisioningUpdates
```

This produces `build/Storehop.xcarchive`. The `-allowProvisioningUpdates` flag lets Xcode generate the provisioning profile if Step 2's "Automatically manage signing" hasn't been triggered yet.

**Easier path (recommended for first submission):** open the project in Xcode, select **Any iOS Device** as the destination, and click **Product → Archive**. Xcode's Organizer window walks you through the rest of upload step by step.

---

## 7. Upload to TestFlight

### From the Organizer (GUI, recommended)

1. **Window → Organizer** in Xcode → **Archives** tab.
2. Select your archive → **Distribute App**.
3. **App Store Connect** → **Upload** → **Next** through the signing prompts.
4. Apple processes the build (~10–30 min). You'll get an email when it's ready.

### From the CLI (if you prefer)

```sh
xcodebuild -exportArchive \
  -archivePath build/Storehop.xcarchive \
  -exportOptionsPlist <(cat <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>method</key><string>app-store-connect</string>
  <key>teamID</key><string>ABC123XYZ4</string>
  <key>uploadSymbols</key><true/>
</dict>
</plist>
EOF
) \
  -exportPath build/export

xcrun altool --upload-app --type ios \
  --file build/export/Storehop.ipa \
  --apiKey YOUR_API_KEY_ID \
  --apiIssuer YOUR_ISSUER_ID
```

(API keys at App Store Connect → **Users and Access → Keys**.)

---

## 8. 2-device manual smoke (Mike + Amanda)

Before submitting for review, run the household-share flow on two real devices. This is the only end-to-end check the unit + UI test suite can't do (no real Firebase + multi-device).

1. App Store Connect → your app → **TestFlight** → **Internal Testing** → add yourself + Mike + Amanda as testers.
2. Wait for the email; install the TestFlight app + the Storehop beta on both devices.
3. On Mike's device: open the app, sign in (Google), Settings → Household → tap **Generate Invite**. Copy the 8-char code.
4. On Amanda's device: same app, sign in with a *different* Google account, Settings → Household → **Join Household** → paste the code.
5. Both devices should now show the same Lidl + Aldi + items, in real time.
6. On Mike: tap a needed item to check off. Within a few seconds, the same item should disappear from Amanda's list at the other store. That's the cross-store cascade across two users — the load-bearing invariant.
7. Spot-check CSV export from Settings → Data → Export Items (Mike has Premium since he bought the invite; Amanda's Export button stays locked with the price label).

If any of those steps fail, **do not submit for review.** Either re-test after a fix, or back out the v0.8.1 changes and ship v0.8.0 to Apple.

---

## 9. App Store Connect metadata

Back in App Store Connect → your app:

### App info

- **Subtitle**: `Multi-store shopping list`
- **Category**: Lifestyle (primary). Productivity is a fine secondary.
- **Content Rights**: You own all the rights.

### Pricing & availability

- **Price**: Free (the app itself is free; the IAP is a separate purchase).
- **Availability**: All countries unless you have a reason to restrict.

### Privacy

Click **App Privacy** → **Get Started** and answer the wizard. Use the same answers as Android's [`play-store-submission.md` Data Safety section](play-store-submission.md), translated to Apple's nomenclature:

| Data type | Collected? | Linked to user? | Used for tracking? | Purpose |
|---|---|---|---|---|
| Email Address | Yes (Google sign-in only) | Yes | No | App functionality, account management |
| Name | Yes (Google sign-in only) | Yes | No | App functionality, account management |
| User ID | Yes (Firebase UID) | Yes | No | App functionality |
| Photos | Yes (when user attaches one) | Yes | No | App functionality |
| Crash logs / Diagnostics / Advertising / Analytics | **No** | — | — | — |

**Privacy Policy URL**: same one as Play Store. If you haven't deployed `docs/privacy-policy.md` via GitHub Pages yet, [follow the Play Store doc's section 1](play-store-submission.md#1-privacy-policy-url). The Apple-side URL is identical.

### Version-specific (1.0 or whatever your `MARKETING_VERSION` is)

- **What's New in This Version**: copy the CHANGELOG entry for this version.
- **Description**: paste from [Play Store listing copy](play-store-submission.md), trim to fit Apple's limit if needed.
- **Keywords** (100 chars): `shopping list, grocery, multi-store, household, aisle, sage, cross-store`
- **Support URL**: your GitHub repo's Issues page works.
- **Marketing URL**: optional; can leave blank.
- **Screenshots**: 6.7" required (iPhone 17 Pro Max). 6.1" recommended (iPhone 17). Use [`DesignSystemTourTest`](../ios/StorehopUITests/DesignSystemTourTest.swift) to generate the 8 light-mode screens, pick the best 3–10. Apple requires at minimum 3 screenshots per device size.
- **App Icon**: already in the binary — Apple pulls it automatically from `AppIcon-1024.png`.

---

## 10. Submit for review

App Store Connect → your app → **App Store** tab → version → scroll to **Build** → select the TestFlight build you uploaded → **Save** → **Add for Review** → **Submit for Review**.

Apple's review queue is typically 24–48 hours. You'll be notified by email.

### Common rejection reasons + how to avoid them

| Rejection | Pre-flight check |
|---|---|
| **Privacy manifest missing** | Step 5 must be done. |
| **In-app purchase not approved** | Step 4's screenshot + description; IAP and binary submit together. |
| **App crashes on launch** | TestFlight install on a fresh device. Make sure Firebase + signing are right. |
| **Sign-in doesn't work for our reviewer** | They use a fresh Apple ID; anonymous-first onboarding handles this. But if they pick the Google sign-in flow, the OAuth consent screen needs to be public-accessible (Firebase Console → Authentication → Sign-in method → Google → enable for production). |
| **Demo account needed** | Provide one in **App Information → Sign-in required → Provide demo account**. Easiest: a Google account dedicated to this purpose with Mike-style seeded data. |
| **Functionality issues with IAP** | Test sandbox purchases beforehand. Apple's sandbox tester accounts: App Store Connect → **Users and Access → Sandbox Testers** → +. |

---

## 11. After approval

- **Phased release** (recommended for v1.0): Apple auto-ramps the new version to 1%, 2%, 5%, 10%, 20%, 50%, 100% of users over 7 days. Toggle this in App Store Connect → **Version → Phased Release**.
- **Watch crash reports** (Apple's own, no Firebase Crashlytics needed): App Store Connect → **Analytics**.
- **TestFlight stays available** for future beta builds without going through review again.

---

## Quick checklist

- [ ] Apple Developer Program enrollment paid + active
- [ ] Real `GoogleService-Info.plist` in `ios/Storehop/Resources/`
- [ ] `DEVELOPMENT_TEAM` filled in `project.yml`
- [ ] `PrivacyInfo.xcprivacy` shipped in the bundle
- [ ] App Store Connect app record created with bundle id `com.storehop.app`
- [ ] `premium_lifetime` IAP created + screenshot uploaded
- [ ] Archive built + uploaded to TestFlight
- [ ] 2-device manual household smoke passes
- [ ] App Store Connect metadata + screenshots + privacy answers complete
- [ ] Demo account provided for Apple's reviewer
- [ ] Submit for Review
