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
    application { mainClass = "com.yugma.terrawatch.MainKt" }
}
