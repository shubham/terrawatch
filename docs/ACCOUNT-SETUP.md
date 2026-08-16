# TerraWatch — Account Setup Guide (Play Console · AdMob · RevenueCat · monetization.properties)

Written 2026-08-15. These are **your** actions — they involve payment, identity verification, and account creation. Do them in this order; AdMob can run in parallel with Play. Budget: **$25 total** (Play registration). Everything else is free.

> **STATUS 2026-08-16:** Step 1 in progress — Play Console account created with **laughsdogcat@gmail.com**, developer name **YugMa**, $25 paid, identity documents submitted, **verification pending**. Use this same Google account for AdMob (step 2) and RevenueCat sign-in (step 3) so everything lives under one identity. Next after verification email arrives: step 1.6 (Create app) onward, then AdMob + RevenueCat.

**Timeline pressure:** Shipaton closes **Sep 30**. New personal Play accounts must run a closed test with **12 testers for 14 continuous days** before production access. Play identity verification itself can take 1–3 days. Start Step 1 tonight; recruit your 12 testers this week (friends/colleagues with Android phones — they just install from a link and keep the app installed).

---

## 1. Google Play Console (~30 min + 1–3 day verification wait, $25)

1. Go to **play.google.com/console/signup** — sign in with your **personal** Google account (mishra.shubham5208@gmail.com — never the work account).
2. Choose **"Yourself"** (personal developer account).
3. Fill legal name, address, phone — must match a government ID; Google verifies identity now (DL/passport upload). Developer name shown on Play: e.g. "Shubham Mishra" or "YugMa".
4. Pay the **$25 one-time fee** (card).
5. Wait for identity verification (email; usually <48h).
6. Once verified: **Create app** → name `TerraWatch: Earthquake Monitor` (exact 30-char title is in `store-assets/listing.md`), default language English (US/IN), App (not game), **Free**.
7. Work through **App content** (Dashboard → left nav "App content") using the prepared drafts:
   - **Privacy policy URL**: enable GitHub Pages first — one command I can run for you: `gh api repos/shubham/terrawatch/pages -X POST -f "source[branch]=main" -f "source[path]=/docs"` → URL becomes `https://shubham.github.io/terrawatch/privacy` (put that in the field). Tell me when you want it enabled.
   - **Data safety**: answers drafted in `store-assets/listing.md` (location = on-device only, not shared; advertising ID = collected by AdMob SDK when ads shown; no accounts).
   - **Content rating questionnaire**: answers drafted in `store-assets/listing.md` (no violence/gambling/UGC; "digital purchases: No" until the paywall goes live at Task 8 — re-answer Yes then).
   - **Ads declaration**: Yes, contains ads.
   - **Target audience**: 18+ (simplest; avoids families policy).
8. **Testing → Closed testing → Create track** ("beta"). Add a tester email list (your 12 testers' Gmail addresses). The AAB upload happens at Task 8 — the track can exist empty today.
9. **Setup → API access** (needed for RevenueCat later): follow the prompt to link/create a Google Cloud project, then create a **service account** with "Finance" + "View app information" permissions in Play Console's Users & permissions. Download its JSON key — RevenueCat will ask for it. (RevenueCat's own wizard walks this; fine to defer to step 3.4 below.)
10. After Task 8 uploads the AAB to the closed track and your 12 testers opt in: the **14-day clock** runs. After 14 days with ≥12 testers, Play unlocks the "apply for production" button.

## 2. AdMob (~15 min, free — parallel with Play)

1. **admob.google.com** → sign in with the same personal Google account → accept terms (country: India; payment details can wait until $100 earnings threshold).
2. **Apps → Add app** → "Is the app listed on a supported app store?" → **No** (pre-registration; you'll link the Play listing after launch) → platform Android → name TerraWatch.
3. Copy the **App ID** — looks like `ca-app-pub-1234567890123456~1234567890` (note the `~`).
4. **Ad units → Add ad unit → Banner** → name "home-anchored-banner" → copy the **Ad unit ID** — looks like `ca-app-pub-1234567890123456/0987654321` (note the `/`).
5. Note your **publisher ID** (the `pub-1234567890123456` part) — needed for app-ads.txt:
   - I'll put `google.com, pub-XXXXXXXXXXXXXXXX, DIRECT, f08c47fec0942fa0` into `docs/app-ads.txt` and the Pages URL into the Play listing's website field once you give me the pub id.
6. After launch: AdMob → Apps → link the published Play listing (ads serve limited until linked + app-ads.txt verified — normal for new apps).

## 3. RevenueCat (~20 min, free — needs Play API access from step 1.9)

1. **app.revenuecat.com** → Sign up (GitHub or Google login, personal).
2. **Create project** "TerraWatch" → **Add app** → Play Store → package `com.yugma.terrawatch`.
3. It asks for **Play service credentials**: upload the service-account JSON from step 1.9 (RevenueCat's docs page walks the exact Play-side clicks if you deferred it).
4. **Entitlements → New**: identifier **exactly `plus`** (must match `RevenueCatEntitlements.kt` — hardcoded).
5. **Products**: first create the IAP in **Play Console → Monetize → In-app products** → product ID `terrawatch_plus` (one-time purchase, e.g. ₹299/$3.99) — note: Play requires an AAB uploaded to some track before IAP products can be created, so this lands right after Task 8's upload. Then in RevenueCat: import/attach `terrawatch_plus` to the `plus` entitlement.
6. **API keys → Google Play** → copy the **public** key — looks like `goog_AbCdEfGhIjKlMnOp`.
7. (Task 8 does the rest: paywall wiring, AdTracker, sandbox purchase test with your own tester account.)

## 4. monetization.properties (2 min, after 2 & 3)

```bash
cp composeApp/monetization.properties.example composeApp/monetization.properties
```

Edit `composeApp/monetization.properties` (this file is **gitignored** — it never leaves your machine):

```properties
REVENUECAT_API_KEY=goog_AbCdEfGhIjKlMnOp        # from RevenueCat step 3.6
ADMOB_APP_ID=ca-app-pub-1234567890123456~1234567890   # from AdMob step 2.3 (the ~ one)
ADMOB_BANNER_UNIT=ca-app-pub-1234567890123456/0987654321  # from AdMob step 2.4 (the / one)
```

Then tell me "keys are in" — Task 8 takes over: rebuild with real IDs, full ad/purchase device pass, signing, AAB, closed-track upload, Shipaton kit.

## Quick reference — what I still need from you

| # | Action | Blocks |
|---|---|---|
| 1 | Play Console signup + $25 + ID verification | everything store-side |
| 2 | 12 tester emails collected | the 14-day clock |
| 3 | AdMob app + banner unit created → 3 IDs to me | real ads |
| 4 | RevenueCat project + `plus` entitlement → API key to me | IAP |
| 5 | "Enable GitHub Pages" go-ahead | privacy-policy URL + app-ads.txt |
