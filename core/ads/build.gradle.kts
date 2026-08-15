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
            implementation(compose.ui)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // Plan 4 Task 6: play-services-ads (AdMob), androidMain-only — jvm/wasmJs actuals render
        // nothing at all (Android-only runtime scope directive, Plan 4 Task 4; spec §7's own
        // platform table: "Ads: Android [only]"), so neither needs this on its classpath. See
        // libs.versions.toml's playServicesAds entry for the version-pin verification.
        androidMain.dependencies {
            // Plain GAV-string notation (version still sourced from libs.versions.toml's own
            // playServicesAds entry, no duplicated/hardcoded number) rather than the
            // `libs.play.services.ads` catalog accessor directly: this Gradle/AGP combination's
            // Kotlin DSL doesn't accept an exclude {} configuration block on the catalog accessor's
            // `Provider<MinimalExternalModuleDependency>` type (tried first — failed with "Type
            // mismatch: inferred type is Provider<MinimalExternalModuleDependency>"; `.get()` was
            // tried next — failed with "Minimal dependencies are immutable", since `.get()` still
            // returns an immutable snapshot that `exclude` can't mutate). The plain-String overload
            // is the one that actually supports per-dependency `exclude` configuration here.
            implementation("com.google.android.gms:play-services-ads:${libs.versions.playServicesAds.get()}") {
                // REAL BUILD FAILURE this excludes (not speculative): play-services-ads pulls
                // androidx.privacysandbox.ads:ads-adservices(-java), which depends on FULL
                // com.google.guava:guava:31.1-android. Guava's own published metadata then forces
                // com.google.guava:listenablefuture (the tiny interface-only shim
                // androidx.work:work-runtime's ListenableFuture-returning APIs need — see
                // AlertDigestScheduler.android.kt) to the deliberately EMPTY
                // "9999.0-empty-to-avoid-conflict-with-guava" variant everywhere AGP's cross-
                // configuration consistency check reaches — including composeApp's COMPILE
                // classpath, where full guava itself is only ever a RUNTIME (implementation)
                // dependency of ads-adservices-java, never exposed to compile-time consumers.
                // Net effect: the empty shim wins at compile time with nothing left to actually
                // supply the class, and `:composeApp:compileDebugKotlinAndroid` fails with "Cannot
                // access class 'ListenableFuture'" in AlertDigestScheduler.android.kt — reproduced
                // directly (this exact error) before this exclusion was added, confirmed gone after.
                // This app has no ad-attribution/Privacy Sandbox reporting need at all (spec §8:
                // one plain anchored banner, no conversion tracking), so both modules are safe to
                // drop entirely rather than working around the conflict with a forced version.
                exclude(group = "androidx.privacysandbox.ads", module = "ads-adservices-java")
                exclude(group = "androidx.privacysandbox.ads", module = "ads-adservices")
            }
        }
    }
}

android {
    namespace = "com.yugma.terrawatch.ads"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
