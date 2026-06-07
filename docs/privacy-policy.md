# StoreHop Privacy Policy

_Last updated: 2026-05-20_

This policy describes what data the StoreHop app ("**StoreHop**", "**we**", "**our**") collects, why, and how we handle it. StoreHop is a personal shopping-list app, available for Android (Google Play) and iOS (App Store), developed by Derek Gallardo as an independent developer.

## TL;DR

- StoreHop is single-user. Your shopping lists are private to you.
- We collect only what's needed to make the app work: an account ID, optional Google profile info if you sign in, and the items / stores / photos you add to your list.
- We don't sell your data. We don't run ads. We don't share data with third parties beyond Google's Firebase services that power sync.
- You can stop using the app at any time. If you want your cloud-synced data deleted, contact us.

## Data we collect

**Account identity**

- When you first open the app, Firebase Authentication assigns you an anonymous user ID (UID). This UID is local to your install and is the key under which your data is stored.
- If you choose to **Sign in with Google** in Settings, we additionally store your Google account's email address, display name, and profile photo URL. This is so the same account can be used to access your data on a different device.
- We do not access your Google contacts, calendar, drive, or any other Google service.

**Your shopping data**

- Items you add (name, brand, optional photo, category, the stores you tag the item to, "always on the list" and "critical" flags).
- Stores you add (name, color, display order).
- Per-(item, store) state: whether you still need an item at that store, when you last bought it.
- Purchase history records (which item, which store, when).
- Photos you attach to items, stored at `users/{your-uid}/items/{item-id}.jpg`.

**What we do _not_ collect**

- We do not use analytics. There is no Firebase Analytics, no Crashlytics, no third-party SDK telemetry.
- We do not collect your device's advertising ID.
- We do not collect location data.
- We do not collect contacts, calendar, or any other on-device data.
- We do not record what you type beyond saving the items you explicitly add.

## How we use your data

Strictly to run the app:

- Display your shopping lists per store.
- Sync your lists between your devices when you're signed in with Google.
- Show photos you've attached to items.
- Mark items as purchased and remember which (item, store) pairs are still needed.

We do not use your data for advertising, profiling, recommendations, or any analytics about you or other users.

## Where your data is stored

- Your data is stored in **Google Firebase** (Cloud Firestore + Firebase Storage), operated by Google LLC.
- Firebase encrypts data in transit (HTTPS / TLS) and at rest by default.
- Your data is stored under your user ID and is only accessible by clients authenticated as that same user (enforced via Firebase Security Rules).
- Locally, the app keeps a copy of your data on your device in a SQLite database (Room on Android, GRDB on iOS) so the app works offline.

## Third parties

The only third party we use is **Google Firebase** (Authentication, Firestore, Storage). Their privacy policy applies to the parts of your data Firebase processes: <https://firebase.google.com/support/privacy>.

We do not embed any other SDKs that send data off-device.

## Data retention

- Your data stays in your account until you delete it or request deletion.
- If you uninstall the app without first signing in with Google, your anonymous account becomes inaccessible and the data tied to it eventually ages out per Firebase's retention rules.
- If you've signed in with Google, your data persists in Firebase indefinitely until you request deletion.

## Your choices

- **Stop using the app**: just uninstall. Local data on the device is removed by the OS.
- **Sign out**: in Settings → Sign out. This drops you back to a fresh anonymous account on this device. Your cloud-synced data stays under your Google account and reappears when you sign in again.
- **Delete your cloud data**: the app does not yet expose a "delete my account" button in-app. Email us (see Contact below) and we'll remove your Firebase records on request, typically within 14 days.

## Children

StoreHop is not directed at children under 13 (or the equivalent minimum age in your jurisdiction). We do not knowingly collect data from children.

## Changes to this policy

If we make material changes to how data is collected or used, we'll update this page and bump the "Last updated" date at the top. Significant changes will be communicated in-app via a Settings notice.

## Contact

For privacy questions or data-deletion requests, contact:

**Email:** _your-email-here@example.com_  
_(StoreHop is maintained by Derek Gallardo as an independent developer. Replace this placeholder with your real contact email before publishing.)_

---

_StoreHop is open-source. Source code is available at <https://github.com/derekgallardo01/storehop>._
