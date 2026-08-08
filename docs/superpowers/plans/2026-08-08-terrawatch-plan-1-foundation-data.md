# TerraWatch Plan 1: Foundation + Data Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Kotlin Multiplatform project that fetches, parses, dedupes, stores, and streams earthquake data from USGS + EMSC, proven by a thin "live feed list" screen on Android, Desktop, and Web, with green CI.

**Architecture:** Multi-module KMP (`core:model → core:network/core:database → core:data`), Compose Multiplatform shell in `composeApp`. All logic in Compose-free core modules, TDD with plain JVM tests. DB is the single source of truth; network writers upsert; UI collects Flows.

**Tech Stack:** Kotlin 2.2.20, Compose Multiplatform 1.9.0, Ktor 3.2.2, SQLDelight 2.1.0, kotlinx-serialization 1.9.0, kotlinx-coroutines 1.10.2, Koin 4.1.0, Turbine 1.2.1 (test), AGP 8.10.1, JDK 17.

## Global Constraints

- Package root: `com.yugma.terrawatch`. Module namespaces: `com.yugma.terrawatch.<module>`.
- Targets per core module: `androidTarget()`, `jvm()`, `wasmJs { browser() }`. composeApp adds the same three.
- Android: `minSdk = 26`, `compileSdk = 36`, `targetSdk = 36`.
- Version pins above are known-good floors. If a pin fails to resolve at execution time, bump that library to its latest stable and note the bump in the commit message. Never downgrade.
- TDD non-negotiable in `core:*`: write test → run red → implement → run green → commit. Run module tests via `./gradlew :core:<name>:jvmTest`.
- No UI logic in core modules; nothing in `core:*` (except `core:ui` later) may depend on Compose.
- Git identity is repo-local (personal). Every commit message body ends with:
  `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`
- APIs used are keyless and free: USGS GeoJSON feeds + FDSN query, EMSC WebSocket. Always send ETag (`If-None-Match`) on feed polls.
- All timestamps stored as epoch millis UTC (`Long`). All distances in kilometers (`Double`).

---

### Task 1: Gradle scaffold — root, version catalog, composeApp hello-world on 3 targets

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts` (root)
- Create: `gradle/libs.versions.toml`
- Create: `gradle.properties`
- Create: `composeApp/build.gradle.kts`
- Create: `composeApp/src/commonMain/kotlin/com/yugma/terrawatch/App.kt`
- Create: `composeApp/src/androidMain/kotlin/com/yugma/terrawatch/MainActivity.kt`
- Create: `composeApp/src/androidMain/AndroidManifest.xml`
- Create: `composeApp/src/jvmMain/kotlin/com/yugma/terrawatch/main.kt`
- Create: `composeApp/src/wasmJsMain/kotlin/com/yugma/terrawatch/main.kt`
- Create: `composeApp/src/wasmJsMain/resources/index.html`

**Interfaces:**
- Consumes: nothing (first task)
- Produces: buildable project; `App()` composable that later tasks replace; version catalog aliases used by every subsequent build file (`libs.plugins.kotlinMultiplatform`, `libs.plugins.androidLibrary`, `libs.plugins.composeMultiplatform`, `libs.plugins.composeCompiler`, `libs.plugins.kotlinSerialization`, `libs.plugins.sqldelight`, `libs.ktor.*`, `libs.sqldelight.*`, `libs.koin.core`, `libs.kotlinx.*`, `libs.turbine`)

- [ ] **Step 1: Install JDK/toolchain sanity check**

Run: `java -version && ./gradlew --version 2>/dev/null || gradle --version`
Expected: JDK 17+. If no system Gradle, install via `brew install gradle` (used once to generate the wrapper).

- [ ] **Step 2: Write `gradle/libs.versions.toml`**

```toml
[versions]
kotlin = "2.2.20"
composeMultiplatform = "1.9.0"
agp = "8.10.1"
ktor = "3.2.2"
sqldelight = "2.1.0"
koin = "4.1.0"
kotlinxSerialization = "1.9.0"
kotlinxCoroutines = "1.10.2"
kotlinxDatetime = "0.7.1"
turbine = "1.2.1"
androidxActivity = "1.10.1"
androidxLifecycle = "2.9.1"

[libraries]
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-cio = { module = "io.ktor:ktor-client-cio", version.ref = "ktor" }
ktor-client-okhttp = { module = "io.ktor:ktor-client-okhttp", version.ref = "ktor" }
ktor-client-js = { module = "io.ktor:ktor-client-js", version.ref = "ktor" }
ktor-client-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-client-websockets = { module = "io.ktor:ktor-client-websockets", version.ref = "ktor" }
ktor-serialization-kotlinx-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
ktor-client-mock = { module = "io.ktor:ktor-client-mock", version.ref = "ktor" }
sqldelight-runtime = { module = "app.cash.sqldelight:runtime", version.ref = "sqldelight" }
sqldelight-coroutines = { module = "app.cash.sqldelight:coroutines-extensions", version.ref = "sqldelight" }
sqldelight-android-driver = { module = "app.cash.sqldelight:android-driver", version.ref = "sqldelight" }
sqldelight-sqlite-driver = { module = "app.cash.sqldelight:sqlite-driver", version.ref = "sqldelight" }
sqldelight-webworker-driver = { module = "app.cash.sqldelight:web-worker-driver", version.ref = "sqldelight" }
koin-core = { module = "io.insert-koin:koin-core", version.ref = "koin" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinxSerialization" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "kotlinxCoroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "kotlinxCoroutines" }
kotlinx-datetime = { module = "org.jetbrains.kotlinx:kotlinx-datetime", version.ref = "kotlinxDatetime" }
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "androidxActivity" }
androidx-lifecycle-viewmodel = { module = "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "androidxLifecycle" }

[plugins]
kotlinMultiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
androidApplication = { id = "com.android.application", version.ref = "agp" }
androidLibrary = { id = "com.android.library", version.ref = "agp" }
composeMultiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }
composeCompiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlinSerialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
sqldelight = { id = "app.cash.sqldelight", version.ref = "sqldelight" }
```

- [ ] **Step 3: Write `settings.gradle.kts`**

```kotlin
rootProject.name = "terrawatch"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google { mavenContent { includeGroupAndSubgroups("androidx"); includeGroupAndSubgroups("com.android"); includeGroupAndSubgroups("com.google") } }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google { mavenContent { includeGroupAndSubgroups("androidx"); includeGroupAndSubgroups("com.android"); includeGroupAndSubgroups("com.google") } }
        mavenCentral()
    }
}

include(":composeApp")
// core modules join in later tasks:
// include(":core:model", ":core:network", ":core:database", ":core:data")
```

- [ ] **Step 4: Write root `build.gradle.kts` and `gradle.properties`**

```kotlin
// build.gradle.kts (root)
plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.sqldelight) apply false
}
```

```properties
# gradle.properties
org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g
org.gradle.caching=true
org.gradle.configuration-cache=true
android.useAndroidX=true
kotlin.code.style=official
```

- [ ] **Step 5: Write `composeApp/build.gradle.kts`**

```kotlin
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
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
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
```

- [ ] **Step 6: Write the hello-world App and three entry points**

```kotlin
// composeApp/src/commonMain/kotlin/com/yugma/terrawatch/App.kt
package com.yugma.terrawatch

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun App() {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text("TerraWatch")
            }
        }
    }
}
```

```kotlin
// composeApp/src/androidMain/kotlin/com/yugma/terrawatch/MainActivity.kt
package com.yugma.terrawatch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}
```

```xml
<!-- composeApp/src/androidMain/AndroidManifest.xml -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <application android:label="TerraWatch" android:theme="@android:style/Theme.Material.Light.NoActionBar">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

```kotlin
// composeApp/src/jvmMain/kotlin/com/yugma/terrawatch/main.kt
package com.yugma.terrawatch

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "TerraWatch") { App() }
}
```

```kotlin
// composeApp/src/wasmJsMain/kotlin/com/yugma/terrawatch/main.kt
package com.yugma.terrawatch

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(viewportContainerId = "app") { App() }
}
```

```html
<!-- composeApp/src/wasmJsMain/resources/index.html -->
<!doctype html>
<html lang="en">
<head><meta charset="utf-8"><title>TerraWatch</title><script src="composeApp.js"></script></head>
<body><div id="app"></div></body>
</html>
```

- [ ] **Step 7: Generate wrapper and build all targets**

Run: `gradle wrapper --gradle-version 8.14 && ./gradlew :composeApp:assembleDebug :composeApp:jvmJar :composeApp:wasmJsBrowserDistribution`
Expected: BUILD SUCCESSFUL on all three. If a version pin fails, bump per Global Constraints.

- [ ] **Step 8: Smoke-run desktop**

Run: `./gradlew :composeApp:run` (close window after it shows "TerraWatch")
Expected: window opens, renders text.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "Scaffold KMP project: composeApp hello-world on android/jvm/wasm

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: `core:model` — Quake domain model, magnitude bands, geo math

**Files:**
- Create: `core/model/build.gradle.kts`
- Create: `core/model/src/commonMain/kotlin/com/yugma/terrawatch/model/Quake.kt`
- Create: `core/model/src/commonMain/kotlin/com/yugma/terrawatch/model/MagnitudeBand.kt`
- Create: `core/model/src/commonMain/kotlin/com/yugma/terrawatch/model/Geo.kt`
- Create: `core/model/src/commonTest/kotlin/com/yugma/terrawatch/model/MagnitudeBandTest.kt`
- Create: `core/model/src/commonTest/kotlin/com/yugma/terrawatch/model/GeoTest.kt`
- Modify: `settings.gradle.kts` (add `include(":core:model")`)

**Interfaces:**
- Consumes: nothing
- Produces (used by every later task):

```kotlin
enum class Source { USGS, EMSC }
enum class QuakeStatus { AUTOMATIC, REVIEWED }

data class MagRevision(val mag: Double, val magType: String?, val atMillis: Long, val source: Source)

data class Quake(
    val id: String,                       // canonical id, e.g. "us7000abcd"
    val timeMillis: Long,                 // origin time, epoch millis UTC
    val lat: Double, val lon: Double,
    val depthKm: Double?,
    val mag: Double?, val magType: String?,
    val place: String,
    val tsunami: Boolean,
    val felt: Int?,
    val status: QuakeStatus,
    val sources: Map<Source, String>,     // agency -> agency-local id
    val revisions: List<MagRevision>,
    val updatedAtMillis: Long,
)

enum class MagnitudeBand { LOW, MODERATE, STRONG, MAJOR, UNKNOWN }
fun magnitudeBand(mag: Double?): MagnitudeBand
// null -> UNKNOWN, <3.0 -> LOW, 3.0..<4.5 -> MODERATE, 4.5..<6.0 -> STRONG, >=6.0 -> MAJOR

data class GeoPoint(val lat: Double, val lon: Double)
fun haversineKm(a: GeoPoint, b: GeoPoint): Double
```

- [ ] **Step 1: Write `core/model/build.gradle.kts` and include module**

```kotlin
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
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

android {
    namespace = "com.yugma.terrawatch.model"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
```

In `settings.gradle.kts` replace the commented include line with `include(":core:model")`.

- [ ] **Step 2: Write failing tests**

```kotlin
// core/model/src/commonTest/kotlin/com/yugma/terrawatch/model/MagnitudeBandTest.kt
package com.yugma.terrawatch.model

import kotlin.test.Test
import kotlin.test.assertEquals

class MagnitudeBandTest {
    @Test fun `null magnitude is unknown`() = assertEquals(MagnitudeBand.UNKNOWN, magnitudeBand(null))
    @Test fun `below three is low`() = assertEquals(MagnitudeBand.LOW, magnitudeBand(2.99))
    @Test fun `three is moderate`() = assertEquals(MagnitudeBand.MODERATE, magnitudeBand(3.0))
    @Test fun `just under four point five is moderate`() = assertEquals(MagnitudeBand.MODERATE, magnitudeBand(4.49))
    @Test fun `four point five is strong`() = assertEquals(MagnitudeBand.STRONG, magnitudeBand(4.5))
    @Test fun `six is major`() = assertEquals(MagnitudeBand.MAJOR, magnitudeBand(6.0))
    @Test fun `negative magnitude is low`() = assertEquals(MagnitudeBand.LOW, magnitudeBand(-0.4))
}
```

```kotlin
// core/model/src/commonTest/kotlin/com/yugma/terrawatch/model/GeoTest.kt
package com.yugma.terrawatch.model

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoTest {
    @Test fun `zero distance to self`() {
        assertEquals(0.0, haversineKm(GeoPoint(12.97, 77.59), GeoPoint(12.97, 77.59)), 1e-9)
    }
    @Test fun `bengaluru to delhi is about 1740 km`() {
        val d = haversineKm(GeoPoint(12.9716, 77.5946), GeoPoint(28.6139, 77.2090))
        assertTrue(abs(d - 1740.0) < 20.0, "got $d")
    }
    @Test fun `antimeridian crossing is short not long`() {
        val d = haversineKm(GeoPoint(0.0, 179.5), GeoPoint(0.0, -179.5))
        assertTrue(d < 200.0, "got $d")
    }
    @Test fun `pole to pole is half circumference`() {
        val d = haversineKm(GeoPoint(90.0, 0.0), GeoPoint(-90.0, 0.0))
        assertTrue(abs(d - 20015.0) < 30.0, "got $d")
    }
}
```

- [ ] **Step 3: Run tests, verify they fail to compile (functions don't exist)**

Run: `./gradlew :core:model:jvmTest`
Expected: FAIL — unresolved references `magnitudeBand`, `haversineKm`, `GeoPoint`.

- [ ] **Step 4: Implement model files**

```kotlin
// core/model/src/commonMain/kotlin/com/yugma/terrawatch/model/Quake.kt
package com.yugma.terrawatch.model

enum class Source { USGS, EMSC }
enum class QuakeStatus { AUTOMATIC, REVIEWED }

data class MagRevision(val mag: Double, val magType: String?, val atMillis: Long, val source: Source)

data class Quake(
    val id: String,
    val timeMillis: Long,
    val lat: Double,
    val lon: Double,
    val depthKm: Double?,
    val mag: Double?,
    val magType: String?,
    val place: String,
    val tsunami: Boolean,
    val felt: Int?,
    val status: QuakeStatus,
    val sources: Map<Source, String>,
    val revisions: List<MagRevision>,
    val updatedAtMillis: Long,
)
```

```kotlin
// core/model/src/commonMain/kotlin/com/yugma/terrawatch/model/MagnitudeBand.kt
package com.yugma.terrawatch.model

enum class MagnitudeBand { LOW, MODERATE, STRONG, MAJOR, UNKNOWN }

fun magnitudeBand(mag: Double?): MagnitudeBand = when {
    mag == null -> MagnitudeBand.UNKNOWN
    mag < 3.0 -> MagnitudeBand.LOW
    mag < 4.5 -> MagnitudeBand.MODERATE
    mag < 6.0 -> MagnitudeBand.STRONG
    else -> MagnitudeBand.MAJOR
}
```

```kotlin
// core/model/src/commonMain/kotlin/com/yugma/terrawatch/model/Geo.kt
package com.yugma.terrawatch.model

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI

data class GeoPoint(val lat: Double, val lon: Double)

private const val EARTH_RADIUS_KM = 6371.0088
private fun Double.toRadians() = this * PI / 180.0

fun haversineKm(a: GeoPoint, b: GeoPoint): Double {
    val dLat = (b.lat - a.lat).toRadians()
    val dLon = (b.lon - a.lon).toRadians()
    val s = sin(dLat / 2) * sin(dLat / 2) +
        cos(a.lat.toRadians()) * cos(b.lat.toRadians()) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_KM * asin(sqrt(s))
}
```

- [ ] **Step 5: Run tests, verify green**

Run: `./gradlew :core:model:jvmTest`
Expected: PASS, all tests.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Add core:model — Quake domain model, magnitude bands, haversine

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: `core:network` — USGS feed parser against a real fixture

**Files:**
- Create: `core/network/build.gradle.kts`
- Create: `core/network/src/commonMain/kotlin/com/yugma/terrawatch/network/UsgsFeedParser.kt`
- Create: `core/network/src/commonTest/kotlin/com/yugma/terrawatch/network/UsgsFeedParserTest.kt`
- Create: `core/network/src/commonTest/resources/fixtures/usgs_all_hour.json` (recorded live)
- Modify: `settings.gradle.kts` (add `include(":core:network")`)

**Interfaces:**
- Consumes: `Quake`, `Source`, `QuakeStatus`, `MagRevision` from `core:model`
- Produces:

```kotlin
object UsgsFeedParser {
    fun parse(geojson: String): List<Quake>   // throws SerializationException on malformed input
}
// Mapping rules: id = "properties.ids" first non-empty token trimmed of commas, else feature "id"
// mag nullable; depthKm = geometry.coordinates[2]; tsunami = properties.tsunami == 1
// status: "reviewed" -> REVIEWED else AUTOMATIC
// sources = mapOf(Source.USGS to feature.id); revisions = listOf(MagRevision(mag,...)) when mag != null
// updatedAtMillis = properties.updated
```

- [ ] **Step 1: Module build file + include**

```kotlin
// core/network/build.gradle.kts
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    androidTarget()
    jvm()
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies { implementation(libs.ktor.client.okhttp) }
        jvmMain.dependencies { implementation(libs.ktor.client.cio) }
        wasmJsMain.dependencies { implementation(libs.ktor.client.js) }
    }
}

android {
    namespace = "com.yugma.terrawatch.network"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
```

Add `include(":core:network")` to settings.

- [ ] **Step 2: Record the live fixture**

Run:
```bash
mkdir -p core/network/src/commonTest/resources/fixtures
curl -s https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/all_hour.geojson \
  -o core/network/src/commonTest/resources/fixtures/usgs_all_hour.json
python3 -c "import json;d=json.load(open('core/network/src/commonTest/resources/fixtures/usgs_all_hour.json'));print('features:',len(d['features']))"
```
Expected: prints a feature count ≥ 1. If 0 (quiet hour), re-record from `all_day.geojson` into the same filename.

Note: JVM tests read fixtures via classpath. Add a tiny helper in the test file (`readFixture`) using `Thread.currentThread().contextClassLoader` — tests for parsers run on jvmTest only is acceptable; keep the test in `commonTest` but gate fixture loading with an `expect/actual` **only if needed**. Simplest working route: put the test in `core/network/src/jvmTest/` instead of commonTest. Do that.

- [ ] **Step 3: Write failing parser test (in `jvmTest`)**

```kotlin
// core/network/src/jvmTest/kotlin/com/yugma/terrawatch/network/UsgsFeedParserTest.kt
package com.yugma.terrawatch.network

import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

private fun readFixture(name: String): String =
    Thread.currentThread().contextClassLoader!!.getResource("fixtures/$name")!!.readText()

class UsgsFeedParserTest {
    private val quakes = UsgsFeedParser.parse(readFixture("usgs_all_hour.json"))

    @Test fun `parses every feature in the fixture`() {
        assertTrue(quakes.isNotEmpty())
    }
    @Test fun `every quake has usgs source id`() {
        quakes.forEach { q -> assertNotNull(q.sources[Source.USGS], "missing USGS id on ${q.id}") }
    }
    @Test fun `coordinates are sane`() {
        quakes.forEach { q ->
            assertTrue(q.lat in -90.0..90.0, "lat ${q.lat}")
            assertTrue(q.lon in -180.0..180.0, "lon ${q.lon}")
        }
    }
    @Test fun `times are epoch millis after 2020`() {
        quakes.forEach { q -> assertTrue(q.timeMillis > 1_577_836_800_000, "time ${q.timeMillis}") }
    }
    @Test fun `status maps reviewed or automatic`() {
        quakes.forEach { q -> assertTrue(q.status == QuakeStatus.REVIEWED || q.status == QuakeStatus.AUTOMATIC) }
    }
    @Test fun `malformed json throws`() {
        kotlin.test.assertFailsWith<Exception> { UsgsFeedParser.parse("{not json") }
    }
}
```

- [ ] **Step 4: Run red**

Run: `./gradlew :core:network:jvmTest`
Expected: FAIL — `UsgsFeedParser` unresolved.

- [ ] **Step 5: Implement parser**

```kotlin
// core/network/src/commonMain/kotlin/com/yugma/terrawatch/network/UsgsFeedParser.kt
package com.yugma.terrawatch.network

import com.yugma.terrawatch.model.MagRevision
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

object UsgsFeedParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(geojson: String): List<Quake> {
        val root = json.parseToJsonElement(geojson).jsonObject
        return root.getValue("features").jsonArray.mapNotNull { f -> feature(f.jsonObject) }
    }

    private fun feature(f: JsonObject): Quake? {
        val props = f["properties"]?.jsonObject ?: return null
        val coords = f["geometry"]?.jsonObject?.get("coordinates")?.jsonArray ?: return null
        if (coords.size < 2) return null
        val featureId = f["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val ids = props["ids"]?.jsonPrimitive?.contentOrNull
        val canonicalId = ids?.split(',')?.firstOrNull { it.isNotBlank() }?.trim() ?: featureId
        val mag = props["mag"]?.jsonPrimitive?.doubleOrNull
        val time = props["time"]?.jsonPrimitive?.longOrNull ?: return null
        val updated = props["updated"]?.jsonPrimitive?.longOrNull ?: time
        return Quake(
            id = canonicalId,
            timeMillis = time,
            lat = coords[1].jsonPrimitive.doubleOrNull ?: return null,
            lon = coords[0].jsonPrimitive.doubleOrNull ?: return null,
            depthKm = coords.getOrNull(2)?.jsonPrimitive?.doubleOrNull,
            mag = mag,
            magType = props["magType"]?.jsonPrimitive?.contentOrNull,
            place = props["place"]?.jsonPrimitive?.contentOrNull ?: "Unknown location",
            tsunami = props["tsunami"]?.jsonPrimitive?.intOrNull == 1,
            felt = props["felt"]?.jsonPrimitive?.intOrNull,
            status = if (props["status"]?.jsonPrimitive?.contentOrNull == "reviewed")
                QuakeStatus.REVIEWED else QuakeStatus.AUTOMATIC,
            sources = mapOf(Source.USGS to featureId),
            revisions = if (mag != null)
                listOf(MagRevision(mag, props["magType"]?.jsonPrimitive?.contentOrNull, updated, Source.USGS))
            else emptyList(),
            updatedAtMillis = updated,
        )
    }
}
```

- [ ] **Step 6: Run green**

Run: `./gradlew :core:network:jvmTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Add core:network USGS GeoJSON feed parser with recorded fixture

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: `core:network` — EMSC WebSocket message parser

**Files:**
- Create: `core/network/src/commonMain/kotlin/com/yugma/terrawatch/network/EmscParser.kt`
- Create: `core/network/src/jvmTest/kotlin/com/yugma/terrawatch/network/EmscParserTest.kt`
- Create: `core/network/src/jvmTest/resources/fixtures/emsc_event.json`

**Interfaces:**
- Consumes: `core:model` types
- Produces:

```kotlin
object EmscParser {
    fun parse(message: String): Quake?   // null when the message is not a quake event (heartbeats etc.)
}
// EMSC ws payload: {"action":"create"|"update","data":{"type":"Feature","geometry":{"coordinates":[lon,lat,-depth]},
//   "id":"20260807_0000123","properties":{"unid":"20260807_0000123","time":"2026-08-07T04:09:41.0Z","mag":6.1,
//   "magtype":"mw","flynn_region":"MINDANAO, PHILIPPINES","evtype":"ke","auto":true,...}}}
// depth arrives NEGATIVE in coordinates[2] (below sea level) -> store as positive km
// time is ISO-8601 string -> epoch millis
// sources = mapOf(Source.EMSC to unid); status AUTOMATIC when properties.auto==true else REVIEWED
```

- [ ] **Step 1: Write the fixture (checked-in sample of the documented EMSC shape)**

```json
{
  "action": "create",
  "data": {
    "type": "Feature",
    "geometry": { "type": "Point", "coordinates": [126.54, 7.12, -10.0] },
    "id": "20260807_0000123",
    "properties": {
      "unid": "20260807_0000123",
      "time": "2026-08-07T04:09:41.0Z",
      "lastupdate": "2026-08-07T04:12:03.0Z",
      "mag": 6.1,
      "magtype": "mw",
      "evtype": "ke",
      "auto": true,
      "flynn_region": "MINDANAO, PHILIPPINES",
      "lat": 7.12,
      "lon": 126.54,
      "depth": 10.0
    }
  }
}
```

Save as `core/network/src/jvmTest/resources/fixtures/emsc_event.json`.

**Fixture verification step (do not skip):** capture one real message and eyeball field names match:
```bash
python3 - <<'EOF'
import asyncio, json, sys
try:
    import websockets
except ImportError:
    sys.exit("pip3 install --user websockets, then rerun")
async def main():
    async with websockets.connect("wss://www.seismicportal.eu/standing_order/websocket") as ws:
        msg = await asyncio.wait_for(ws.recv(), timeout=180)
        print(json.dumps(json.loads(msg), indent=2)[:2000])
asyncio.run(main())
EOF
```
If field names differ from the fixture, fix the fixture AND the parser mapping table above before writing the parser. (If no event arrives in 3 min, proceed — the documented shape is the contract, and the WS integration test in Plan 2 re-validates.)

- [ ] **Step 2: Write failing test**

```kotlin
// core/network/src/jvmTest/kotlin/com/yugma/terrawatch/network/EmscParserTest.kt
package com.yugma.terrawatch.network

import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

private fun readFixture(name: String): String =
    Thread.currentThread().contextClassLoader!!.getResource("fixtures/$name")!!.readText()

class EmscParserTest {
    @Test fun `parses create event`() {
        val q = EmscParser.parse(readFixture("emsc_event.json"))
        assertNotNull(q)
        assertEquals("20260807_0000123", q.sources[Source.EMSC])
        assertEquals(6.1, q.mag)
        assertEquals(7.12, q.lat)
        assertEquals(126.54, q.lon)
        assertEquals(10.0, q.depthKm)                    // negative coord -> positive km
        assertEquals(QuakeStatus.AUTOMATIC, q.status)
        assertEquals("MINDANAO, PHILIPPINES", q.place)
        assertEquals(1786161000000L, q.timeMillis)       // computed at write time; adjust to python-verified value
    }
    @Test fun `non-event message returns null`() {
        assertNull(EmscParser.parse("""{"action":"heartbeat"}"""))
        assertNull(EmscParser.parse("""not json at all"""))
    }
}
```

Note: compute the exact expected epoch for `2026-08-07T04:09:41.0Z` before finalizing the test:
`python3 -c "from datetime import datetime,timezone; print(int(datetime(2026,8,7,4,9,41,tzinfo=timezone.utc).timestamp()*1000))"` — replace `1786161000000L` with the printed value.

- [ ] **Step 3: Run red**

Run: `./gradlew :core:network:jvmTest --tests '*EmscParserTest*'`
Expected: FAIL — `EmscParser` unresolved.

- [ ] **Step 4: Implement**

```kotlin
// core/network/src/commonMain/kotlin/com/yugma/terrawatch/network/EmscParser.kt
package com.yugma.terrawatch.network

import com.yugma.terrawatch.model.MagRevision
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs

object EmscParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(message: String): Quake? {
        val root = runCatching { json.parseToJsonElement(message).jsonObject }.getOrNull() ?: return null
        val action = root["action"]?.jsonPrimitive?.contentOrNull
        if (action != "create" && action != "update") return null
        val data = root["data"]?.jsonObject ?: return null
        val props = data["properties"]?.jsonObject ?: return null
        val unid = props["unid"]?.jsonPrimitive?.contentOrNull ?: return null
        val lat = props["lat"]?.jsonPrimitive?.doubleOrNull ?: return null
        val lon = props["lon"]?.jsonPrimitive?.doubleOrNull ?: return null
        val timeIso = props["time"]?.jsonPrimitive?.contentOrNull ?: return null
        val timeMillis = runCatching { Instant.parse(normalizeIso(timeIso)).toEpochMilliseconds() }.getOrNull() ?: return null
        val updatedIso = props["lastupdate"]?.jsonPrimitive?.contentOrNull
        val updatedMillis = updatedIso?.let { runCatching { Instant.parse(normalizeIso(it)).toEpochMilliseconds() }.getOrNull() } ?: timeMillis
        val mag = props["mag"]?.jsonPrimitive?.doubleOrNull
        val magType = props["magtype"]?.jsonPrimitive?.contentOrNull
        val depth = props["depth"]?.jsonPrimitive?.doubleOrNull
            ?: data["geometry"]?.jsonObject?.get("coordinates")?.jsonArray?.getOrNull(2)
                ?.jsonPrimitive?.doubleOrNull?.let { abs(it) }
        val auto = props["auto"]?.jsonPrimitive?.booleanOrNull
            ?: (props["auto"]?.jsonPrimitive?.contentOrNull != "false")
        return Quake(
            id = unid,
            timeMillis = timeMillis,
            lat = lat, lon = lon,
            depthKm = depth?.let { abs(it) },
            mag = mag, magType = magType,
            place = props["flynn_region"]?.jsonPrimitive?.contentOrNull ?: "Unknown location",
            tsunami = false,
            felt = null,
            status = if (auto) QuakeStatus.AUTOMATIC else QuakeStatus.REVIEWED,
            sources = mapOf(Source.EMSC to unid),
            revisions = if (mag != null) listOf(MagRevision(mag, magType, updatedMillis, Source.EMSC)) else emptyList(),
            updatedAtMillis = updatedMillis,
        )
    }

    // EMSC sends "2026-08-07T04:09:41.0Z"; Instant.parse needs well-formed fractions — it accepts this,
    // but some payloads omit 'Z'. Append when missing.
    private fun normalizeIso(s: String): String = if (s.endsWith("Z") || s.contains('+')) s else s + "Z"
}
```

Add `kotlinx-datetime` to `core:network` commonMain deps: `implementation(libs.kotlinx.datetime)`.

- [ ] **Step 5: Run green**

Run: `./gradlew :core:network:jvmTest --tests '*EmscParserTest*'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Add EMSC WebSocket message parser

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: `core:network` — HTTP clients: feed poll with ETag, FDSN pager, WS connector

**Files:**
- Create: `core/network/src/commonMain/kotlin/com/yugma/terrawatch/network/UsgsApi.kt`
- Create: `core/network/src/commonMain/kotlin/com/yugma/terrawatch/network/EmscLiveSource.kt`
- Create: `core/network/src/jvmTest/kotlin/com/yugma/terrawatch/network/UsgsApiTest.kt`

**Interfaces:**
- Consumes: parsers from Tasks 3–4
- Produces:

```kotlin
sealed interface FeedResult {
    data class Fresh(val quakes: List<Quake>, val etag: String?) : FeedResult
    data object NotModified : FeedResult
    data class Failure(val cause: Throwable) : FeedResult
}

class UsgsApi(private val http: HttpClient, private val baseFeedUrl: String = "https://earthquake.usgs.gov") {
    suspend fun fetchFeed(feed: String = "all_day", previousEtag: String? = null): FeedResult
    suspend fun queryArchive(endTimeMillis: Long, limit: Int = 200, minMagnitude: Double? = null): List<Quake>
    // FDSN: GET /fdsnws/event/1/query?format=geojson&orderby=time&limit=..&endtime=ISO&minmagnitude=..
}

class EmscLiveSource(private val http: HttpClient, private val url: String = "wss://www.seismicportal.eu/standing_order/websocket") {
    fun events(): Flow<Quake>   // cold; connects on collect, parses frames, silently skips non-events;
                                // reconnects with exponential backoff 1s,2s,4s..max 60s + jitter; flow never completes normally
}
```

- [ ] **Step 1: Write failing tests with Ktor MockEngine**

```kotlin
// core/network/src/jvmTest/kotlin/com/yugma/terrawatch/network/UsgsApiTest.kt
package com.yugma.terrawatch.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun readFixture(name: String): String =
    Thread.currentThread().contextClassLoader!!.getResource("fixtures/$name")!!.readText()

class UsgsApiTest {
    @Test fun `fresh feed returns quakes and etag`() = runTest {
        val engine = MockEngine { req ->
            assertTrue(req.url.toString().contains("/summary/all_day.geojson"))
            respond(readFixture("usgs_all_hour.json"), HttpStatusCode.OK,
                headersOf(HttpHeaders.ETag, "\"abc123\"", HttpHeaders.ContentType, "application/json"))
        }
        val api = UsgsApi(HttpClient(engine))
        val result = api.fetchFeed()
        val fresh = assertIs<FeedResult.Fresh>(result)
        assertTrue(fresh.quakes.isNotEmpty())
        assertEquals("\"abc123\"", fresh.etag)
    }

    @Test fun `etag is sent and 304 maps to NotModified`() = runTest {
        val engine = MockEngine { req ->
            assertEquals("\"abc123\"", req.headers[HttpHeaders.IfNoneMatch])
            respond("", HttpStatusCode.NotModified)
        }
        val api = UsgsApi(HttpClient(engine))
        assertIs<FeedResult.NotModified>(api.fetchFeed(previousEtag = "\"abc123\""))
    }

    @Test fun `server error maps to Failure not exception`() = runTest {
        val engine = MockEngine { respond("boom", HttpStatusCode.InternalServerError) }
        val api = UsgsApi(HttpClient(engine))
        assertIs<FeedResult.Failure>(api.fetchFeed())
    }

    @Test fun `archive query builds fdsn url and parses`() = runTest {
        val engine = MockEngine { req ->
            val u = req.url.toString()
            assertTrue(u.contains("/fdsnws/event/1/query"))
            assertTrue(u.contains("format=geojson"))
            assertTrue(u.contains("limit=200"))
            assertTrue(u.contains("orderby=time"))
            assertTrue(u.contains("endtime="))
            respond(readFixture("usgs_all_hour.json"), HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val api = UsgsApi(HttpClient(engine))
        assertTrue(api.queryArchive(endTimeMillis = 1_754_600_000_000).isNotEmpty())
    }
}
```

- [ ] **Step 2: Run red**

Run: `./gradlew :core:network:jvmTest --tests '*UsgsApiTest*'`
Expected: FAIL — `UsgsApi`, `FeedResult` unresolved.

- [ ] **Step 3: Implement `UsgsApi`**

```kotlin
// core/network/src/commonMain/kotlin/com/yugma/terrawatch/network/UsgsApi.kt
package com.yugma.terrawatch.network

import com.yugma.terrawatch.model.Quake
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.datetime.Instant

sealed interface FeedResult {
    data class Fresh(val quakes: List<Quake>, val etag: String?) : FeedResult
    data object NotModified : FeedResult
    data class Failure(val cause: Throwable) : FeedResult
}

class UsgsApi(
    private val http: HttpClient,
    private val baseUrl: String = "https://earthquake.usgs.gov",
) {
    suspend fun fetchFeed(feed: String = "all_day", previousEtag: String? = null): FeedResult = try {
        val resp = http.get("$baseUrl/earthquakes/feed/v1.0/summary/$feed.geojson") {
            previousEtag?.let { header(HttpHeaders.IfNoneMatch, it) }
        }
        when {
            resp.status == HttpStatusCode.NotModified -> FeedResult.NotModified
            resp.status.isSuccess() ->
                FeedResult.Fresh(UsgsFeedParser.parse(resp.bodyAsText()), resp.headers[HttpHeaders.ETag])
            else -> FeedResult.Failure(IllegalStateException("HTTP ${resp.status.value}"))
        }
    } catch (t: Throwable) {
        FeedResult.Failure(t)
    }

    suspend fun queryArchive(
        endTimeMillis: Long,
        limit: Int = 200,
        minMagnitude: Double? = null,
    ): List<Quake> {
        val endIso = Instant.fromEpochMilliseconds(endTimeMillis).toString()
        val url = buildString {
            append("$baseUrl/fdsnws/event/1/query?format=geojson&orderby=time&limit=$limit&endtime=$endIso")
            minMagnitude?.let { append("&minmagnitude=$it") }
        }
        val resp = http.get(url)
        return UsgsFeedParser.parse(resp.bodyAsText())
    }
}
```

- [ ] **Step 4: Run green**

Run: `./gradlew :core:network:jvmTest --tests '*UsgsApiTest*'`
Expected: PASS.

- [ ] **Step 5: Implement `EmscLiveSource` (reconnect logic unit-testable via injected connector in Plan 2's integration pass; here: structure + compile)**

```kotlin
// core/network/src/commonMain/kotlin/com/yugma/terrawatch/network/EmscLiveSource.kt
package com.yugma.terrawatch.network

import com.yugma.terrawatch.model.Quake
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

class EmscLiveSource(
    private val http: HttpClient,
    private val url: String = "wss://www.seismicportal.eu/standing_order/websocket",
) {
    fun events(): Flow<Quake> = flow {
        var backoffMs = 1_000L
        while (true) {
            try {
                http.webSocket(url) {
                    backoffMs = 1_000L
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            EmscParser.parse(frame.readText())?.let { emit(it) }
                        }
                    }
                }
            } catch (_: Throwable) {
                // fall through to backoff
            }
            delay(backoffMs + Random.nextLong(0, 500))
            backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
        }
    }
}
```

Compile check: `./gradlew :core:network:compileKotlinJvm` — Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Add UsgsApi (feed+ETag, FDSN archive) and EmscLiveSource

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: `core:database` — SQLDelight schema + revision-aware upsert

**Files:**
- Create: `core/database/build.gradle.kts`
- Create: `core/database/src/commonMain/sqldelight/com/yugma/terrawatch/database/Quake.sq`
- Create: `core/database/src/commonMain/kotlin/com/yugma/terrawatch/database/QuakeDao.kt`
- Create: `core/database/src/commonMain/kotlin/com/yugma/terrawatch/database/DriverFactory.kt` (expect)
- Create: `core/database/src/androidMain/kotlin/com/yugma/terrawatch/database/DriverFactory.android.kt`
- Create: `core/database/src/jvmMain/kotlin/com/yugma/terrawatch/database/DriverFactory.jvm.kt`
- Create: `core/database/src/wasmJsMain/kotlin/com/yugma/terrawatch/database/DriverFactory.wasmJs.kt`
- Create: `core/database/src/jvmTest/kotlin/com/yugma/terrawatch/database/QuakeDaoTest.kt`
- Modify: `settings.gradle.kts` (add `include(":core:database")`)

**Interfaces:**
- Consumes: `Quake` and friends from `core:model`
- Produces:

```kotlin
class QuakeDao(db: TerraWatchDb) {
    fun upsert(quake: Quake)                                  // insert or revision-aware merge (see rules below)
    fun upsertAll(quakes: List<Quake>)                        // single transaction
    fun byId(id: String): Quake?
    fun recent(sinceMillis: Long): Flow<List<Quake>>          // ordered by timeMillis DESC
    fun pageBefore(timeMillis: Long, limit: Int, minMag: Double?): List<Quake>  // History paging
    fun countAll(): Long
}
// Upsert rules:
//  - row absent -> insert as-is
//  - row present, incoming.updatedAtMillis <= existing.updatedAtMillis -> keep existing row untouched
//  - row present, newer -> update fields; sources = existing.sources + incoming.sources;
//    revisions = existing.revisions + (incoming revision entries whose (mag,atMillis,source) not already present)
expect class DriverFactory { fun createDriver(): SqlDriver }
fun createDatabase(driverFactory: DriverFactory): TerraWatchDb
```

- [ ] **Step 1: Module build file + include**

```kotlin
// core/database/build.gradle.kts
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
        wasmJsMain.dependencies { implementation(libs.sqldelight.webworker.driver) }
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
```

Note: if the wasm web-worker driver requires `generateAsync=true` at link time, keep `generateAsync=false` for v1 and exclude the wasm driver dependency until Plan 3's web-storage task — the wasm target still compiles because SQLDelight runtime is multiplatform. Note the decision in the commit message.

- [ ] **Step 2: Write `Quake.sq`**

```sql
-- core/database/src/commonMain/sqldelight/com/yugma/terrawatch/database/Quake.sq
CREATE TABLE quake (
  id TEXT NOT NULL PRIMARY KEY,
  timeMillis INTEGER NOT NULL,
  lat REAL NOT NULL,
  lon REAL NOT NULL,
  depthKm REAL,
  mag REAL,
  magType TEXT,
  place TEXT NOT NULL,
  tsunami INTEGER NOT NULL DEFAULT 0,
  felt INTEGER,
  status TEXT NOT NULL,
  sourcesJson TEXT NOT NULL,
  revisionsJson TEXT NOT NULL,
  updatedAtMillis INTEGER NOT NULL,
  fetchedAtMillis INTEGER NOT NULL
);

CREATE INDEX quake_time ON quake(timeMillis DESC);
CREATE INDEX quake_mag ON quake(mag);

insertOrReplace:
INSERT OR REPLACE INTO quake VALUES ?;

byId:
SELECT * FROM quake WHERE id = ?;

recent:
SELECT * FROM quake WHERE timeMillis >= ? ORDER BY timeMillis DESC;

pageBefore:
SELECT * FROM quake
WHERE timeMillis < :timeMillis AND (:minMag IS NULL OR mag >= :minMag)
ORDER BY timeMillis DESC LIMIT :limit;

countAll:
SELECT COUNT(*) FROM quake;

meta_get:
SELECT value FROM meta WHERE key = ?;

meta_put:
INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?);
```

Add the meta table at the top of the same file:

```sql
CREATE TABLE meta (
  key TEXT NOT NULL PRIMARY KEY,
  value TEXT NOT NULL
);
```

(SQLDelight files execute top-to-bottom; keep both CREATE TABLE statements before the named queries.)

- [ ] **Step 3: Write failing DAO test**

```kotlin
// core/database/src/jvmTest/kotlin/com/yugma/terrawatch/database/QuakeDaoTest.kt
package com.yugma.terrawatch.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yugma.terrawatch.model.MagRevision
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.BeforeTest

class QuakeDaoTest {
    private lateinit var dao: QuakeDao

    @BeforeTest fun setup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TerraWatchDb.Schema.create(driver)
        dao = QuakeDao(TerraWatchDb(driver))
    }

    private fun quake(
        id: String = "us1", updated: Long = 1000, mag: Double? = 5.0,
        sources: Map<Source, String> = mapOf(Source.USGS to id),
        revisions: List<MagRevision> = listOf(MagRevision(5.0, "mb", 1000, Source.USGS)),
    ) = Quake(id, 900, 7.1, 126.5, 10.0, mag, "mb", "Somewhere", false, null,
        QuakeStatus.AUTOMATIC, sources, revisions, updated)

    @Test fun `insert then read back`() {
        dao.upsert(quake())
        val q = assertNotNull(dao.byId("us1"))
        assertEquals(5.0, q.mag)
        assertEquals(1, dao.countAll())
    }

    @Test fun `stale update is ignored`() {
        dao.upsert(quake(updated = 2000, mag = 6.1))
        dao.upsert(quake(updated = 1000, mag = 5.0))
        assertEquals(6.1, assertNotNull(dao.byId("us1")).mag)
    }

    @Test fun `newer update merges sources and appends distinct revisions`() {
        dao.upsert(quake(updated = 1000))
        dao.upsert(quake(
            updated = 2000, mag = 6.1,
            sources = mapOf(Source.EMSC to "e1"),
            revisions = listOf(MagRevision(6.1, "mw", 2000, Source.EMSC)),
        ))
        val q = assertNotNull(dao.byId("us1"))
        assertEquals(6.1, q.mag)
        assertEquals(setOf(Source.USGS, Source.EMSC), q.sources.keys)
        assertEquals(2, q.revisions.size)
    }

    @Test fun `duplicate revision entries are not appended twice`() {
        dao.upsert(quake(updated = 1000))
        dao.upsert(quake(updated = 2000, revisions = listOf(MagRevision(5.0, "mb", 1000, Source.USGS))))
        assertEquals(1, assertNotNull(dao.byId("us1")).revisions.size)
    }

    @Test fun `pageBefore filters by magnitude and pages by time`() {
        dao.upsertAll(listOf(
            quake(id = "a", updated = 1).copy(timeMillis = 100, mag = 2.0),
            quake(id = "b", updated = 1).copy(timeMillis = 200, mag = 5.0),
            quake(id = "c", updated = 1).copy(timeMillis = 300, mag = 6.5),
        ))
        val page = dao.pageBefore(timeMillis = 400, limit = 10, minMag = 4.5)
        assertEquals(listOf("c", "b"), page.map { it.id })
    }
}
```

- [ ] **Step 4: Run red**

Run: `./gradlew :core:database:jvmTest`
Expected: FAIL — `QuakeDao` unresolved (generated `TerraWatchDb` appears after first successful SQLDelight compile; the DAO wrapper is what's missing).

- [ ] **Step 5: Implement DAO + drivers**

```kotlin
// core/database/src/commonMain/kotlin/com/yugma/terrawatch/database/QuakeDao.kt
package com.yugma.terrawatch.database

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.yugma.terrawatch.model.MagRevision
import com.yugma.terrawatch.model.Quake as DomainQuake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

@Serializable
private data class RevisionJson(val mag: Double, val magType: String?, val atMillis: Long, val source: String)

class QuakeDao(private val db: TerraWatchDb) {
    private val json = Json

    fun upsert(quake: DomainQuake) = db.transaction { upsertInternal(quake) }

    fun upsertAll(quakes: List<DomainQuake>) = db.transaction { quakes.forEach { upsertInternal(it) } }

    private fun upsertInternal(incoming: DomainQuake) {
        val existing = byIdInternal(incoming.id)
        val toWrite = when {
            existing == null -> incoming
            incoming.updatedAtMillis <= existing.updatedAtMillis -> return
            else -> incoming.copy(
                sources = existing.sources + incoming.sources,
                revisions = existing.revisions +
                    incoming.revisions.filter { r ->
                        existing.revisions.none { it.mag == r.mag && it.atMillis == r.atMillis && it.source == r.source }
                    },
            )
        }
        db.quakeQueries.insertOrReplace(toWrite.toRow())
    }

    fun byId(id: String): DomainQuake? = byIdInternal(id)

    private fun byIdInternal(id: String): DomainQuake? =
        db.quakeQueries.byId(id).executeAsOneOrNull()?.toDomain()

    fun recent(sinceMillis: Long): Flow<List<DomainQuake>> =
        db.quakeQueries.recent(sinceMillis).asFlow().mapToList(Dispatchers.Default)
            .let { flow -> kotlinx.coroutines.flow.map(flow) { rows -> rows.map { it.toDomain() } } }

    fun pageBefore(timeMillis: Long, limit: Int, minMag: Double?): List<DomainQuake> =
        db.quakeQueries.pageBefore(timeMillis, minMag, limit.toLong()).executeAsList().map { it.toDomain() }

    fun countAll(): Long = db.quakeQueries.countAll().executeAsOne()

    private fun DomainQuake.toRow() = Quake(
        id = id, timeMillis = timeMillis, lat = lat, lon = lon, depthKm = depthKm,
        mag = mag, magType = magType, place = place, tsunami = if (tsunami) 1 else 0,
        felt = felt?.toLong(), status = status.name,
        sourcesJson = json.encodeToString(
            MapSerializer(String.serializer(), String.serializer()),
            sources.mapKeys { it.key.name }),
        revisionsJson = json.encodeToString(
            ListSerializer(RevisionJson.serializer()),
            revisions.map { RevisionJson(it.mag, it.magType, it.atMillis, it.source.name) }),
        updatedAtMillis = updatedAtMillis,
        fetchedAtMillis = updatedAtMillis,
    )

    private fun Quake.toDomain() = DomainQuake(
        id = id, timeMillis = timeMillis, lat = lat, lon = lon, depthKm = depthKm,
        mag = mag, magType = magType, place = place, tsunami = tsunami == 1L,
        felt = felt?.toInt(), status = QuakeStatus.valueOf(status),
        sources = json.decodeFromString(
            MapSerializer(String.serializer(), String.serializer()), sourcesJson)
            .mapKeys { Source.valueOf(it.key) },
        revisions = json.decodeFromString(ListSerializer(RevisionJson.serializer()), revisionsJson)
            .map { MagRevision(it.mag, it.magType, it.atMillis, Source.valueOf(it.source)) },
        updatedAtMillis = updatedAtMillis,
    )
}
```

(The generated row class is also named `Quake` — hence the `DomainQuake` import alias. If the name collision confuses the compiler, rename the generated type via `CREATE TABLE quakeRow` — but try the alias first.)

```kotlin
// core/database/src/commonMain/kotlin/com/yugma/terrawatch/database/DriverFactory.kt
package com.yugma.terrawatch.database

import app.cash.sqldelight.db.SqlDriver

expect class DriverFactory {
    fun createDriver(): SqlDriver
}

fun createDatabase(driverFactory: DriverFactory): TerraWatchDb = TerraWatchDb(driverFactory.createDriver())
```

```kotlin
// core/database/src/androidMain/kotlin/com/yugma/terrawatch/database/DriverFactory.android.kt
package com.yugma.terrawatch.database

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

actual class DriverFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver =
        AndroidSqliteDriver(TerraWatchDb.Schema, context, "terrawatch.db")
}
```

```kotlin
// core/database/src/jvmMain/kotlin/com/yugma/terrawatch/database/DriverFactory.jvm.kt
package com.yugma.terrawatch.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

actual class DriverFactory {
    actual fun createDriver(): SqlDriver {
        val dir = File(System.getProperty("user.home"), ".terrawatch").apply { mkdirs() }
        val driver = JdbcSqliteDriver("jdbc:sqlite:${File(dir, "terrawatch.db").absolutePath}")
        TerraWatchDb.Schema.create(driver)
        return driver
    }
}
```

```kotlin
// core/database/src/wasmJsMain/kotlin/com/yugma/terrawatch/database/DriverFactory.wasmJs.kt
package com.yugma.terrawatch.database

import app.cash.sqldelight.db.SqlDriver

// Web storage lands in Plan 3 (worker driver needs async codegen).
// v1 web runs on the in-memory path provided there; this actual keeps the target compiling.
actual class DriverFactory {
    actual fun createDriver(): SqlDriver =
        throw NotImplementedError("Web persistence arrives in Plan 3; wire in-memory repository fallback for wasm.")
}
```

- [ ] **Step 6: Run green**

Run: `./gradlew :core:database:jvmTest`
Expected: PASS all five tests.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Add core:database — SQLDelight schema, revision-aware upsert DAO

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 7: `core:data` — DedupeEngine (cross-agency reconciliation)

**Files:**
- Create: `core/data/build.gradle.kts`
- Create: `core/data/src/commonMain/kotlin/com/yugma/terrawatch/data/DedupeEngine.kt`
- Create: `core/data/src/commonTest/kotlin/com/yugma/terrawatch/data/DedupeEngineTest.kt`
- Modify: `settings.gradle.kts` (add `include(":core:data")`)

**Interfaces:**
- Consumes: `core:model`, `core:database` (`QuakeDao`), `core:network` (types only in later tasks)
- Produces:

```kotlin
data class ReconcileResult(val canonical: Quake, val replacesId: String?)
// replacesId != null means: delete/replace the row previously stored under that id
// (happens when EMSC arrived first and the USGS twin shows up later — USGS id wins)

class DedupeEngine(
    private val timeWindowMs: Long = 90_000,
    private val distanceKm: Double = 100.0,
) {
    fun reconcile(candidates: List<Quake>, incoming: Quake): ReconcileResult
    // candidates: rows whose timeMillis is within ±timeWindowMs of incoming (caller pre-filters via DB query)
}
// Match rule: |t1-t2| <= timeWindowMs AND haversineKm <= distanceKm, best (closest) match wins.
// Merge rules on match:
//  - canonical id: USGS-sourced id preferred; if both have USGS ids, keep existing row's id
//  - sources merged; mag preference: REVIEWED > USGS > newest updatedAtMillis
//  - revisions: union, deduped by (mag, atMillis, source), sorted by atMillis
//  - place: prefer USGS text; tsunami: logical OR; felt: max of non-null
//  - updatedAtMillis: max of both
// No match: ReconcileResult(incoming, null)
```

- [ ] **Step 1: Module build file + include**

```kotlin
// core/data/build.gradle.kts
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
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies { implementation(libs.turbine) }
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
```

- [ ] **Step 2: Write failing tests**

```kotlin
// core/data/src/commonTest/kotlin/com/yugma/terrawatch/data/DedupeEngineTest.kt
package com.yugma.terrawatch.data

import com.yugma.terrawatch.model.MagRevision
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun q(
    id: String, source: Source, t: Long = 1_000_000, lat: Double = 7.10, lon: Double = 126.50,
    mag: Double = 6.0, status: QuakeStatus = QuakeStatus.AUTOMATIC, updated: Long = t,
) = Quake(id, t, lat, lon, 10.0, mag, "mw", "PLACE", false, null, status,
    mapOf(source to id), listOf(MagRevision(mag, "mw", updated, source)), updated)

class DedupeEngineTest {
    private val engine = DedupeEngine()

    @Test fun `no candidates passes through`() {
        val r = engine.reconcile(emptyList(), q("e1", Source.EMSC))
        assertEquals("e1", r.canonical.id)
        assertNull(r.replacesId)
    }

    @Test fun `same event from both agencies merges under usgs id`() {
        val emscFirst = q("e1", Source.EMSC, t = 1_000_000)
        val usgsLater = q("us1", Source.USGS, t = 1_000_030_000 - 1_000_000 + 1_000_000)
        // keep it simple: 30s apart, 5km apart
        val usgs = usgsLater.copy(timeMillis = 1_030_000, lat = 7.14, updatedAtMillis = 1_030_000)
        val r = engine.reconcile(listOf(emscFirst), usgs)
        assertEquals("us1", r.canonical.id)
        assertEquals("e1", r.replacesId)
        assertEquals(setOf(Source.USGS, Source.EMSC), r.canonical.sources.keys)
    }

    @Test fun `usgs stored first keeps its id when emsc twin arrives`() {
        val usgsFirst = q("us1", Source.USGS, t = 1_000_000)
        val emsc = q("e1", Source.EMSC, t = 1_020_000)
        val r = engine.reconcile(listOf(usgsFirst), emsc)
        assertEquals("us1", r.canonical.id)
        assertNull(r.replacesId)   // canonical row already stored under us1
        assertEquals("e1", r.canonical.sources[Source.EMSC])
    }

    @Test fun `outside time window does not match`() {
        val a = q("us1", Source.USGS, t = 1_000_000)
        val b = q("e1", Source.EMSC, t = 1_000_000 + 91_000)
        assertNull(engine.reconcile(listOf(a), b).replacesId)
        assertEquals("e1", engine.reconcile(listOf(a), b).canonical.id)
    }

    @Test fun `outside distance does not match`() {
        val a = q("us1", Source.USGS, lat = 7.0, lon = 126.0)
        val b = q("e1", Source.EMSC, lat = 8.5, lon = 127.5) // ~190 km away
        assertNull(engine.reconcile(listOf(a), b).replacesId)
    }

    @Test fun `reviewed magnitude beats automatic`() {
        val auto = q("us1", Source.USGS, mag = 5.9, status = QuakeStatus.AUTOMATIC, updated = 2_000_000)
        val reviewed = q("e1", Source.EMSC, mag = 6.1, status = QuakeStatus.REVIEWED, updated = 1_500_000)
        val r = engine.reconcile(listOf(auto), reviewed)
        assertEquals(6.1, r.canonical.mag)
    }

    @Test fun `revisions union is deduped and sorted`() {
        val a = q("us1", Source.USGS, updated = 1_000)
        val b = q("e1", Source.EMSC, updated = 2_000).copy(
            revisions = listOf(
                MagRevision(6.0, "mw", 500, Source.EMSC),
                MagRevision(6.1, "mw", 2_000, Source.EMSC),
            ))
        val r = engine.reconcile(listOf(a), b)
        assertEquals(listOf(500L, 1_000L, 2_000L), r.canonical.revisions.map { it.atMillis })
    }

    @Test fun `closest of multiple candidates wins`() {
        val near = q("us_near", Source.USGS, lat = 7.11)
        val far = q("us_far", Source.USGS, lat = 7.60)
        val incoming = q("e1", Source.EMSC, lat = 7.10)
        val r = engine.reconcile(listOf(far, near), incoming)
        assertEquals("us_near", r.canonical.id)
    }

    @Test fun `tsunami flag ors and felt takes max`() {
        val a = q("us1", Source.USGS).copy(tsunami = true, felt = 120)
        val b = q("e1", Source.EMSC).copy(felt = 300)
        val r = engine.reconcile(listOf(a), b)
        assertTrue(r.canonical.tsunami)
        assertEquals(300, r.canonical.felt)
    }
}
```

- [ ] **Step 3: Run red**

Run: `./gradlew :core:data:jvmTest`
Expected: FAIL — `DedupeEngine` unresolved.

- [ ] **Step 4: Implement**

```kotlin
// core/data/src/commonMain/kotlin/com/yugma/terrawatch/data/DedupeEngine.kt
package com.yugma.terrawatch.data

import com.yugma.terrawatch.model.GeoPoint
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import com.yugma.terrawatch.model.haversineKm
import kotlin.math.abs

data class ReconcileResult(val canonical: Quake, val replacesId: String?)

class DedupeEngine(
    private val timeWindowMs: Long = 90_000,
    private val distanceKm: Double = 100.0,
) {
    fun reconcile(candidates: List<Quake>, incoming: Quake): ReconcileResult {
        val match = candidates
            .filter { abs(it.timeMillis - incoming.timeMillis) <= timeWindowMs }
            .map { it to haversineKm(GeoPoint(it.lat, it.lon), GeoPoint(incoming.lat, incoming.lon)) }
            .filter { (_, d) -> d <= distanceKm }
            .minByOrNull { (_, d) -> d }?.first
            ?: return ReconcileResult(incoming, null)

        val merged = merge(match, incoming)
        val replaces = if (merged.id != match.id) match.id else null
        return ReconcileResult(merged, replaces)
    }

    private fun merge(existing: Quake, incoming: Quake): Quake {
        val id = when {
            existing.sources.containsKey(Source.USGS) -> existing.id
            incoming.sources.containsKey(Source.USGS) ->
                incoming.sources.getValue(Source.USGS)
            else -> existing.id
        }
        val magHolder = pickMagnitudeHolder(existing, incoming)
        val placeHolder = if (existing.sources.containsKey(Source.USGS)) existing
            else if (incoming.sources.containsKey(Source.USGS)) incoming else existing
        val revisions = (existing.revisions + incoming.revisions)
            .distinctBy { Triple(it.mag, it.atMillis, it.source) }
            .sortedBy { it.atMillis }
        return Quake(
            id = id,
            timeMillis = magHolder.timeMillis,
            lat = magHolder.lat, lon = magHolder.lon,
            depthKm = magHolder.depthKm ?: existing.depthKm ?: incoming.depthKm,
            mag = magHolder.mag, magType = magHolder.magType,
            place = placeHolder.place,
            tsunami = existing.tsunami || incoming.tsunami,
            felt = listOfNotNull(existing.felt, incoming.felt).maxOrNull(),
            status = if (existing.status == QuakeStatus.REVIEWED || incoming.status == QuakeStatus.REVIEWED)
                QuakeStatus.REVIEWED else QuakeStatus.AUTOMATIC,
            sources = existing.sources + incoming.sources,
            revisions = revisions,
            updatedAtMillis = maxOf(existing.updatedAtMillis, incoming.updatedAtMillis),
        )
    }

    private fun pickMagnitudeHolder(a: Quake, b: Quake): Quake = when {
        (a.status == QuakeStatus.REVIEWED) != (b.status == QuakeStatus.REVIEWED) ->
            if (a.status == QuakeStatus.REVIEWED) a else b
        a.sources.containsKey(Source.USGS) != b.sources.containsKey(Source.USGS) ->
            if (a.sources.containsKey(Source.USGS)) a else b
        else -> if (a.updatedAtMillis >= b.updatedAtMillis) a else b
    }
}
```

- [ ] **Step 5: Run green**

Run: `./gradlew :core:data:jvmTest`
Expected: PASS all nine tests.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Add DedupeEngine — cross-agency quake reconciliation

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 8: `core:data` — AlertRuleEngine

**Files:**
- Create: `core/data/src/commonMain/kotlin/com/yugma/terrawatch/data/AlertRuleEngine.kt`
- Create: `core/data/src/commonTest/kotlin/com/yugma/terrawatch/data/AlertRuleEngineTest.kt`

**Interfaces:**
- Consumes: `core:model`
- Produces:

```kotlin
data class AlertRule(
    val id: String,
    val minMag: Double,
    val radiusKm: Double?,     // null = worldwide
    val center: GeoPoint?,     // null = use home; required when radiusKm != null and home may be null
    val enabled: Boolean = true,
)
val DEFAULT_RULES = listOf(
    AlertRule(id = "near", minMag = 4.5, radiusKm = 500.0, center = null),
    AlertRule(id = "world", minMag = 6.0, radiusKm = null, center = null),
)

data class AlertEvent(val quake: Quake, val matchedRuleId: String)

class AlertRuleEngine {
    fun evaluate(previous: Quake?, current: Quake, rules: List<AlertRule>, home: GeoPoint?): AlertEvent?
    // Fires when: rule enabled AND current.mag >= minMag AND inside radius (if any)
    //   AND (previous == null            -> new quake
    //        OR previous.mag < minMag)   -> revision crossed the threshold
    // Radius rules with center==null use home; if home also null, radius rules never fire.
    // First matching rule wins (list order = priority). Never fires twice for same rule+quake
    //   (the previous.mag guard is what prevents refiring on non-mag updates).
}
```

- [ ] **Step 1: Write failing tests**

```kotlin
// core/data/src/commonTest/kotlin/com/yugma/terrawatch/data/AlertRuleEngineTest.kt
package com.yugma.terrawatch.data

import com.yugma.terrawatch.model.GeoPoint
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

private fun q(mag: Double?, lat: Double = 13.0, lon: Double = 77.6, updated: Long = 1000) =
    Quake("q1", 900, lat, lon, 10.0, mag, "mb", "P", false, null,
        QuakeStatus.AUTOMATIC, mapOf(Source.USGS to "q1"), emptyList(), updated)

class AlertRuleEngineTest {
    private val engine = AlertRuleEngine()
    private val home = GeoPoint(12.97, 77.59)  // Bengaluru

    @Test fun `new nearby quake above threshold fires near rule`() {
        val e = assertNotNull(engine.evaluate(null, q(5.0), DEFAULT_RULES, home))
        assertEquals("near", e.matchedRuleId)
    }

    @Test fun `new far quake below world threshold does not fire`() {
        assertNull(engine.evaluate(null, q(5.5, lat = 35.0, lon = 140.0), DEFAULT_RULES, home))
    }

    @Test fun `new far major quake fires world rule`() {
        val e = assertNotNull(engine.evaluate(null, q(6.2, lat = 35.0, lon = 140.0), DEFAULT_RULES, home))
        assertEquals("world", e.matchedRuleId)
    }

    @Test fun `revision crossing threshold fires`() {
        val e = engine.evaluate(q(5.8, lat = 35.0, lon = 140.0), q(6.1, lat = 35.0, lon = 140.0), DEFAULT_RULES, home)
        assertEquals("world", assertNotNull(e).matchedRuleId)
    }

    @Test fun `update without crossing does not refire`() {
        assertNull(engine.evaluate(q(6.1, lat = 35.0, lon = 140.0), q(6.3, lat = 35.0, lon = 140.0), DEFAULT_RULES, home))
    }

    @Test fun `radius rule without home never fires`() {
        assertNull(engine.evaluate(null, q(5.0), DEFAULT_RULES, home = null))
    }

    @Test fun `world rule works without home`() {
        val e = assertNotNull(engine.evaluate(null, q(6.5), DEFAULT_RULES, home = null))
        assertEquals("world", e.matchedRuleId)
    }

    @Test fun `disabled rule is skipped`() {
        val rules = DEFAULT_RULES.map { it.copy(enabled = false) }
        assertNull(engine.evaluate(null, q(7.0), rules, home))
    }

    @Test fun `null magnitude never fires`() {
        assertNull(engine.evaluate(null, q(null), DEFAULT_RULES, home))
    }
}
```

- [ ] **Step 2: Run red**

Run: `./gradlew :core:data:jvmTest --tests '*AlertRuleEngineTest*'`
Expected: FAIL — unresolved.

- [ ] **Step 3: Implement**

```kotlin
// core/data/src/commonMain/kotlin/com/yugma/terrawatch/data/AlertRuleEngine.kt
package com.yugma.terrawatch.data

import com.yugma.terrawatch.model.GeoPoint
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.haversineKm

data class AlertRule(
    val id: String,
    val minMag: Double,
    val radiusKm: Double?,
    val center: GeoPoint?,
    val enabled: Boolean = true,
)

val DEFAULT_RULES = listOf(
    AlertRule(id = "near", minMag = 4.5, radiusKm = 500.0, center = null),
    AlertRule(id = "world", minMag = 6.0, radiusKm = null, center = null),
)

data class AlertEvent(val quake: Quake, val matchedRuleId: String)

class AlertRuleEngine {
    fun evaluate(previous: Quake?, current: Quake, rules: List<AlertRule>, home: GeoPoint?): AlertEvent? {
        val mag = current.mag ?: return null
        for (rule in rules) {
            if (!rule.enabled) continue
            if (mag < rule.minMag) continue
            if (previous?.mag != null && previous.mag >= rule.minMag) continue
            if (rule.radiusKm != null) {
                val center = rule.center ?: home ?: continue
                if (haversineKm(center, GeoPoint(current.lat, current.lon)) > rule.radiusKm) continue
            }
            return AlertEvent(current, rule.id)
        }
        return null
    }
}
```

- [ ] **Step 4: Run green**

Run: `./gradlew :core:data:jvmTest --tests '*AlertRuleEngineTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Add AlertRuleEngine with default near/world rules

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 9: `core:data` — QuakeRepository (poll + live + archive through one upsert path)

**Files:**
- Create: `core/data/src/commonMain/kotlin/com/yugma/terrawatch/data/QuakeRepository.kt`
- Create: `core/data/src/jvmTest/kotlin/com/yugma/terrawatch/data/QuakeRepositoryTest.kt`

**Interfaces:**
- Consumes: `UsgsApi`, `FeedResult`, `EmscLiveSource` (Task 5), `QuakeDao` (Task 6), `DedupeEngine` (7), `AlertRuleEngine` (8)
- Produces (what feature ViewModels use from Plan 2 on):

```kotlin
class QuakeRepository(
    private val api: UsgsApi,
    private val live: EmscLiveSource,
    private val dao: QuakeDao,
    private val dedupe: DedupeEngine = DedupeEngine(),
    private val alerts: AlertRuleEngine = AlertRuleEngine(),
    private val clock: () -> Long,                       // injectable for tests
) {
    val alertEvents: SharedFlow<AlertEvent>
    fun recentQuakes(windowMs: Long = 86_400_000): Flow<List<Quake>>
    suspend fun refreshFeed(): RefreshStatus              // ETag persisted in meta table under key "feed_etag"
    suspend fun startLive(scope: CoroutineScope)          // collects EmscLiveSource into ingest()
    suspend fun loadArchivePage(beforeMillis: Long, minMag: Double? = null): Int  // returns rows ingested
    suspend fun ingest(incoming: Quake, rules: List<AlertRule> = DEFAULT_RULES, home: GeoPoint? = null)
}
enum class RefreshStatus { UPDATED, NOT_MODIFIED, FAILED }
// ingest() = the single write path: window-query dao -> dedupe.reconcile -> dao.upsert (+delete replaced row)
//   -> alerts.evaluate(previous, merged) -> emit AlertEvent
```

- [ ] **Step 1: Write failing repository test with fakes**

```kotlin
// core/data/src/jvmTest/kotlin/com/yugma/terrawatch/data/QuakeRepositoryTest.kt
package com.yugma.terrawatch.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.database.TerraWatchDb
import com.yugma.terrawatch.model.GeoPoint
import com.yugma.terrawatch.model.MagRevision
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import com.yugma.terrawatch.network.EmscLiveSource
import com.yugma.terrawatch.network.UsgsApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.BeforeTest

class QuakeRepositoryTest {
    private lateinit var dao: QuakeDao

    @BeforeTest fun setup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TerraWatchDb.Schema.create(driver)
        dao = QuakeDao(TerraWatchDb(driver))
    }

    private fun repo(engine: MockEngine.Companion.() -> MockEngine = { MockEngine { respond("", HttpStatusCode.NotFound) } }): QuakeRepository {
        val http = HttpClient(MockEngine.engine())
        return QuakeRepository(UsgsApi(http), EmscLiveSource(http), dao, clock = { 10_000_000 })
    }
    // NOTE to implementer: the helper above is illustrative — shape it so each test
    // can hand the repository its own MockEngine. Keep it simple; no mocking libraries.

    private fun quake(id: String, source: Source, mag: Double, t: Long, updated: Long = t) =
        Quake(id, t, 7.1, 126.5, 10.0, mag, "mw", "P", false, null, QuakeStatus.AUTOMATIC,
            mapOf(source to id), listOf(MagRevision(mag, "mw", updated, source)), updated)

    @Test fun `ingest stores new quake and emits on recent flow`() = runTest {
        val r = QuakeRepository(
            UsgsApi(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
            EmscLiveSource(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
            dao, clock = { 2_000_000 })
        r.ingest(quake("us1", Source.USGS, 5.5, t = 1_950_000))
        r.recentQuakes(windowMs = 100_000).test {
            assertEquals(listOf("us1"), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `emsc twin merges into stored usgs row`() = runTest {
        val r = QuakeRepository(
            UsgsApi(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
            EmscLiveSource(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
            dao, clock = { 2_000_000 })
        r.ingest(quake("us1", Source.USGS, 5.5, t = 1_950_000, updated = 1_950_000))
        r.ingest(quake("e1", Source.EMSC, 5.7, t = 1_960_000, updated = 1_960_000))
        assertEquals(1, dao.countAll())
        val stored = dao.byId("us1")!!
        assertEquals("e1", stored.sources[Source.EMSC])
    }

    @Test fun `alert fires once when threshold crossed by revision`() = runTest {
        val r = QuakeRepository(
            UsgsApi(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
            EmscLiveSource(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
            dao, clock = { 2_000_000 })
        r.alertEvents.test {
            r.ingest(quake("us1", Source.USGS, 5.8, t = 1_900_000, updated = 1_900_000), home = null)
            r.ingest(quake("us1", Source.USGS, 6.1, t = 1_900_000, updated = 1_910_000), home = null)
            assertEquals("world", awaitItem().matchedRuleId)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `refreshFeed persists etag and second call sends it`() = runTest {
        var sawIfNoneMatch: String? = null
        val engine = MockEngine { req ->
            sawIfNoneMatch = req.headers[HttpHeaders.IfNoneMatch]
            if (sawIfNoneMatch == null)
                respond("""{"features":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ETag, "\"e1\"", HttpHeaders.ContentType, "application/json"))
            else respond("", HttpStatusCode.NotModified)
        }
        val r = QuakeRepository(UsgsApi(HttpClient(engine)),
            EmscLiveSource(HttpClient(engine)), dao, clock = { 2_000_000 })
        assertEquals(RefreshStatus.UPDATED, r.refreshFeed())
        assertEquals(RefreshStatus.NOT_MODIFIED, r.refreshFeed())
        assertEquals("\"e1\"", sawIfNoneMatch)
    }
}
```

- [ ] **Step 2: Run red**

Run: `./gradlew :core:data:jvmTest --tests '*QuakeRepositoryTest*'`
Expected: FAIL — `QuakeRepository`, `RefreshStatus` unresolved.

- [ ] **Step 3: Implement**

```kotlin
// core/data/src/commonMain/kotlin/com/yugma/terrawatch/data/QuakeRepository.kt
package com.yugma.terrawatch.data

import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.model.GeoPoint
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.network.EmscLiveSource
import com.yugma.terrawatch.network.FeedResult
import com.yugma.terrawatch.network.UsgsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

enum class RefreshStatus { UPDATED, NOT_MODIFIED, FAILED }

class QuakeRepository(
    private val api: UsgsApi,
    private val live: EmscLiveSource,
    private val dao: QuakeDao,
    private val dedupe: DedupeEngine = DedupeEngine(),
    private val alerts: AlertRuleEngine = AlertRuleEngine(),
    private val clock: () -> Long,
) {
    private val _alertEvents = MutableSharedFlow<AlertEvent>(extraBufferCapacity = 16)
    val alertEvents: SharedFlow<AlertEvent> = _alertEvents

    fun recentQuakes(windowMs: Long = 86_400_000): Flow<List<Quake>> =
        dao.recent(clock() - windowMs)

    suspend fun refreshFeed(): RefreshStatus =
        when (val result = api.fetchFeed(previousEtag = dao.metaGet(FEED_ETAG_KEY))) {
            is FeedResult.Fresh -> {
                result.quakes.forEach { ingest(it) }
                result.etag?.let { dao.metaPut(FEED_ETAG_KEY, it) }
                RefreshStatus.UPDATED
            }
            FeedResult.NotModified -> RefreshStatus.NOT_MODIFIED
            is FeedResult.Failure -> RefreshStatus.FAILED
        }

    suspend fun startLive(scope: CoroutineScope) {
        scope.launch { live.events().collect { ingest(it) } }
    }

    suspend fun loadArchivePage(beforeMillis: Long, minMag: Double? = null): Int {
        val page = api.queryArchive(endTimeMillis = beforeMillis, minMagnitude = minMag)
        page.forEach { ingest(it) }
        return page.size
    }

    suspend fun ingest(
        incoming: Quake,
        rules: List<AlertRule> = DEFAULT_RULES,
        home: GeoPoint? = null,
    ) {
        val window = dao.pageBefore(
            timeMillis = incoming.timeMillis + WINDOW_MS,
            limit = 50,
            minMag = null,
        ).filter { it.timeMillis >= incoming.timeMillis - WINDOW_MS }
        val previousById = dao.byId(incoming.id)
        val result = dedupe.reconcile(window, incoming)
        val previous = previousById ?: result.replacesId?.let { dao.byId(it) }
            ?: dao.byId(result.canonical.id)?.takeIf { it.id != incoming.id }
        result.replacesId?.let { dao.delete(it) }
        dao.upsert(result.canonical)
        alerts.evaluate(previous, result.canonical, rules, home)?.let { _alertEvents.tryEmit(it) }
    }

    private companion object {
        const val FEED_ETAG_KEY = "feed_etag"
        const val WINDOW_MS = 90_000L
    }
}
```

Supporting DAO additions (add to `QuakeDao` + `Quake.sq` in the same commit):

```sql
-- append to Quake.sq
delete:
DELETE FROM quake WHERE id = ?;
```

```kotlin
// add to QuakeDao
fun delete(id: String) = db.quakeQueries.delete(id)
fun metaGet(key: String): String? = db.quakeQueries.meta_get(key).executeAsOneOrNull()
fun metaPut(key: String, value: String) { db.quakeQueries.meta_put(key, value) }
```

Note for implementer: `dao.recent()` returns rows filtered by a fixed `sinceMillis` computed at call time — acceptable for Plan 1; Plan 2 re-queries on a timer.

- [ ] **Step 4: Run green (repository + full module)**

Run: `./gradlew :core:data:jvmTest && ./gradlew :core:database:jvmTest`
Expected: PASS everywhere (database module re-tested because DAO gained methods).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Add QuakeRepository — single ingest path for poll, live, archive

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 10: Thin vertical slice — live feed list on all three targets

**Files:**
- Modify: `composeApp/build.gradle.kts` (add core deps + koin + lifecycle)
- Create: `composeApp/src/commonMain/kotlin/com/yugma/terrawatch/di/AppModule.kt`
- Create: `composeApp/src/commonMain/kotlin/com/yugma/terrawatch/feed/FeedViewModel.kt`
- Create: `composeApp/src/commonMain/kotlin/com/yugma/terrawatch/feed/FeedScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/yugma/terrawatch/App.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/yugma/terrawatch/MainActivity.kt` (Koin init with Android driver)
- Modify: `composeApp/src/jvmMain/kotlin/com/yugma/terrawatch/main.kt` (Koin init with JVM driver)
- Modify: `composeApp/src/wasmJsMain/kotlin/com/yugma/terrawatch/main.kt` (Koin init with in-memory repo fallback)
- Create: `composeApp/src/commonTest/kotlin/com/yugma/terrawatch/feed/FeedViewModelTest.kt` (runs on jvm)

**Interfaces:**
- Consumes: `QuakeRepository`, `RefreshStatus`, `Quake`, `magnitudeBand`
- Produces: `FeedViewModel(repository): ViewModel` exposing `val state: StateFlow<FeedUiState>`;
  `sealed interface FeedUiState { Loading; Content(quakes, isLive); Error(message) }` — Plan 2 replaces `FeedScreen` but keeps the ViewModel contract.

- [ ] **Step 1: Wire dependencies in `composeApp/build.gradle.kts`**

Add to `commonMain.dependencies`:
```kotlin
implementation(projects.core.model)
implementation(projects.core.network)
implementation(projects.core.database)
implementation(projects.core.data)
implementation(libs.koin.core)
implementation(libs.androidx.lifecycle.viewmodel)
implementation(libs.kotlinx.coroutines.core)
```
Add to `commonTest.dependencies`: `implementation(kotlin("test"))`, `implementation(libs.kotlinx.coroutines.test)`, `implementation(libs.turbine)`, `implementation(libs.ktor.client.mock)`.

- [ ] **Step 2: Write failing ViewModel test**

```kotlin
// composeApp/src/commonTest/kotlin/com/yugma/terrawatch/feed/FeedViewModelTest.kt
package com.yugma.terrawatch.feed

// This test compiles in commonTest but executes via :composeApp:jvmTest.
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeedViewModelTest {
    @Test fun `loads feed into content state`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = FeedViewModel(repository = fakeRepositoryWithOneQuake())
        vm.state.test {
            assertIs<FeedUiState.Loading>(awaitItem())
            val content = awaitItem()
            assertIs<FeedUiState.Content>(content)
            assertTrue(content.quakes.isNotEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
// fakeRepositoryWithOneQuake(): build a real QuakeRepository over an in-memory JVM SQLDelight driver
// with MockEngine returning one-feature GeoJSON — reuse the pattern from QuakeRepositoryTest.
// If jvm-only classes leak into commonTest, move this file to composeApp/src/jvmTest/. That is acceptable.
```

- [ ] **Step 3: Run red**

Run: `./gradlew :composeApp:jvmTest`
Expected: FAIL — `FeedViewModel` unresolved.

- [ ] **Step 4: Implement ViewModel, screen, DI, entry-point wiring**

```kotlin
// composeApp/src/commonMain/kotlin/com/yugma/terrawatch/feed/FeedViewModel.kt
package com.yugma.terrawatch.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugma.terrawatch.data.QuakeRepository
import com.yugma.terrawatch.data.RefreshStatus
import com.yugma.terrawatch.model.Quake
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface FeedUiState {
    data object Loading : FeedUiState
    data class Content(val quakes: List<Quake>, val isLive: Boolean) : FeedUiState
    data class Error(val message: String) : FeedUiState
}

class FeedViewModel(private val repository: QuakeRepository) : ViewModel() {
    private val _state = MutableStateFlow<FeedUiState>(FeedUiState.Loading)
    val state: StateFlow<FeedUiState> = _state

    init {
        viewModelScope.launch {
            val status = repository.refreshFeed()
            if (status == RefreshStatus.FAILED) {
                _state.value = FeedUiState.Error("Couldn't reach USGS")
            }
            repository.startLive(viewModelScope)
            repository.recentQuakes().collect { quakes ->
                _state.value = FeedUiState.Content(quakes, isLive = true)
            }
        }
    }
}
```

```kotlin
// composeApp/src/commonMain/kotlin/com/yugma/terrawatch/feed/FeedScreen.kt
package com.yugma.terrawatch.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun FeedScreen(viewModel: FeedViewModel) {
    val state by viewModel.state.collectAsState()
    when (val s = state) {
        FeedUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is FeedUiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(s.message, style = MaterialTheme.typography.bodyLarge)
        }
        is FeedUiState.Content -> LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
            items(s.quakes, key = { it.id }) { q ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(q.place, style = MaterialTheme.typography.bodyLarge)
                        Text("depth ${q.depthKm ?: "?"} km", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("M ${q.mag ?: "?"}", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
```

```kotlin
// composeApp/src/commonMain/kotlin/com/yugma/terrawatch/di/AppModule.kt
package com.yugma.terrawatch.di

import com.yugma.terrawatch.data.QuakeRepository
import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.feed.FeedViewModel
import com.yugma.terrawatch.network.EmscLiveSource
import com.yugma.terrawatch.network.UsgsApi
import io.ktor.client.HttpClient
import kotlinx.datetime.Clock
import org.koin.core.module.Module
import org.koin.dsl.module

// Platform entry points supply: HttpClient (engine differs) and QuakeDao (driver differs).
fun appModule(http: HttpClient, dao: QuakeDao): Module = module {
    single { UsgsApi(http) }
    single { EmscLiveSource(http) }
    single { dao }
    single { QuakeRepository(get(), get(), get(), clock = { Clock.System.now().toEpochMilliseconds() }) }
    factory { FeedViewModel(get()) }
}
```

Update `App.kt` to take the ViewModel and show `FeedScreen`:

```kotlin
// composeApp/src/commonMain/kotlin/com/yugma/terrawatch/App.kt
package com.yugma.terrawatch

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.yugma.terrawatch.feed.FeedScreen
import com.yugma.terrawatch.feed.FeedViewModel

@Composable
fun App(feedViewModel: FeedViewModel) {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) { FeedScreen(feedViewModel) }
    }
}
```

Entry points (Android/JVM start Koin with their driver + engine; wasm uses an error-screen fallback until Plan 3):

```kotlin
// androidMain MainActivity.kt — replace class body
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = QuakeDao(createDatabase(DriverFactory(applicationContext)))
        val koin = startKoin { modules(appModule(HttpClient(OkHttp), dao)) }.koin
        setContent { App(koin.get()) }
    }
}
// imports: io.ktor.client.engine.okhttp.OkHttp, org.koin.core.context.startKoin,
//          com.yugma.terrawatch.database.{DriverFactory, QuakeDao, createDatabase}, com.yugma.terrawatch.di.appModule
```

```kotlin
// jvmMain main.kt — replace body
fun main() {
    val dao = QuakeDao(createDatabase(DriverFactory()))
    val koin = startKoin { modules(appModule(HttpClient(CIO), dao)) }.koin
    application {
        Window(onCloseRequest = ::exitApplication, title = "TerraWatch") { App(koin.get()) }
    }
}
```

```kotlin
// wasmJsMain main.kt — replace body: web persistence lands in Plan 3.
// Render a plain Text("TerraWatch web — data layer arrives in Plan 3") instead of App().
```

- [ ] **Step 5: Run green + build all targets**

Run: `./gradlew :composeApp:jvmTest && ./gradlew :composeApp:assembleDebug :composeApp:wasmJsBrowserDistribution`
Expected: tests PASS; all targets build.

- [ ] **Step 6: Manual smoke — desktop shows real live data**

Run: `./gradlew :composeApp:run`
Expected: window lists real recent earthquakes (place + magnitude) within ~5 s. Leave open 2 min; if EMSC pushes an event it appears without restart. Close.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Thin vertical slice: live quake feed list on desktop and android

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 11: CI — GitHub Actions test + build pipeline

**Files:**
- Create: `.github/workflows/ci.yml`
- Create: `README.md`

**Interfaces:**
- Consumes: all Gradle tasks above
- Produces: green check on every push; the badge later tasks reference

- [ ] **Step 1: Write workflow**

```yaml
# .github/workflows/ci.yml
name: CI
on:
  push:
    branches: [main]
  pull_request:

jobs:
  test:
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 17 }
      - uses: gradle/actions/setup-gradle@v4
      - name: Unit tests (core + app)
        run: ./gradlew :core:model:jvmTest :core:network:jvmTest :core:database:jvmTest :core:data:jvmTest :composeApp:jvmTest
      - name: Build all targets
        run: ./gradlew :composeApp:assembleDebug :composeApp:jvmJar :composeApp:wasmJsBrowserDistribution
```

- [ ] **Step 2: Write README**

```markdown
# TerraWatch

Live earthquake monitor. Kotlin Multiplatform + Compose Multiplatform (Android · Desktop · Web).

Data: USGS realtime feeds + FDSN archive, EMSC WebSocket live stream. Free APIs, no keys.

## Run
- Desktop: `./gradlew :composeApp:run`
- Android: `./gradlew :composeApp:assembleDebug` (or Run in Android Studio)
- Web: `./gradlew :composeApp:wasmJsBrowserDevelopmentRun`

## Test
`./gradlew :core:model:jvmTest :core:network:jvmTest :core:database:jvmTest :core:data:jvmTest :composeApp:jvmTest`

## Docs
- Spec: `docs/superpowers/specs/2026-08-08-terrawatch-design.md`
- Plans: `docs/superpowers/plans/`
```

- [ ] **Step 3: Push and verify CI green**

Run: `git add -A && git commit -m "Add CI workflow and README

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>" && git push && gh run watch --exit-status || gh run view --log-failed`
Expected: workflow concludes success. If it fails, fix before proceeding — Plan 1 is done only when CI is green.

---

## Self-Review (performed at write time)

1. **Spec coverage (Plan 1 scope):** data sources §6.1 → Tasks 3–5; dedupe §6.2 → Task 7; schema §6.3 → Task 6; single-upsert flow §6.4 → Task 9; alert engine §6.5 → Task 8; module layout §5.1 → Tasks 2–9 respect the dependency rule; state pattern §5.2 → Task 10 ViewModel; CI §9 → Task 11. UI/UX, History/Insights screens, monetization, notifications = Plans 2–4 by design.
2. **Placeholder scan:** no TBDs. Two deliberate deferrals are explicit and scoped: wasm DB driver (Task 6 note + Task 10 wasm fallback, lands Plan 3), EMSC reconnect integration test (Plan 2). Fixture-dependent constants have exact verification commands.
3. **Type consistency:** `Quake` fields match across model/DAO/engines; `QuakeDao` gains `delete`/`metaGet`/`metaPut` in Task 9 and the SQL is provided; `FeedResult` names match between Task 5 api and Task 9 repository; `DEFAULT_RULES` ids (`near`, `world`) match tests.
