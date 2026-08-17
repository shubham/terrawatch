@file:OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// Plan 4 Task 6: RevenueCat + AdMob local config. `monetization.properties` is gitignored (no real
// RevenueCat/AdMob account exists yet — both are USER-GATED prerequisites, plan's own Global
// Constraints); `monetization.properties.example` (committed) is the template. Every checked-in
// build must compile and run correctly with this file simply ABSENT — that's this repo's actual
// state throughout Task 6, not a hypothetical to guard against speculatively.
private val monetizationProperties = Properties().apply {
    val file = rootProject.file("composeApp/monetization.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// Google's own official AdMob TEST app id (developers.google.com/admob/android/test-ads) — used
// whenever ADMOB_APP_ID is absent/blank, so the manifest's own
// `com.google.android.gms.ads.APPLICATION_ID` meta-data (AndroidManifest.xml, below) is NEVER a
// blank/invalid string: MobileAds throws at initialize time without a well-formed app id, and this
// substitution happens once, at BUILD time, specifically so that can never happen. REVENUECAT_API_KEY
// and ADMOB_BANNER_UNIT are deliberately NOT defaulted here — both stay raw/possibly-blank strings
// read at RUNTIME instead (`KoinBootstrap.android.kt` / `BannerAdSlot.android.kt`), because their
// absent/blank-vs-configured DECISION is a pure, TDD'd function
// (`revenueCatKeyIsConfigured`/`TEST_BANNER_AD_UNIT_ID`'s own fallback), not something this build
// script should bake in ahead of time.
private val TEST_ADMOB_APP_ID = "ca-app-pub-3940256099942544~3347511713"

kotlin {
    androidTarget()
    jvm()
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser(); binaries.executable() }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            // feat/feed-visit-ux, "since-last-visit summary": FeedSheet's new visit-summary banner
            // is this codebase's first AnimatedVisibility/fadeIn/slideInVertically usage (grepped
            // first - EVIDENCE INTEGRITY - zero existing call sites) - compose.animation-core alone
            // (already implied transitively by the compose.animation.core imports FeedSheet.kt's
            // pulsing LIVE dot already uses) does not contain AnimatedVisibility/EnterTransition/
            // ExitTransition, which live in the separate compose.animation artifact. Declared
            // explicitly here rather than relying on an undocumented transitive edge through
            // compose.material3's own internal dependency graph, matching this file's established
            // "declare what you actually use" convention for every other per-target dependency.
            implementation(compose.animation)
            implementation(projects.core.model)
            implementation(projects.core.network)
            implementation(projects.core.database)
            implementation(projects.core.data)
            // Task 8: HomeScreen wraps in TerraTheme and QuakeMap's Android actual sources pin
            // colors from magnitudeColor(band) — both live in core:ui.
            implementation(projects.core.ui)
            // Plan 4 Task 6: EntitlementsProvider/AlwaysFreeEntitlements (AppModule.kt's DI wiring,
            // SettingsViewModel's mirrored isPlusActive) and BannerAdSlot/adSlotVisible (AppNav.kt's
            // ad-slot gate) — both compile on all 3 targets (androidTarget/jvm/wasmJs), matching
            // every other core:* module this app already depends on from commonMain.
            implementation(projects.core.monetization)
            implementation(projects.core.ads)
            implementation(libs.koin.core)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.kotlinx.coroutines.core)
            // Task 5 (Plan 3): HistoryViewModel.groupByMonth's UTC month bucketing needs the same
            // kotlinx-datetime core:data's own HistoryPager (year-filter boundary math) and core:ui's
            // Formats.kt already depend on for the identical reason.
            implementation(libs.kotlinx.datetime)
            // Task 4 (Plan 3): nav/AppNav.kt's NavHost + bottom NavigationBar/NavigationRail
            // tab-switching. See libs.versions.toml's navigationCompose entry for the version
            // choice (2.9.2, latest stable) and the wasmJs-target verification.
            implementation(libs.navigation.compose)
            // AppModule.kt references HttpClient directly (engine-agnostic type) in commonMain,
            // which compiles for wasmJs too — core:network only exposes ktor-client-core as
            // `implementation`, so it doesn't leak through projects.core.network transitively.
            implementation(libs.ktor.client.core)
            // Plan 4 Task 3: OnboardingScreen/SettingsScreen re-read notification-permission state
            // on ON_RESUME (system Settings can change it while this app is merely paused, not
            // restarted) via LocalLifecycleOwner + a Lifecycle.Event observer — see
            // notifications/NotificationPermissionState.kt's callers.
            implementation(libs.androidx.lifecycle.runtime.compose)
            // Plan 4 Task 4 (c): AppNav.kt/HomeScreen.kt's ONE shared adaptive-layout source of
            // truth (currentWindowAdaptiveInfo().windowSizeClass) — replaces the two independent
            // BoxWithConstraints-measured raw-width breakpoints that used to disagree in the
            // 900-980dp band (see home/LayoutMode.kt's own kdoc for the dead zone this closes). See
            // libs.versions.toml's material3Adaptive entry for the version-pin verification.
            implementation(libs.material3.adaptive)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            // feat/feed-visit-ux, "Splash app name": SplashScreen.installSplashScreen(this) in
            // MainActivity, backing Theme.App.Starting (values/themes.xml)'s
            // windowSplashScreenBrandingImage - the actual wordmark. AndroidX compat back to
            // minSdk 26 (the platform SplashScreen API itself only exists on API 31+); androidMain-
            // only, no jvm/wasmJs equivalent, same per-target-explicit-dependency convention this
            // file already follows for its other Android-only libraries below.
            implementation(libs.androidx.core.splashscreen)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.ktor.client.websockets)
            // HOTFIX (Task 6 follow-up): moved here from commonMain. maplibre-compose has no
            // wasmJs target and its desktop artifact requires a JDK 25 runtime this project's
            // Gradle toolchain doesn't provide — leaving it in commonMain (or jvmMain) broke
            // :composeApp:jvmTest (JDK25-only class files vs jvmToolchain(17)) and
            // :composeApp:wasmJsBrowserDistribution (no matching variant) outright. Spike decision
            // is Android-only live map for now; see docs/superpowers/plans/plan-2-spike-maplibre.md.
            implementation(libs.maplibre.compose)
            // Plan 4 Task 3: AlertDigestWorker + its periodic/one-time enqueue calls — WorkManager
            // has no jvm/wasmJs equivalent (spec §7's own platform table: background work is
            // Android-only in v1), so this is androidMain-only, no expect/actual ceremony needed
            // for the worker class itself (only the thin scheduling/state-query surface UI screens
            // touch is expect/actual — see AlertDigestScheduler.kt).
            implementation(libs.androidx.work.runtime.ktx)
            // Plan 4 Task 6: KoinBootstrap.android.kt calls MobileAds.initialize(...) directly —
            // core:ads' own play-services-ads dependency is `implementation`, not `api` (same
            // per-target-explicit-dependency convention this file's own comments already document
            // for ktor-client-websockets/okhttp above), so it doesn't leak transitively here.
            // SAME exclusion as core:ads/build.gradle.kts's identical declaration, for the identical
            // reason (see that file's own kdoc for the real ListenableFuture/Guava compile failure
            // this works around) — this is a SEPARATE Gradle dependency declaration on a different
            // module, so the exclusion must be repeated here rather than assumed to apply once.
            implementation("com.google.android.gms:play-services-ads:${libs.versions.playServicesAds.get()}") {
                exclude(group = "androidx.privacysandbox.ads", module = "ads-adservices-java")
                exclude(group = "androidx.privacysandbox.ads", module = "ads-adservices")
            }
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.websockets)
            implementation(libs.coroutines.swing)
        }
        // Task 9 (Plan 3): web enablement — the Js engine (browser fetch/XHR + native WebSocket
        // under the hood) is what main.kt's real (no longer WebPlaceholder) startKoin() call now
        // builds an HttpClient on. Same "composeApp needs its own explicit per-target ktor deps"
        // reasoning as jvmMain/androidMain above: core:network's own ktor-client-websockets is an
        // `implementation` dependency there, so it does NOT leak transitively through
        // projects.core.data's `api(projects.core.network)` — composeApp's AppModule.kt/main.kt
        // construct their own HttpClient directly and need the engine + WebSockets plugin on their
        // own classpath regardless.
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
            implementation(libs.ktor.client.websockets)
        }
        // Real QuakeRepository construction needs the JVM-only SQLDelight JDBC driver, so this
        // suite lives in jvmTest rather than commonTest (commonTest compiles for androidTarget and
        // wasmJs too, neither of which has JdbcSqliteDriver on its classpath).
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
            implementation(libs.ktor.client.mock)
            implementation(libs.sqldelight.sqlite.driver)
        }
        // Task 13: instrumented (on-device/emulator) Compose UI tests. AGP+KGP source-set naming
        // for a KMP androidTarget is "androidInstrumentedTest" (connectedDebugAndroidTest), not the
        // classic single-platform-project "androidTest" — verified against this project's actual
        // Kotlin 2.2.20 + AGP 8.10.1 combo by compiling against it (see task-13-report.md).
        // compose.uiTestJUnit4 (NOT compose.uiTest — that one only pulls org.jetbrains.compose.ui:
        // ui-test, which resolves the base semantics-tree test API like onNodeWithText/
        // captureToImage but NOT createComposeRule()/createAndroidComposeRule(), which live in the
        // JUnit4-rule artifact; found the getUiTestJUnit4() accessor by inspecting the Compose
        // Multiplatform Gradle plugin jar after compose.uiTest alone failed with "Unresolved
        // reference: createComposeRule" — see task-13-report.md) is the Compose Multiplatform
        // plugin's own dependency accessor for that JUnit4 rule artifact, kept version-aligned with
        // whatever compose.ui this project already resolves instead of a second, independently
        // pinned version that could drift and clash — it resolves to the real
        // androidx.compose.ui:ui-test-junit4 (-> ui-test-android transitively) for the android
        // target. ui-test-manifest is deliberately NOT declared here — see the plain
        // `debugImplementation` block below this kotlin{} block for why.
        androidInstrumentedTest.dependencies {
            implementation(compose.uiTestJUnit4)
            implementation(libs.androidx.test.runner)
            // HomeFlowTest (DI-backed) builds a real QuakeRepository the same way
            // HomeViewModelTest's jvmTest fakes do, but on-device: MockEngine stands in for the
            // network, and a throwaway in-memory AndroidSqliteDriver (NOT the app's own
            // DriverFactory/"terrawatch.db" file, which this same device's manual-QA passes write
            // real quakes into) keeps this test's data isolated from anything else on the device.
            implementation(libs.ktor.client.mock)
            implementation(libs.sqldelight.android.driver)
            // review-round-3: ComponentsTest's new back-press dismissal regression needs a REAL
            // system back-key dispatch, not a direct SheetState/dispatcher method call — DetailSheet's
            // ModalBottomSheet renders into its own Dialog window with its own OnBackInvokedDispatcher
            // (see ModalBottomSheet.android.kt's ModalBottomSheetDialogWrapper), separate from the
            // host Activity's — so only a real injected back event reaches the right callback the
            // same way a device's physical back press/gesture would. Espresso.pressBack() does
            // exactly that via the instrumentation/UiAutomation input path. Not already on this
            // classpath despite the jar existing in the local Gradle cache (that copy came from an
            // unrelated transitive graph, not this source set) — added explicitly rather than
            // relying on an incidental transitive resolution.
            implementation(libs.androidx.test.espresso.core)
        }
    }
}

android {
    namespace = "com.yugma.terrawatch"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.yugma.terrawatch"
        minSdk = 26
        targetSdk = 36
        // Plan 4 Task 1: R8 release hardening milestone. KEEP IN SYNC BY HAND with
        // SettingsScreen.kt's APP_VERSION const (that file's own kdoc carries the same reminder) —
        // no BuildConfig surface reaches commonMain, so these two literals are the only source of
        // truth and must be bumped together.
        // CI releases (release.yml) pass -PciVersionCode so every Play upload gets a unique,
        // monotonically-increasing code without a commit; local builds keep the literal.
        versionCode = (project.findProperty("ciVersionCode") as String?)?.toIntOrNull() ?: 2
        versionName = "0.9.0"
        // Task 13: required for connectedDebugAndroidTest to resolve a runner at all — AGP's
        // default is the deprecated android.test.InstrumentationTestRunner otherwise.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Plan 4 Task 6: 3 manifest placeholders (AndroidManifest.xml, below) sourced from
        // `monetizationProperties` above. `admobAppId` always resolves to a well-formed value (real
        // or TEST) — see TEST_ADMOB_APP_ID's own kdoc for why that one specifically can't be left
        // blank. `revenueCatApiKey`/`admobBannerUnit` are left as their raw (possibly-blank) config
        // value on purpose — `RevenueCatEntitlements`'s gate and `BannerAdSlot`'s TEST-id fallback
        // both read them at RUNTIME via manifest metadata (not BuildConfig — this project enables
        // no `buildFeatures.buildConfig` anywhere, see `QuakeMap.android.kt`'s own kdoc for that
        // established precedent, and Task 6 doesn't need to break it: manifest meta-data reaches
        // BOTH this module's own androidMain AND core:ads/core:monetization's separate androidMain
        // source sets via the same merged-manifest mechanism, where a per-module BuildConfig class
        // would only ever be visible inside the one module that generated it).
        manifestPlaceholders["admobAppId"] =
            monetizationProperties.getProperty("ADMOB_APP_ID")?.takeIf { it.isNotBlank() } ?: TEST_ADMOB_APP_ID
        manifestPlaceholders["revenueCatApiKey"] = monetizationProperties.getProperty("REVENUECAT_API_KEY").orEmpty()
        manifestPlaceholders["admobBannerUnit"] = monetizationProperties.getProperty("ADMOB_BANNER_UNIT").orEmpty()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // APK-only size control: libmaplibre.so alone is ~56MB across 4 ABIs (measured 2026-08-17;
    // dex is ~10MB), so a universal APK lands at 64MB. Per-ABI split APKs cut a shareable
    // arm64-v8a build to ~23MB. The Play path is unaffected: bundleRelease ignores this block
    // and always carries every ABI — Play then serves each device only its own (~15-18MB
    // download). isUniversalApk keeps the everything-APK available for emulators/x86.
    // Splits + bundleRelease conflict in AGP ("Sequence contains more than one matching
    // element" in buildReleasePreBundle), so splits switch off for bundle invocations —
    // the AAB must carry every ABI anyway.
    val isBundleBuild = gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }
    splits {
        abi {
            isEnable = !isBundleBuild
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }
    // Real upload-key signing when the CI env provides a keystore (release.yml decodes it from a
    // GitHub secret); anything else — local builds included — falls back to debug signing so the
    // release build type stays buildable/installable for R8 smoke work without a signing identity.
    // The upload key signs what goes TO Play; Play App Signing re-signs what users download.
    val ciKeystorePath = System.getenv("CI_KEYSTORE_PATH")
    if (ciKeystorePath != null) {
        signingConfigs.create("upload") {
            storeFile = file(ciKeystorePath)
            storePassword = System.getenv("CI_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("CI_KEY_ALIAS")
            keyPassword = System.getenv("CI_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (ciKeystorePath != null) {
                signingConfigs.getByName("upload")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
}

compose.desktop {
    application { mainClass = "com.yugma.terrawatch.MainKt" }
}

// Task 13: ui-test-manifest MUST be a dependency of the app's own DEBUG build (the classic AGP
// "debugImplementation" configuration), NOT the androidInstrumentedTest source set — its whole job
// is contributing a manifest fragment (a placeholder `androidx.activity.ComponentActivity`
// declaration) that createComposeRule()'s ActivityScenario.launch() needs to find registered
// inside the app-under-test's OWN package (com.yugma.terrawatch). Declaring it as an
// androidInstrumentedTest dependency instead put that Activity in the separate test package
// (com.yugma.terrawatch.test) — found by actually running connectedDebugAndroidTest first and
// reading the real failure: "Intent in process com.yugma.terrawatch resolved to different process
// com.yugma.terrawatch.test" (Instrumentation.startActivitySync) on all 9 tests. This plain
// `dependencies {}` block (AGP's classic per-variant configurations, e.g. "debugImplementation")
// coexists fine with the kotlin{} sourceSets DSL above — both ultimately configure the same
// underlying Gradle configurations for the android target.
dependencies {
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
