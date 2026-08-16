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
            // Fix Round 1 (Review 1, BLOCKER-1): confirmed via SqlDelightDatabase's own gradle-plugin
            // class (app.cash.sqldelight:gradle-plugin:2.1.0 jar, decompiled from the gradle cache —
            // getVerifyMigrations(): Property<Boolean> is a real DSL property) that this wires a
            // VerifyMigrationTask, which fails the build if replaying every .sqm migration from
            // scratch doesn't reproduce byte-for-byte the same schema the .sq files describe directly
            // — exactly the safety net that would have caught 1.sqm silently drifting from
            // FavoritePlace.sq's own CREATE TABLE, now that this project has its first migration.
            verifyMigrations.set(true)
            // Required for verifyMigrations to actually run (not just declare) — confirmed against
            // SqlDelightDatabase.addMigrationTasks' own bytecode: it only registers the
            // GenerateSchemaTask/wires a real check when `schemaOutputDirectory.isPresent()`; without
            // this, `verifyCommonMainTerraWatchDbMigration` (a real dependency of `check` the instant
            // verifyMigrations is true) fails outright with "Verifying a migration requires a database
            // file to be present" — a worse state than not enabling the flag at all. This directory
            // holds the generated schema snapshot (`generateCommonMainTerraWatchDbSchema`) that gets
            // committed and diffed against on every future migration.
            schemaOutputDirectory.set(project.file("src/commonMain/sqldelight/databases"))
        }
    }
}
