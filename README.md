# TerraWatch — Earthquake Monitor

A free, honest earthquake-monitoring app for Android. Live global quakes from **USGS + EMSC**,
digest alerts (never "early warning"), history archive, insights, and a nearby-radius model —
built on a **Kotlin Multiplatform + Compose Multiplatform** stack with zero paid infrastructure.
Entry for **RevenueCat Shipaton 2026** (submission window closes **Sep 30, 2026**).

- **Package:** `com.yugma.terrawatch` · **Version:** 1.1.0 (versionCode 4)
- **Runtime target:** Android only (jvm/wasm targets are compile-only, for CI health)
- **Status:** feature-complete for 1.0; in Google Play **closed testing**; monetization keys pending

---

## 🤖 Continuing this work (AI agent or new developer)?

**Read [`docs/HANDOFF.md`](docs/HANDOFF.md) first.** It's the clone-and-continue guide: exact
environment setup, the secrets you must get from the repo owner (they are NOT in git), current
state, what's left, and how work is done here. Don't build or change anything before reading it —
there are two environment landmines (JDK version, git identity) that waste an hour if you hit them.

---

## Quickstart

Two environment requirements (see HANDOFF.md for why):

```bash
# 1. JDK 17 — the system JDK 25 CRASHES the Kotlin compiler. Use a JBR/JDK 17.
export JAVA_HOME=/path/to/jbr-17   # on the original machine: .../jbr-17.0.14/Contents/Home
# 2. gh credential helper for git push (see HANDOFF.md "Secrets & auth")
export GIT_CONFIG_GLOBAL=/tmp/tw-gitconfig
```

```bash
./gradlew jvmTest                              # all unit tests (~760, the fast feedback loop)
./gradlew :composeApp:assembleDebug            # debug APK -> composeApp/build/outputs/apk/debug/
./gradlew :composeApp:assembleRelease          # per-ABI + universal APKs (needs keystore to sign; else debug-signed)
./gradlew :composeApp:bundleRelease            # Play AAB -> composeApp/build/outputs/bundle/release/
./gradlew :core:database:verifySqlDelightMigration   # schema migration guard (also in CI)
```

Three-target compile check (CI runs these):
`compileDebugKotlinAndroid`, `compileKotlinJvm`, `compileKotlinWasmJs`.

## Module map

```
composeApp/                 Android app + shared UI (screens, nav, viewmodels); jvm/wasm targets compile-only
core:model                  Quake, GeoPoint, MagnitudeBand, FavoritePlace — pure domain
core:network                Ktor clients: USGS feed/archive, EMSC WebSocket, GDELT (news, currently disabled)
core:database               SQLDelight (.sq + .sqm migrations), QuakeDao, stores
core:data                   QuakeRepository, dedupe, alert engine, AlertDigestWorker, feature stores
core:ui                     Design system (TerraTheme, Inter font, tokens), shared components
core:ads                    BannerAd expect/actual — android=AdMob, adSlotVisible ethics truth table,
                            AdRevenueTracker (ad revenue -> RevenueCat)
```

## Stack

Kotlin 2.2.x · Compose Multiplatform 1.9 · Ktor 3 · SQLDelight 2.1 · Koin 4 ·
maplibre-compose (Android map) · WorkManager (alerts) · purchases-kmp (RevenueCat) ·
play-services-ads (AdMob). R8/minify on release.

## Key docs

| Doc | What |
|---|---|
| [docs/HANDOFF.md](docs/HANDOFF.md) | **Start here** — clone & continue guide |
| [docs/ACCOUNT-SETUP.md](docs/ACCOUNT-SETUP.md) | Play Console / AdMob / RevenueCat setup (owner actions) |
| [docs/superpowers/plans/2026-08-17-play-ci-deployment.md](docs/superpowers/plans/2026-08-17-play-ci-deployment.md) | CI → Play automated release pipeline |
| [store-assets/listing.md](store-assets/listing.md) | Store listing copy + data-safety/content-rating answers |
| `docs/superpowers/plans/*.md` | The five delivery plans (foundation → polish) |
| `docs/qa/*/RESULTS.md` | Device-verification evidence per plan/round |

Data © USGS, EMSC, OpenStreetMap/OpenFreeMap. Code by Shubham Mishra, paired with Claude.
