# Plan 4 Task 1: R8 release hardening.
#
# Starting position is DELIBERATELY EMPTY. AGP's bundled proguard-android-optimize.txt (see
# build.gradle.kts's proguardFiles call) already covers the Android framework surface (Activity/
# Application/View constructors, Parcelable CREATOR fields, enum valueOf/values, native method
# names, annotations, etc.), and every well-maintained library this app depends on (AndroidX,
# Compose, Coroutines, Koin, Ktor's OkHttp engine, SQLDelight, kotlinx-serialization,
# maplibre-compose) ships its own consumer-rules.pro bundled inside its AAR/jar, merged
# automatically into R8's rule set — no explicit keep here duplicates any of that.
#
# Every rule below was added because a REAL `assembleRelease` run broke or warned without it —
# never speculatively. Each one names the exact symptom (missing-class warning text, or the
# runtime crash/log line from an on-device smoke pass) it fixes. If a future dependency bump makes
# a rule below provably unnecessary (e.g. the library starts shipping the equivalent consumer rule
# itself), remove it rather than let it fossilize — see the "Verified NOT needed" section below for
# the trouble spots this task's brief flagged that turned out to need nothing.
#
# RESULT: zero rules were needed. `assembleRelease` succeeded clean on the FIRST attempt — no
# composeApp/build/outputs/mapping/release/missing_rules.txt was ever generated (R8 raises that
# file, and fails the build, the moment ANY keep rule references a class it can't find at all —
# its absence means R8's whole-program analysis never hit a missing-class wall). This file stays
# empty, not because keep rules were tried and deleted, but because the real build never asked for
# any.
#
# Verified NOT needed (brief's own flagged trouble spots — checked, not assumed):
# - **maplibre-native JNI** (org.maplibre.**): the concern is real in general (R8 has no visibility
#   into a .so's JNI calls back into obfuscated Java/Kotlin classes) but maplibre-compose 0.14.0's
#   AAR already ships its own consumer-rules.pro (merged automatically — see
#   :composeApp:mergeReleaseConsumerProguardFiles's inputs). Confirmed on-device, not just by clean
#   build: the release APK's live map rendered real MapLibre vector tiles, pins, clustering, and the
#   home-radius ring correctly on 98bc1cd8 (task1-release-home.png) — if JNI callback targets had
#   been renamed without a matching native-side rule, this would have been a black/blank map or a
#   native crash, not a silent success.
# - **SQLDelight driver reflection**: the Android driver (`AndroidSqliteDriver`) doesn't reflect by
#   class name; confirmed on-device — History's archive browse and Home's live-feed persistence both
#   round-tripped real data through the release build's SQLDelight-backed store with no error.
# - **Koin DI**: this app's `koin-core`/`koin-compose-viewmodel` usage is 100% lambda-based module
#   DSL (`single { ... }`, `viewModel { ... }` in AppModule.kt) — no classpath/annotation scanning,
#   so there is no string-based lookup for R8 to break. Confirmed on-device: every Koin-resolved
#   dependency across all 5 screens (HomeViewModel, SettingsViewModel, OnboardingStore,
#   LocationRequester, etc.) resolved correctly in the release build.
# - **Ktor's OkHttp engine**: `ktor-client-okhttp` bundles its own consumer rules; confirmed
#   on-device — the release build's live USGS/EMSC feed fetch succeeded (Home showed real quakes,
#   "LIVE" + fresh new-quake count, not a stuck/failed refresh state).
# - **kotlinx-serialization**: IS used (this app does NOT use "manual JSON only" as the brief
#   guessed — `QuakeDao.kt`'s private `@Serializable data class RevisionJson` and a couple of
#   `AppNav.kt` mentions exist, checked directly rather than trusting the brief's assumption). No
#   rule needed anyway: kotlinx-serialization generates its `$serializer` companion at compile time
#   (no runtime reflection), and the library's own jar ships the matching consumer rule for R8's
#   Kotlin-metadata-aware shrinking pass. Confirmed on-device via the exact code path that uses it
#   (QuakeDao's revision-history round trip, exercised by every live quake ingested during the
#   smoke pass with no crash).
#
# Known, unrelated, non-fatal R8 warning observed on this AGP 8.10.1 / R8 toolchain (recorded here so
# a future reader doesn't mistake it for something this file should suppress): "R8: An error
# occurred when parsing kotlin metadata... normally happens when using a newer version of kotlin
# than the kotlin version released when this version of R8 was created." This project's Kotlin is
# 2.2.20, but Gradle's version resolution picks a newer transitive kotlin-stdlib (2.3.21, pulled in
# by a dependency requesting it) for the actual compile/R8 classpath — R8's bundled kotlin-metadata
# parser lags that far-newer stdlib's metadata format. Cosmetic: it only means R8 falls back to
# treating a handful of classes as plain Java for its Kotlin-specific shrinking heuristics (still
# fully correct, just marginally less aggressive for those classes) — not a missing-class error, not
# fixable by a keep rule, and orthogonal to anything this app's own code does.
