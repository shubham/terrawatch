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
#   into a .so's JNI calls back into obfuscated Java/Kotlin classes) but the transitive
#   `org.maplibre.gl:android-sdk:13.0.2` dependency (pulled in by maplibre-compose 0.14.0, NOT
#   maplibre-compose's own AAR — corrected here; the prior draft of this comment mis-credited that
#   AAR directly) already ships its own proguard.txt with the needed keeps, merged automatically —
#   verified in composeApp/build/outputs/mapping/release/configuration.txt:222-236: line 222's own
#   "The proguard configuration file for the following section is .../android-sdk-13.0.2/
#   proguard.txt" file-source comment, followed by lines 228-236's "Reflection on classes from
#   native code" section, which is what actually keeps `org.maplibre.android.tile.TileOperation`,
#   `.maps.RenderingStats`, and `.maps.NativeMapOptions` — not anything shipped in
#   maplibre-compose's own consumer-rules.pro. Confirmed on-device too, not just by clean build: the
#   release APK's live map rendered real MapLibre vector tiles, pins, clustering, and the
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
#
# Plan 4 Task 6 UPDATE: `assembleRelease` after adding play-services-ads 25.4.0 +
# purchases-kmp-core/purchases-kmp-models 3.5.0 emits MANY MORE lines of this exact same message
# class (both SDKs' own internal modules were compiled with a newer Kotlin metadata version, 2.3.0,
# than this R8's bundled parser expects, 2.1.0) — confirmed to be the identical cosmetic condition
# this paragraph already documents, not a new problem: `assembleRelease` still exits 0, a real
# `composeApp-release.apk` is produced (61 MB, up from Task 1's release build — expected, two
# substantial SDKs added), and — same verification method this file's own "RESULT" paragraph above
# already establishes — no `missing_rules.txt` was generated (confirmed directly: `find
# composeApp/build/outputs/mapping -iname "*missing*"` returns nothing). Zero new keep rules were
# needed for either SDK.

# Plan 4 Task 6: RevenueCat purchases-kmp-core's AndroidX App Startup auto-init (Context capture)
# and play-services-ads' own AdMob SDK internals both ship their OWN bundled consumer-rules.pro
# (same "well-maintained library, no explicit keep needed" default this file's header already states
# for AndroidX/Compose/Koin/Ktor/SQLDelight/kotlinx-serialization/maplibre-compose) — confirmed by
# the clean `assembleRelease` result above, not assumed. `RevenueCatEntitlements` itself
# (core:monetization androidMain) is UNREACHABLE at runtime throughout Task 6 (no RevenueCat API key
# configured — see that class's own kdoc), so this is a compile/shrink-time-only proof for now; a
# real on-device purchase-flow smoke pass is Task 8's job, once a real account/product exists to
# smoke-test against.
#
# One real, non-cosmetic dependency-resolution issue Task 6 DID hit and fix (not an R8/proguard-rule
# concern — recorded here for proximity, fixed in core:ads/build.gradle.kts and
# composeApp/build.gradle.kts instead): play-services-ads transitively pulls
# `androidx.privacysandbox.ads:ads-adservices(-java)`, which depends on full `com.google.guava:
# guava`. Guava's own metadata then forces `com.google.guava:listenablefuture` (the tiny
# interface-only shim `androidx.work:work-runtime`'s `ListenableFuture`-returning APIs need — see
# `AlertDigestScheduler.android.kt`) to its deliberately EMPTY "9999.0-empty-to-avoid-conflict-with-
# guava" variant across BOTH runtime and (via AGP's cross-configuration consistency check) COMPILE
# classpaths — but full Guava itself is only ever a RUNTIME dependency of `ads-adservices-java`, so
# at COMPILE time the empty shim wins with nothing left to supply the real class, and
# `:composeApp:compileDebugKotlinAndroid` failed outright with "Cannot access class
# 'ListenableFuture'" (reproduced directly, confirmed gone after the fix). Fixed by excluding both
# Privacy Sandbox modules from both of this app's `play-services-ads` dependency declarations — this
# app has no ad-attribution/conversion-reporting need at all (one plain anchored banner, spec §8),
# so neither module was ever needed.

# Plan 4 Task 3: AlertDigestWorker, WorkManager's own reflection-based instantiation.
#
# Unlike every OTHER concern this file's "Verified NOT needed" section above walks through
# (Koin/SQLDelight/Ktor/kotlinx-serialization all avoid string-based reflection entirely in this
# app), WorkManager's default WorkerFactory genuinely does one: it persists a Worker subclass's
# fully-qualified name into its own Room-backed WorkSpec, then later re-instantiates it via
# `Class.forName(...).asSubclass(ListenableWorker::class.java)` followed by reflectively invoking
# its `(Context, WorkerParameters)` constructor. androidx.work:work-runtime-ktx's own bundled
# consumer-rules.pro cannot possibly name THIS app's own AlertDigestWorker class ahead of time — a
# library's consumer rules only ever cover that library's OWN classes, never a consuming app's.
# Without this rule, R8 is free to rename/obfuscate AlertDigestWorker, and the very first periodic
# run after a release install would fail with a silent ClassNotFoundException deep inside
# WorkManager (no crash the user would see — just alerts that never fire).
-keep class com.yugma.terrawatch.alerts.AlertDigestWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}
