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
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // Plan 4 Task 6: RevenueCat's purchases-kmp-core, ANDROID-GATED per this task's own brief —
        // jvm/wasmJs never construct RevenueCatEntitlements at all (both stay on
        // AlwaysFreeEntitlements permanently, Android-only runtime scope directive), so neither
        // needs this dependency on its own classpath. See libs.versions.toml's
        // revenueCatPurchasesKmp entry for the version-pin verification.
        androidMain.dependencies {
            implementation(libs.revenuecat.purchases.kmp.core)
        }
    }
}

android {
    namespace = "com.yugma.terrawatch.monetization"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
