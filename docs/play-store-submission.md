# Play Store submission — copy + form answers

Fill-in answers for Play Console's app setup forms, plus the store listing copy you can paste into the listing.

---

## 1. Privacy policy URL

Host `docs/privacy-policy.md` somewhere public. Cheapest path:

1. Push the docs/ directory to your public GitHub repo (already there if `docs/` is committed).
2. GitHub repo → **Settings → Pages → Source**: "Deploy from a branch" → **main / /docs**.
3. After ~1 minute, the policy is live at:
   ```
   https://derekgallardo01.github.io/storehop/privacy-policy
   ```
4. Paste that URL in Play Console → **Policy → App content → Privacy policy**.

Before publishing, edit `docs/privacy-policy.md` to put your real contact email in the Contact section (it's currently a placeholder).

---

## 2. Data safety form

Play Console → **App content → Data safety**. Walk through the wizard with these answers.

### Does your app collect or share any of the required user data types?
**Yes.**

### Is all of the user data collected by your app encrypted in transit?
**Yes.** Firebase uses HTTPS/TLS for all data sent to its services.

### Do you provide a way for users to request that their data is deleted?
**Yes.** They can email the contact address in the privacy policy. (You can mark this honestly even though there's no in-app button yet — the manual email path counts.)

### Data types collected

For each row, indicate **Collected**, **Shared** (with third parties beyond Firebase), and **Optional/Required**.

| Category | Type | Collected? | Shared? | Optional? | Why |
|---|---|---|---|---|---|
| **Personal info** | Name | Yes (only if user signs in with Google) | No | Optional | Display name shown in Settings |
| **Personal info** | Email address | Yes (only if user signs in with Google) | No | Optional | Identifies the user's cloud-synced account |
| **Personal info** | User IDs | Yes | No | Required | Firebase Authentication assigns an anonymous UID; required for the app to store data per-user |
| **Photos and videos** | Photos | Yes (only if user attaches a photo to an item) | No | Optional | Stored at `users/{uid}/items/{itemId}.jpg`, displayed only to that user |
| **App activity** | App interactions | **No** | — | — | We do not collect interaction telemetry |
| **App info and performance** | Crash logs | **No** | — | — | We do not run Firebase Crashlytics |
| **App info and performance** | Diagnostics | **No** | — | — | No diagnostics collection |
| **Device or other IDs** | Device or other IDs | **No** | — | — | The Firebase auth UID is account-scoped, not device-scoped; no advertising ID |

### Purposes for each data type
- **App functionality** (everything: it's all used to run the app)
- **Account management** (only for: Email, Name, User IDs)

Do **not** check: Analytics, Developer communications, Advertising or marketing, Fraud prevention, Personalization.

### Sharing
**No data is shared with third parties.** Firebase counts as a "service provider" and Play's wizard treats it as not-sharing for purposes of the form.

---

## 3. Content rating questionnaire

Play Console → **App content → Content rating**. Storehop is unambiguously rated for everyone. Walk through:

- **Category**: Reference, News, or Education → **Other** (utility / lifestyle / productivity is fine)
- **Violence**: No
- **Sexual content**: No
- **Profanity**: No
- **Controlled substances**: No
- **Gambling**: No
- **User-generated content**: **Yes** (users add their own item names, store names, photos) — but it's **not shared with other users** (single-user app), so this won't trigger an interactive flag
- **Personal info collected and shared with third parties**: No
- **Location sharing**: No
- **Digital purchases**: No

Expected outcome: **Everyone** (or **PEGI 3** in EU).

---

## 4. Target audience and content

Play Console → **App content → Target audience and content**.

- **Target age groups**: 13–17, 18+ (do NOT include under-13 — that triggers Designed for Families requirements which we don't want)
- **Does your app appeal to children?**: No
- **Account creation**: Users can use the app without an account (anonymous mode); optionally sign in with Google.

---

## 5. Ads

Play Console → **App content → Ads**.

- **Does your app contain ads?**: **No**.

---

## 6. App access

Play Console → **App content → App access**.

- **All functionality is available without restrictions**: **Yes**. (The Google Sign-In is optional, not gated.)

---

## 7. Store listing copy

Play Console → **Grow → Store presence → Main store listing**.

### App name
**StoreHop**

### Short description (≤ 80 chars)
```
Per-store shopping lists, cross-device sync, photos. No ads.
```

### Full description (paste verbatim)
```
StoreHop is a calm, focused shopping list for people who shop at more than one store.

Each store gets its own list, organized by aisle. Tag an item to "Lidl + Aldi" once and it shows up at both — but checking it off at Lidl only checks it off at Lidl. Aldi's list is untouched, because each (item, store) pair is its own state.

What's inside:
- Per-store shopping lists with aisle-aware sorting
- Drag and drop to reorder stores by your weekly route
- Critical-item flag for things you can't afford to forget (milk, eggs, paper towels). Surfaces as a banner above the list and a side-stripe on the row.
- "Always on the list" flag for staples — they stay visible struck-through after purchase, ready to un-check the next time you run out
- Photos for items so you can spot the right brand on the shelf
- Quick-add: type a name at the bottom of the store's list, item is created and tagged in one tap
- Undo for every destructive action (delete a store, mark an item purchased, delete an item — 5 seconds to take it back)
- Anonymous-first: install and start shopping immediately. Optional sign in with Google later for cross-device sync and uninstall safety.
- Share your shopping list as plain text to WhatsApp, SMS, or anywhere else
- English and European Portuguese (Português de Portugal)
- Light and dark themes following system, or pick your own

What's not inside:
- No ads
- No sponsored stores
- No analytics tracking what you shop for
- No account required to use the app
- No subscription

StoreHop is private, single-user, and stays out of your way.
```

### App icon
Already in the project at `app/src/main/res/mipmap-*/ic_launcher.png`. Play extracts this from the AAB; no separate upload needed.

### Feature graphic (1024 × 500 PNG)
Required. Quick path: open Figma/Canva, paste the launcher icon on a sage background (`#A8B89D` ≈ your primary), put the text "StoreHop" + tagline next to it, export at 1024×500.

### Phone screenshots (at least 2, max 8)
Take from your emulator. Recommended 5:
1. Store Picker showing 3+ stores with mixed states (one with "✓ All set", one with "1 critical", one normal)
2. Shop-at-Store screen with a category section, an item with a side-stripe, the quick-add bar at the bottom
3. Edit Item form showing all the fields (name, brand, category, stores, both toggles, photo)
4. Settings screen showing Account / Theme / Language sections
5. Same Store Picker, in Português, dark theme

To screenshot from the emulator: Power button on the emulator side panel, or `Ctrl+S` in the emulator window.

### App category
**Lifestyle** (or **Productivity** — both fit; Lifestyle ranks better for shopping-adjacent searches).

### Tags (Play Console picks 5)
Suggest: shopping list, grocery, lists, todo, household.

---

## 8. Final pre-submission checklist

- [ ] Privacy policy hosted at a public URL (GitHub Pages → Settings → Pages from /docs)
- [ ] Replaced the placeholder email in `docs/privacy-policy.md` with a real one
- [ ] Data safety form filled in (above answers)
- [ ] Content rating completed
- [ ] Target audience: 13+
- [ ] Ads: No
- [ ] App access: All functionality available
- [ ] Store listing: name, short + full description, app icon (auto), feature graphic, ≥2 screenshots
- [ ] Internal testing: tester list created, your friend's Gmail added, opt-in URL sent to them

Once all green-checked: submit for review (production track) when you're ready to ship publicly. Internal testing can run independently in parallel — your friend doesn't need the review to complete.
