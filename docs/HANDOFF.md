# TerraWatch — Agent Handoff / Continue-From-Here Guide

Written 2026-08-21. For an AI agent or developer picking this up on a **fresh machine** with
**no conversation history**. Read this top to bottom before touching anything.

---

## 0. What TerraWatch is, in one paragraph

A free Android earthquake-monitoring app (`com.yugma.terrawatch`), KMP + Compose Multiplatform,
built for RevenueCat **Shipaton 2026** (deadline **Sep 30, 2026**). Live quakes from USGS + EMSC,
on-device digest alerts (deliberately *not* early-warning — see honesty rule below), history,
insights, favorites, nearby-radius. **No backend** — the app talks only to public feeds; nothing
user-specific ever leaves the device. Monetized by AdMob banner + a "Plus" (remove-ads) RevenueCat
subscription. It is at **v1.0.0**, feature-complete, signed, and being pushed through Google Play
**closed testing** toward a production launch.

## 1. First 15 minutes after `git clone`

Do these in order. Steps 2–4 need things only the **repo owner** can give you.

1. **Read this whole file**, then skim [README.md](../README.md) and
   [docs/ACCOUNT-SETUP.md](ACCOUNT-SETUP.md).
2. **Fix the JDK** (landmine #1). The Kotlin compiler **crashes on JDK 25** with
   `IllegalArgumentException: 25.x`. Install/point to a **JDK 17** (JetBrains Runtime 17 was used
   originally) and `export JAVA_HOME=/path/to/jdk-17`. Verify: `./gradlew jvmTest` compiles.
3. **Fix git identity + auth** (landmine #2). Commits MUST be the owner's **personal** identity
   (`Shubham Mishra <mishra.shubham5208@gmail.com>`, GitHub user `shubham`), **never** a work
   account. Set repo-local:
   `git config user.name "Shubham Mishra"; git config user.email "mishra.shubham5208@gmail.com"`.
   For push auth the original machine used a shim so `gh` supplies credentials:
   `export GIT_CONFIG_GLOBAL=/tmp/tw-gitconfig` where that file contains a `gh auth git-credential`
   helper. On a new machine, simplest is `gh auth login` as `shubham`, then push normally (skip the
   shim). Confirm remote: `git remote -v` → `github.com/shubham/terrawatch`.
4. **Get the secrets that are NOT in git** — see §2. Without the keystore you cannot ship a Play
   update that installs over the existing one.
5. **Prove the toolchain:** `./gradlew jvmTest` (expect ~760 passing) +
   `./gradlew :composeApp:assembleDebug`. Green = you're ready.
6. **Commit trailer:** every commit ends with
   `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

## 2. Secrets & files NOT in the repo (you must obtain these from the owner)

`.gitignore` excludes all of these on purpose. They live with the repo owner (Shubham) and/or in
GitHub Actions secrets — never in git.

| Thing | What it is | Where it is | Needed for |
|---|---|---|---|
| **`terrawatch-upload.jks`** + its passwords | The Play **upload signing key** (generated 2026-08-21, alias `terrawatch-upload`, CN=YugMa). | Owner has the file + `keystore-credentials.txt`. **Ask for them.** | Signing any AAB that updates the Play app. Lose it → recover via Play (Google holds the real app-signing key). |
| **`composeApp/monetization.properties`** | `REVENUECAT_API_KEY`, `ADMOB_APP_ID`, `ADMOB_BANNER_UNIT`. | Does **not exist yet** — owner is creating the accounts. Template: `composeApp/monetization.properties.example`. | Real ads + real Plus subscription. App builds & runs fine WITHOUT it (test ad IDs, free-only). |
| **Play service-account JSON** | Google Play API key for automated uploads. | Owner creates in Play Console → Setup → API access. | CI auto-deploy (release.yml) + RevenueCat linking. |
| **GitHub Actions secrets** | 8 secrets the release workflow reads. | Repo → Settings → Secrets. See the CI plan doc. | `release.yml` tag-driven Play deploys. |

**If monetization.properties is absent** (the normal state right now): the build substitutes
Google's official AdMob **test** ad unit, and `EntitlementsProvider` resolves to always-free. This
is intentional and correct for development and for the closed test.

## 3. Environment landmines (all of them)

- **JDK 17 only** — JDK 25 crashes the compiler (§1.2).
- **Personal git identity only** — never the work email (§1.3).
- **Android-only runtime.** Only Android is verified on a real device. `jvm()` and `wasmJs()`
  targets exist to keep shared code honest and must **compile**, but are never run/verified. Don't
  spend effort on desktop/web behavior. maplibre map + AdMob + RevenueCat are androidMain-only.
- **Real-device verification, not emulator** (owner's standing rule). Two devices used:
  OnePlus 9R (Android 14, OxygenOS, USB — flaky cable, `adb kill-server && adb start-server` to
  recover) and Motorola edge 50 fusion (Android 16, WiFi adb — serial has a space/parens, always
  quote it; drops off-network, re-resolve via `adb devices`).
- **OxygenOS shell restrictions** (OnePlus): `pm clear`, `pm revoke`, `WRITE_SETTINGS`, and native
  `screenrecord` (SIGSEGV) are blocked. Workarounds: change permissions via the system Settings UI
  (drive it with `adb shell input` + `uiautomator dump`), use **scrcpy** for screen video, use an
  emulator only for the specific cases device shell can't reach (disclose when you do).
- **Evidence integrity is enforced culturally here.** Never claim a file/symbol/endpoint without
  grepping it first; never narrate a screenshot you didn't save. Device evidence goes in
  `docs/qa/<plan-or-round>/` and is committed. Fabrication triggers a redo.

## 4. Current state (as of this handoff)

- **Branch `main` @ latest**, fully pushed, CI green. Everything below is merged.
- **Version 1.0.0 / versionCode 3.** A **signed release AAB exists** (built with the upload key)
  and has been handed to the owner to upload as the **first** Play release (Play requires the very
  first upload to be manual; the API can't create an app's first release).
- **~760 unit tests**, all green. R8/minify on release. Migration guard in CI.
- **CI:** `.github/workflows/ci.yml` runs tests + 3-target compiles + migration verify on
  pushes to `main`/`feat/**`/`fix/**`/`docs/**`. `.github/workflows/release.yml` is the
  tag-driven Play deployer (dormant until the 8 secrets are set — see §2 and the CI plan doc).
- **Store assets ready:** icon (512 + 1024), feature graphic (1024×500), 6 framed screenshots,
  full listing copy + data-safety/content-rating drafts (all in `store-assets/`). ⚠️ Screenshots
  currently show a "Test Ad" placeholder banner — regenerate from a real-ads release build before
  **production** (fine for closed testing).
- **Brand:** dial logo (green needle + amber dot) as adaptive icon + monochrome + splash; splash
  wordmark is a vector in Inter SemiBold. Font across the app is **Inter** (bundled, 3 weights).
- **GitHub Pages live:** privacy policy at `https://shubham.github.io/terrawatch/privacy`.
- **Known open items / residuals** (none block the closed test):
  - Store screenshots show test-ad banner (above) — refresh before production.
  - Samsung device untested by us — the detail-sheet fix (`skipPartiallyExpanded`) is M3-standard
    so low-risk, but the exact One UI nav-bar inset was never seen on-device.
  - Splash wordmark vector fix (2026-08-21) is not yet cold-start-confirmed on a real device
    (device was offline at fix time) — verify on next device pass.
  - GDELT news feature is code-complete but **disabled** (`NewsFeature.ENABLED = false`) — GDELT
    unreliable from the owner's networks. Do not advertise news in store copy while off.
  - Font loads synchronously on first frame (~1.2MB) — no measured jank, not chased.

## 5. What's left — the path to launch (prioritized)

**Critical path = Google's 14-day closed-test clock** (new personal accounts must run a closed
test with ≥12 testers opted in for 14 continuous days before production). Everything else fits
inside those 14 days.

1. **Owner: start the clock.** Upload the signed AAB to a **Closed testing** track (NOT Internal —
   Internal doesn't count), publish the listing (copy in `store-assets/listing.md`), complete App
   content forms (privacy URL above), and get the 12 testers to actually opt in + install.
2. **Owner: create accounts** → AdMob (3 IDs), RevenueCat (entitlement id exactly `plus`), Play
   service account (JSON). Full steps: [docs/ACCOUNT-SETUP.md](ACCOUNT-SETUP.md).
3. **Agent, when keys arrive ("keys are in"):** write `composeApp/monetization.properties` from
   the owner's values; wire the real RevenueCat paywall purchase flow (currently a disabled stub in
   `PaywallScreen`); device-verify real ads + a sandbox purchase; populate the 8 GitHub Actions
   secrets; regenerate clean store screenshots (real ads); do a dry-run `internal` release via
   `release.yml`. After that, tagging `v1.0.x` auto-ships to testers.
4. **Owner + agent: promote to production** after 14 days / 12 testers (a deliberate human click
   in Play Console — no auto-prod while the account is new).
5. **Shipaton submission kit:** 2-min demo video (scrcpy; `store-assets/video/` has a prior cut),
   Devpost writeup (KMP + RevenueCat SDK + Catvertising angle), social launch assets (ready in
   `store-assets/social/`).

**Roadmap ideas** (post-launch, from the competitor study
`docs/research/competitor-my-earthquake-alerts.md`): "I'm safe" share, fault-line overlay (with a
"context not forecast" caption to respect the honesty rule), quiet hours, list sort. FCM push
alerts are the one big fork — they'd require standing up a backend and would break the zero-server
privacy story; treat as a deliberate product decision, not a quick win.

## 6. How work is done here (the method — please keep to it)

This repo was built with a disciplined subagent-driven flow. Matching it keeps quality consistent:

- **Spec → plan → TDD → review → device-verify → merge.** Plans live in
  `docs/superpowers/plans/`. Logic is written test-first (tests fail, then pass). See the
  `superpowers:*` skills (brainstorming, writing-plans, subagent-driven-development, TDD,
  systematic-debugging) if available in your harness.
- **Feature branches** (`feat/**`, `fix/**`), never commit straight to `main` for non-trivial work;
  merge after CI is green + an adversarial review pass. Review rounds hunt for real defects
  (correctness/security/ethics), fix rounds close them, then a scoped re-review confirms.
- **Immutable product rules** (do not regress — they define the product):
  - *Alert honesty:* digest framing only, never "early warning" / seconds-count claims. Any
    `alertEvents` consumer stays behind its guard.
  - *Ad ethics:* banner hidden while the detail sheet is open and during onboarding; ads only when
    Plus is inactive; no interstitials/rewarded. Encoded in `adSlotVisible` (a TDD'd truth table in
    `core:ads`) — its tests are pinned; don't weaken them.
  - *Privacy:* no backend, no accounts; home location never transmitted.
- **Notification floor:** alerts only fire for **M4.0+** (hard floor in the alert engine).
- **Device matrix:** every UI-affecting change gets a real-device screenshot committed under
  `docs/qa/`.

## 7. Reference docs (index)

- [README.md](../README.md) — overview, build commands, module map.
- [docs/ACCOUNT-SETUP.md](ACCOUNT-SETUP.md) — Play/AdMob/RevenueCat owner setup, with live status.
- [docs/superpowers/plans/2026-08-17-play-ci-deployment.md](superpowers/plans/2026-08-17-play-ci-deployment.md)
  — the CI→Play release pipeline: how a tag ships, the 8 secrets, the one-time setup.
- [store-assets/listing.md](../store-assets/listing.md) — final store copy + form answers.
- `docs/superpowers/plans/2026-08-08…2026-08-17-*.md` — the five delivery plans in order
  (foundation-data → ui-shell → screens → release → polish) + font-selection + ui-polish findings.
- `docs/superpowers/plans/plan-*-exit-conditions.md`, `plan-4-backlog.md` — carried decisions and
  deferred items with rationale.
- `docs/qa/*/RESULTS.md` — device-verification logs (what was tested, on which device, with
  screenshots).
- `docs/research/competitor-my-earthquake-alerts.md` — competitor teardown + roadmap input.

## 8. Accounts & identities (don't mix them up)

- **Google Play / AdMob / RevenueCat:** `laughsdogcat@gmail.com`, developer name **YugMa**.
- **GitHub / git commits:** `shubham` / `mishra.shubham5208@gmail.com` (personal). Never the work
  email.
- These are deliberately different accounts. Commits use the GitHub identity; store/monetization
  uses the YugMa identity.
