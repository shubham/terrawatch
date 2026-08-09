import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

kotlin {
    androidTarget()
    jvm()
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(projects.core.network)
            api(projects.core.database)
            implementation(libs.kotlinx.coroutines.core)
            // Task 5 (Plan 3): HistoryPager's year-filter boundary math (Dec-31/Jan-1 UTC instants)
            // needs real calendar arithmetic (leap years etc.) — reuses the same kotlinx-datetime
            // library core:ui's Formats.kt already depends on for the identical reason, rather than
            // hand-rolling day-of-year math a second time.
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.turbine)
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.ktor.client.mock)
        }
    }
}

android {
    namespace = "com.yugma.terrawatch.data"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
