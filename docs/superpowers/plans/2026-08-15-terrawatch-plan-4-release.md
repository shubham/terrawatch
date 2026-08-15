# TerraWatch Plan 4: Monetization, Alerts & Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship TerraWatch 1.0.0 to Google Play inside the Shipaton window (closes **Sep 30, 2026** — 6.5 weeks): R8-hardened release build, WorkManager digest alerts, RevenueCat Plus IAP + AdMob banner, the external-review features (news, share targets, insights density, SDK-36 audit), store assets, and the submission kit.

**Architecture:** Android-only runtime (user directive stands; jvm/wasm compile-only for CI). New `core:monetization` (expect/actual entitlements) + `core:ads` androidMain AdMob wrapper per spec §5.1. Alerts consume the existing `alertEvents` SharedFlow behind the F5 guard. News via GDELT DOC API in `core:network` behind a spike gate.

**Tech Stack (additions):** purchases-kmp 3.x + purchases-kmp-ui, purchases-android AdTracker, play-services-ads (AdMob), WorkManager 2.10.x, R8 (AGP built-in).

## Global Constraints

- Branch `feat/plan-4-release` off main. All prior constraints carry: package root, minSdk 26/compileSdk 36/targetSdk 36, TDD in logic, evidence integrity (grep before citing; commit device evidence to `docs/qa/plan-4-device-matrix/`; narrative without artifacts = automatic recapture), commit trailer `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`, real device 98bc1cd8 only, `export GIT_CONFIG_GLOBAL=/tmp/tw-gitconfig` + `JAVA_HOME=jbr-17.0.14` (see plan-3-exit-conditions env notes).
- **USER-GATED prerequisites (cannot be automated — surface early, work around while pending):** Google Play Console account (**$25**, needs 12 closed-track testers × 14 days for new personal accounts), RevenueCat account (free), AdMob account (free), a public GitHub Pages site for app-ads.txt. Tasks 6-8 block on these; Tasks 1-5 don't.
- Ad ethics (spec §8, immutable): banner hidden while detail sheet open and during onboarding; no interstitials/rewarded; ads only when Plus inactive.
- Alert honesty (spec §6.5): digest framing, never "early warning". F5 guard mandatory before any alertEvents consumer ships.
- plan-4-backlog.md + plan-3-exit-conditions.md carried items are in-scope where a task touches their area; each task's brief names its carried items.

---

### Task 1: R8 release hardening
`buildTypes { release { isMinifyEnabled = true; proguard rules } }`; keep-rules for maplibre/sqldelight/koin/ktor reflection surfaces; debug-hook symbols verified STRIPPED from release dex (dex-grep red→green equivalent); release APK installs + full manual smoke on device (map, tabs, settings, onboarding); versionCode 2, versionName 0.9.0. Carried: minify gate row, release-smoke.

### Task 2: DB retention + alert-path guards (F1+F5+M4 decisions)
Retention: keep feed rows 30 days (prune on app start, `DELETE WHERE timeMillis < now-30d AND id NOT IN (protected)`) BUT protect History-fetched archive rows — add `origin TEXT` column (feed/archive/live) default 'feed'; archive rows exempt from prune; TDD prune + protection. F5: `loadArchivePage` ingests with `rules = emptyList()` (never alerts) — TDD. M4 decision: notifications use full rules (world M6 included); pill stays nearby-only — record ruling + kdoc both sites. M1 torn-write: make HomeLocationStore.set one transaction (dao gains `metaPutAll(pairs)` transactional). M2: clamp radius on read to RADIUS_STEPS range.

### Task 3: WorkManager digest alerts + notification permission
POST_NOTIFICATIONS runtime ask (moved to onboarding step 3 action or first-alert-time — decide: step 3 gains "Enable alerts" button = in-context ask, honoring plan-3 backlog's sequencing item; launch-time location ask ALSO moves to step 2 same pattern); notification channel "Earthquake digests"; `AlertDigestWorker` periodic 30-60min: refreshFeed + collect alertEvents-equivalent evaluation over new-since-last-run rows (NOT live SharedFlow — worker re-evaluates via AlertRuleEngine on fetch delta; alertEvents buffer/replay revisit satisfied by worker-side evaluation), notification per quake (dedupe by id, max 3/run + summary), tap → deep link detail. Doze-honest framing. Device: real notification screenshot, tap-through, permission deny path.

### Task 4: External features bundle A — share targets + SDK-36 audit
Share row in DetailSheet: WhatsApp/X/Threads package-targeted intents + availability check (PackageManager), fallback chooser; TDD intent-builder pure fn. SDK-36 audit: edge-to-edge sweep (all screens under insets), predictive back (enable + verify), manifest audit; document in report; emulator API-36 smoke ONLY for back-gesture/insets (black-map caveat, non-map screens fine — disclosed exception to device-only rule, controller-approved).

### Task 5: External features bundle B — GDELT news spike + Insights density
Spike (≤45min): GDELT DOC API quality for 3 recent M6+ events (relevance, English, dead-link rate) → GATE. If pass: `GdeltClient` in core:network (TDD w/ fixture), news section in DetailSheet (M5.5+, 3 headlines, source+link via CustomTabs/ACTION_VIEW), "In the news" Insights card (M6+ 7d). If fail: USGS event-page link row fallback (zero-dep). Insights density: FDSN `/count` backfill for 30d chart when cache thin (<100 rows in window) + "based on N cached quakes" caption — TDD count-client + merge logic.

### Task 6: RevenueCat + TerraWatch Plus + AdMob banner [USER-GATED: RC + AdMob accounts]
core:monetization expect/actual (EntitlementsProvider; android = purchases-kmp; jvm/wasm = always-free no-op); Plus product config doc (user creates in RC dashboard + Play Console once available); paywall via purchases-kmp-ui (Settings "TerraWatch Plus" row); Plus gates: unlimited saved places (free=1), custom rules editor (free=default rule); core:ads BannerAd expect/actual (android = AdMob adaptive anchored in Task-4 spacer slot; test unit IDs until real); visibility rules per constraints; AdTracker wiring. Until accounts exist: full implementation against TEST ids + sandbox, device-verified with test ads; real ids = config swap task 8.

### Task 7: Store assets + listing [USER-GATED: Play Console]
Adaptive launcher icon (shield + ring motif, Calm Guardian palette — SVG → mipmap set); feature graphic 1024×500; 1024² icon; ≥4 phone screenshots (from matrix, framed); listing copy (title ≤30, short ≤80, full description — honest, no "early warning" claims); privacy policy page (GitHub Pages — location usage disclosure, no data collection beyond device); app-ads.txt on same Pages site; data-safety form answers doc.

### Task 8: Release + submission [USER-GATED: all accounts + 12 testers]
versionName 1.0.0 versionCode 3; signed AAB (user generates upload key or Play App Signing); closed-testing upload; CI release-build job; Shipaton kit: 2-min demo video (scrcpy recording — OxygenOS screenrecord SIGSEGVs), Devpost submission draft (RevenueCat SDK usage narrative, KMP category, Catvertising angle); README badges/final polish; final whole-branch review; merge.

## Self-Review
Coverage: spec §8 (RC/AdMob/ethics) → T6; §6.5 alerts → T3; backlog external items 1-5 → T4/T5; F1/F5/M1/M2/M4 → T2; minify → T1; icon/listing/submission → T7/T8. User-gates isolated to T6-T8 with test-id workarounds so T1-T5 proceed unblocked. No placeholders; briefs carry per-task detail at dispatch (established pattern).
