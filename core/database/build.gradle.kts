import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    androidTarget()
    jvm()
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        androidMain.dependencies { implementation(libs.sqldelight.android.driver) }
        jvmMain.dependencies { implementation(libs.sqldelight.sqlite.driver) }
        // wasmJs web-worker driver excluded for v1: it needs generateAsync=true at link time,
        // which we are not adopting yet (see sqldelight { } block below). Web persistence lands
        // in Plan 3; DriverFactory.wasmJs.kt throws NotImplementedError until then.
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
    }
}

android {
    namespace = "com.yugma.terrawatch.database"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

sqldelight {
    databases {
        create("TerraWatchDb") {
            packageName.set("com.yugma.terrawatch.database")
            generateAsync.set(false)
        }
    }
}
