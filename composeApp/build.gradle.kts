@file:OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

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
            implementation(projects.core.model)
            implementation(projects.core.network)
            implementation(projects.core.database)
            implementation(projects.core.data)
            // Task 8: HomeScreen wraps in TerraTheme and QuakeMap's Android actual sources pin
            // colors from magnitudeColor(band) — both live in core:ui.
            implementation(projects.core.ui)
            implementation(libs.koin.core)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.kotlinx.coroutines.core)
            // Task 4 (Plan 3): nav/AppNav.kt's NavHost + bottom NavigationBar/NavigationRail
            // tab-switching. See libs.versions.toml's navigationCompose entry for the version
            // choice (2.9.2, latest stable) and the wasmJs-target verification.
            implementation(libs.navigation.compose)
            // AppModule.kt references HttpClient directly (engine-agnostic type) in commonMain,
            // which compiles for wasmJs too — core:network only exposes ktor-client-core as
            // `implementation`, so it doesn't leak through projects.core.network transitively.
            implementation(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.ktor.client.websockets)
            // HOTFIX (Task 6 follow-up): moved here from commonMain. maplibre-compose has no
            // wasmJs target and its desktop artifact requires a JDK 25 runtime this project's
            // Gradle toolchain doesn't provide — leaving it in commonMain (or jvmMain) broke
            // :composeApp:jvmTest (JDK25-only class files vs jvmToolchain(17)) and
            // :composeApp:wasmJsBrowserDistribution (no matching variant) outright. Spike decision
            // is Android-only live map for now; see docs/superpowers/plans/plan-2-spike-maplibre.md.
            implementation(libs.maplibre.compose)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.websockets)
            implementation(libs.coroutines.swing)
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
        versionCode = 1
        versionName = "0.1.0"
        // Task 13: required for connectedDebugAndroidTest to resolve a runner at all — AGP's
        // default is the deprecated android.test.InstrumentationTestRunner otherwise.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
