import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    androidTarget()
    jvm()
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            api(projects.core.model)
            // Task 9: StatusShield's public signature takes a PillStatus (core:data's pure pill
            // logic) directly, so consumers of core:ui's StatusShield need that type on their
            // compile classpath too — api, not implementation, same reasoning as core.model above.
            // No cycle: core:data depends on model/network/database only, never on core:ui.
            api(projects.core.data)
            implementation(libs.kotlinx.datetime)
        }
        // Formats.kt's tests live in jvmTest (not commonTest): the Compose Multiplatform plugin's
        // per-target compilations and commonTest's kotlin-test resolution can fight in a KMP+compose
        // module (android/wasmJs test compilations wanting different kotlin-test artifacts than the
        // plain JVM one). jvmTest already depends on commonTest via the default hierarchy template,
        // so declaring kotlin("test") once here is enough for FormatsTest to see kotlin.test.*.
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.yugma.terrawatch.ui"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
