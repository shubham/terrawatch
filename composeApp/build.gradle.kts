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
            implementation(libs.koin.core)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.kotlinx.coroutines.core)
            // SPIKE (Task 6): maplibre-compose has no wasmJs target (confirmed against v0.14.0
            // source and Maven Central — it publishes android/desktop/ios*/js artifacts only, no
            // maplibre-compose-wasm-js). Declaring it here, in commonMain, is intentional: it's
            // exactly what the spike needs to prove that a naive commonMain dependency breaks the
            // wasmJs target outright. See docs/superpowers/plans/plan-2-spike-maplibre.md.
            implementation(libs.maplibre.compose)
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
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.websockets)
            implementation(libs.coroutines.swing)
            // SPIKE (Task 6): desktop needs one native-renderer runtime alongside the library
            // itself (see "Set up Desktop (JVM)" in the maplibre-compose docs). This machine is
            // macOS arm64, hence the Metal backend; a real multi-OS desktop target would need to
            // select the matching runtime per build host (Vulkan for Linux/Windows).
            runtimeOnly("org.maplibre.compose:maplibre-compose-runtime-metal-macos-arm64:${libs.versions.maplibreCompose.get()}")
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
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.desktop {
    application {
        mainClass = "com.yugma.terrawatch.MainKt"
        // SPIKE (Task 6): required per maplibre-compose's desktop setup docs — MapLibre Native's
        // FFI binding makes FFM downcalls, which need explicit native-access on JDK 25+ modules.
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
    }
}
