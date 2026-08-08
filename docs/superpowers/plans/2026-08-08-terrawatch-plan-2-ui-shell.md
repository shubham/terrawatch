# TerraWatch Plan 2: UI Shell Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Calm Guardian UI — map-first Home with live animated quake pins, feed sheet, status pill, and Detail sheet — on Android (device-verified), Desktop, and Web (fallback allowed), with Plan 1's four entry-condition debts paid first.

**Architecture:** New `core:ui` module (design system) + screens in `composeApp` (feature modules deferred until a second screen family exists — YAGNI). Map via maplibre-compose 0.13.0 + OpenFreeMap vector tiles. All UI consumes `QuakeRepository` flows; no new data paths.

**Tech Stack:** everything from Plan 1, plus: maplibre-compose 0.13.0 (`org.maplibre.compose`), koin-compose-viewmodel (koin 4.1.0 family), kotlinx-coroutines-swing (already present).

## Global Constraints

- Branch: `feat/plan-2-ui-shell` off `main`. All Plan 1 global constraints carry (package root, minSdk 26/compileSdk 36, TDD in core/logic, commit trailer `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`, per-target compiles per touched module).
- **Real-device verification (user mandate): every task that changes visible UI ends with install + interact + screenshot on the physical device (`adb -s 98bc1cd8`, OnePlus 9R) AND emulator-5554.** OxygenOS blocks `pm clear`/forced rotation via shell — fresh-install cases run on the emulator; rotation on the device is manual or emulator-forced.
- Design tokens are LAW (spec §4.2): Ink #17222E · Canvas #F6FAF9 · Water #D9E9F4 · Land #EFF3EC · Safe #2FA36B · magnitude LOW #59B87D / MODERATE #F5A524 / STRONG #F0663B / MAJOR #C43A2F · Dusk canvas #10161D cards #1A222C · radii card 16 / sheet 22 / pill 99. Magnitude color never appears without the number.
- Glass (blur/translucency) allowed ONLY on: status pill, offline banner, bottom nav/sheet grabber-header. Never on content cards.
- Every screen renders all four states: Loading / Content / Empty / Error. No blank screens (Plan 1's lesson).
- maplibre-compose API details come from the Task 6 spike — later map tasks adapt call-sites to the spike's findings, but every interface WE own is specified here exactly.
- OpenFreeMap style base: `https://tiles.openfreemap.org/styles/liberty` (re-tint per tokens where the spike shows it's feasible; else accept default until Plan 3). Attribution "© OpenStreetMap contributors" must be visible on the map — non-negotiable (ODbL).

---

### Task 1: Pay debt #1+#2 — ingest off the main thread, serialized

**Files:**
- Modify: `core/data/src/commonMain/kotlin/com/yugma/terrawatch/data/QuakeRepository.kt`
- Test: `core/data/src/jvmTest/kotlin/com/yugma/terrawatch/data/QuakeRepositoryConcurrencyTest.kt`

**Interfaces:**
- Consumes: existing QuakeRepository.
- Produces: constructor gains `private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default` (LAST param, after `clock`, so existing positional call sites keep working — verify none break). `refreshFeed`, `loadArchivePage`, and `ingest` bodies run `withContext(ioDispatcher)`; `ingest`'s read-reconcile-write critical section is wrapped in a private `Mutex.withLock`. Public signatures otherwise unchanged.

- [ ] **Step 1: Write failing tests**

```kotlin
// core/data/src/jvmTest/kotlin/com/yugma/terrawatch/data/QuakeRepositoryConcurrencyTest.kt
package com.yugma.terrawatch.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.database.TerraWatchDb
import com.yugma.terrawatch.model.MagRevision
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import com.yugma.terrawatch.network.EmscLiveSource
import com.yugma.terrawatch.network.UsgsApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class QuakeRepositoryConcurrencyTest {
    private lateinit var dao: QuakeDao

    @BeforeTest fun setup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TerraWatchDb.Schema.create(driver)
        dao = QuakeDao(TerraWatchDb(driver))
    }

    private fun repo() = QuakeRepository(
        UsgsApi(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
        EmscLiveSource(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
        dao, clock = { 2_000_000 },
        ioDispatcher = Dispatchers.Default,   // real parallelism — the point of this test
    )

    private fun q(id: String, source: Source, lat: Double, updated: Long) =
        Quake(id, 1_950_000, lat, 126.5, 10.0, 5.5, "mw", "P", false, null,
            QuakeStatus.AUTOMATIC, mapOf(source to id),
            listOf(MagRevision(5.5, "mw", updated, source)), updated)

    @Test fun `20 concurrent ingests of the same twin pair yield exactly one row`() = runTest {
        val r = repo()
        // Two agencies' variants of one event, ingested concurrently many times.
        val usgs = q("us1", Source.USGS, 7.10, updated = 1_950_000)
        val emsc = q("e1", Source.EMSC, 7.14, updated = 1_960_000)
        (1..10).flatMap { i ->
            listOf(
                async(Dispatchers.Default) { r.ingest(usgs.copy(updatedAtMillis = 1_950_000 + i)) },
                async(Dispatchers.Default) { r.ingest(emsc.copy(updatedAtMillis = 1_960_000 + i)) },
            )
        }.awaitAll()
        assertEquals(1, dao.countAll(), "concurrent ingest must never leave duplicates")
    }

    @Test fun `ingest returns on caller thread but work ran on io dispatcher`() = runTest {
        // Behavioral proxy: repository built with an ioDispatcher that records usage.
        var used = false
        val recording = kotlinx.coroutines.CoroutineDispatcher.run {
            object : kotlinx.coroutines.CoroutineDispatcher() {
                override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                    used = true
                    Dispatchers.Default.dispatch(context, block)
                }
            }
        }
        val r = QuakeRepository(
            UsgsApi(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
            EmscLiveSource(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
            dao, clock = { 2_000_000 }, ioDispatcher = recording,
        )
        r.ingest(q("us9", Source.USGS, 7.0, 1_900_000))
        assertEquals(true, used, "ingest must hop to ioDispatcher")
        assertEquals(1, dao.countAll())
    }
}
```

- [ ] **Step 2: Run red** — `./gradlew :core:data:jvmTest --tests '*Concurrency*'` — FAIL: no `ioDispatcher` param (compile error). Note: the duplicate-row assertion would ALSO fail intermittently pre-fix; the compile error is the deterministic red.

- [ ] **Step 3: Implement** — add ctor param + `private val ingestMutex = Mutex()`; wrap `refreshFeed`/`loadArchivePage` bodies in `withContext(ioDispatcher) { ... }`; in `ingest`, `withContext(ioDispatcher) { ingestMutex.withLock { /* existing window→reconcile→replaceAndDelete→alerts */ } }`. Imports: `kotlinx.coroutines.sync.Mutex`, `kotlinx.coroutines.sync.withLock`, `kotlinx.coroutines.withContext`, `kotlinx.coroutines.CoroutineDispatcher`.

- [ ] **Step 4: Run green** — full `:core:data:jvmTest` (all prior tests + 2 new). Run the concurrency test 5× (`--rerun-tasks` loop) to shake flakes.

- [ ] **Step 5: Per-target compiles + commit** — `:core:data:compileKotlinJvm/:compileDebugKotlinAndroid/:compileKotlinWasmJs`; commit "Serialize ingest and move repository work off the caller thread".

---

### Task 2: Pay debt #4 — real clock for fetchedAtMillis + staleness query

**Files:**
- Modify: `core/database/src/commonMain/kotlin/com/yugma/terrawatch/database/QuakeDao.kt`
- Modify: `core/database/src/commonMain/sqldelight/com/yugma/terrawatch/database/Quake.sq`
- Test: `core/database/src/jvmTest/kotlin/com/yugma/terrawatch/database/QuakeDaoTest.kt` (extend)

**Interfaces:**
- Produces: `QuakeDao(db: TerraWatchDb, clock: () -> Long = { 0L })` — clock stamps `fetchedAtMillis` in `toRow()` at write time (both `upsert` and `replaceAndDelete` paths). New query + method `fun lastFetchedAtMillis(): Long?` (SQL: `SELECT MAX(fetchedAtMillis) FROM quake;` named `lastFetchedAt`) — powers the "updated N min ago" staleness chip. Default `{ 0L }` keeps existing tests compiling; composeApp wiring passes the real clock.

- [ ] **Step 1: Failing tests** — extend QuakeDaoTest: construct `QuakeDao(db, clock = { 42_000L })`, upsert any quake, assert `lastFetchedAtMillis() == 42_000L`; second test: two writes with clocks 42k then 99k → `lastFetchedAtMillis() == 99_000L` regardless of the quakes' own `updatedAtMillis`.
- [ ] **Step 2: Red** — compile error (no clock param / method).
- [ ] **Step 3: Implement** — ctor param, `toRow()` uses `clock()` for `fetchedAtMillis`, add SQL + method.
- [ ] **Step 4: Green + all database tests + per-target compiles.**
- [ ] **Step 5: Wire real clock in composeApp entry points** (`QuakeDao(db, clock = { Clock.System.now().toEpochMilliseconds() })` — `kotlin.time.Clock`) and in `appModule`. Build all targets.
- [ ] **Step 6: Commit** "fetchedAtMillis reflects local write time; expose lastFetchedAt for staleness".

---

### Task 3: Pay debt #3 — proper ViewModel wiring (kills rotation leak)

**Files:**
- Modify: `gradle/libs.versions.toml` (add `koin-compose-viewmodel = { module = "io.insert-koin:koin-compose-viewmodel", version.ref = "koin" }`)
- Modify: `composeApp/build.gradle.kts` (commonMain: koin-compose-viewmodel)
- Modify: `composeApp/src/commonMain/kotlin/com/yugma/terrawatch/App.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/yugma/terrawatch/MainActivity.kt`
- Modify: `composeApp/src/jvmMain/kotlin/com/yugma/terrawatch/main.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/yugma/terrawatch/di/AppModule.kt`

**Interfaces:**
- Produces: `App()` takes NO ViewModel param; it resolves via `org.koin.compose.viewmodel.koinViewModel<FeedViewModel>()` inside composition (scoped to the platform ViewModelStore — Android Activity's store survives rotation, one VM instance). `appModule` declares `viewModel { FeedViewModel(get()) }` (koin viewModel DSL) instead of `factory`. MainActivity: ALL construction (dao, http, startKoin) moves inside the `GlobalContext.getOrNull() == null` guard; `setContent { App() }`. Desktop main: `startKoin` then `application { Window { App() } }`.

- [ ] **Step 1: Failing check (behavioral, emulator)** — before changes, document the leak: launch on emulator, `adb -s emulator-5554 shell settings put system user_rotation 1` (emulator allows), grep logcat for a temporary `Log`/println marker in `startLive` — 2 collectors after rotation. (If marker plumbing is awkward: skip straight to implementation; the structural fix is deterministic. Note choice in report.)
- [ ] **Step 2: Implement wiring per Interfaces block.** Kotlin/wasm target of koin-compose-viewmodel: if the artifact lacks wasmJs, keep wasm entry on the placeholder path (it doesn't call `App()` anyway — verify, note).
- [ ] **Step 3: Verify** — emulator: rotate twice, logcat shows ONE live collector (add temporary marker, remove before commit — or assert via `adb shell dumpsys` process health + absence of duplicate WS attempts in logs). Real device: manual rotation (ask controller to have user rotate, or skip with note — OxygenOS shell-rotation blocked). All targets build; jvmTest green (FeedViewModelTest unaffected — it constructs the VM directly).
- [ ] **Step 4: Commit** "Scope FeedViewModel to ViewModelStore via koin-compose-viewmodel".

---

### Task 4: Debug-only trust for corp-proxy emulator + device demo reliability

**Files:**
- Create: `composeApp/src/androidMain/res/xml/network_security_config.xml`
- Create: `composeApp/src/debug/AndroidManifest.xml`
- Modify: `README.md` (Live data behind corporate proxies section)

**Interfaces:**
- Produces: DEBUG builds trust user-installed CAs; release builds untouched (system CAs only). Exact files:

```xml
<!-- composeApp/src/androidMain/res/xml/network_security_config.xml -->
<network-security-config>
    <base-config>
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
    <debug-overrides>
        <trust-anchors>
            <certificates src="system" />
            <certificates src="user" />
        </trust-anchors>
    </debug-overrides>
</network-security-config>
```

```xml
<!-- composeApp/src/debug/AndroidManifest.xml -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:networkSecurityConfig="@xml/network_security_config" />
</manifest>
```

(`debug-overrides` only activates when `android:debuggable` — belt-and-braces: config referenced from the debug manifest overlay only.)

- [ ] **Step 1: Add files, build `assembleDebug` AND `assembleRelease`** — both must succeed; inspect merged release manifest (`build/intermediates/merged_manifests/release/`) to confirm NO networkSecurityConfig attribute leaks into release.
- [ ] **Step 2: Emulator verification** — push + install the Zscaler CA as a user cert: `adb -s emulator-5554 push ~/.gradle/zscaler-root-ca.pem /sdcard/Download/zscaler.pem`, then controller/user installs via Settings → Security → Install from device (manual step — coordinate; if not feasible this session, document and mark verified-by-config-inspection).
- [ ] **Step 3: README section** — how live data works behind TLS-intercepting proxies (JVM truststore for desktop, user CA + debug build for Android; tests never need network).
- [ ] **Step 4: Commit** "Debug builds trust user CAs for TLS-intercepting proxy environments".

---

### Task 5: `core:ui` module — tokens, theme, formatting (TDD)

**Files:**
- Create: `core/ui/build.gradle.kts` (KMP + compose plugins: `composeMultiplatform`, `composeCompiler`, `androidLibrary`; targets android/jvm/wasmJs; deps: compose.runtime/foundation/material3/ui, `api(projects.core.model)`)
- Create: `core/ui/src/commonMain/kotlin/com/yugma/terrawatch/ui/theme/Tokens.kt`
- Create: `core/ui/src/commonMain/kotlin/com/yugma/terrawatch/ui/theme/TerraTheme.kt`
- Create: `core/ui/src/commonMain/kotlin/com/yugma/terrawatch/ui/format/Formats.kt`
- Test: `core/ui/src/jvmTest/kotlin/com/yugma/terrawatch/ui/format/FormatsTest.kt`
- Modify: `settings.gradle.kts` (`include(":core:ui")`)

**Interfaces:**
- Produces (consumed by every later task):

```kotlin
object TerraColors { // all androidx.compose.ui.graphics.Color
    val Ink = Color(0xFF17222E); val Canvas = Color(0xFFF6FAF9)
    val Water = Color(0xFFD9E9F4); val Land = Color(0xFFEFF3EC)
    val Safe = Color(0xFF2FA36B); val InfoBlue = Color(0xFF5C8DB8)
    val WarnInk = Color(0xFFB08A2E); val WarnBg = Color(0xFFFCF3DD)
    val MagLow = Color(0xFF59B87D); val MagModerate = Color(0xFFF5A524)
    val MagStrong = Color(0xFFF0663B); val MagMajor = Color(0xFFC43A2F)
    val DuskCanvas = Color(0xFF10161D); val DuskCard = Color(0xFF1A222C)
}
fun magnitudeColor(band: MagnitudeBand): Color  // UNKNOWN -> Ink at 40% alpha
object TerraRadii { val card = 16.dp; val sheet = 22.dp; val pill = 99.dp }
@Composable fun TerraTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit)
// Material3 ColorScheme mapped from tokens; typography: system sans, magnitude numerals bold

// Formats.kt — pure, fully TDD'd:
fun formatMagnitude(mag: Double?): String        // 6.1 -> "6.1", 6.0 -> "6.0", null -> "—", one decimal always
fun formatDepthKm(depthKm: Double?): String      // 31.1599998 -> "31.2 km", null -> "depth unknown", one decimal
fun formatRelativeTime(thenMillis: Long, nowMillis: Long): String
// <60s "just now"; <60m "N min ago"; <24h "N h ago"; <7d "N d ago"; else "MMM d" (e.g. "Aug 3", UTC ok for v1)
fun formatDistanceKm(km: Double): String         // 4102.3 -> "4,102 km" (thousands separator, 0 decimals)
```

- [ ] **Step 1: Module build file + include; failing FormatsTest** — table-driven tests for every rule above incl. boundaries (59s/60s, 59m/60m, 23h/24h, 6d/7d, null-handling, "6.05"→"6.1"/"6.0" rounding note: use `((mag * 10).roundToInt() / 10.0)` semantics; specify test values exactly: `formatMagnitude(6.049)=="6.0"`, `formatMagnitude(6.05)=="6.1"`).
- [ ] **Step 2: Red → implement Formats.kt → green.** No java.text/String.format (wasm) — hand-roll: `val d = ((v*10).roundToInt()); "${d/10}.${abs(d%10)}"` pattern; thousands separator via manual grouping.
- [ ] **Step 3: Tokens + TerraTheme** (no tests — compile + used by every subsequent task).
- [ ] **Step 4: Per-target compiles all 3 + commit** "Add core:ui — Calm Guardian tokens, theme, formatting".

---

### Task 6: maplibre-compose spike (DECISION GATE for web)

**Files:**
- Modify: `gradle/libs.versions.toml` (`maplibre-compose = { module = "org.maplibre.compose:maplibre-compose", version = "0.13.0" }` — bump to latest stable if newer)
- Modify: `composeApp/build.gradle.kts` (commonMain dep)
- Create: `composeApp/src/commonMain/kotlin/com/yugma/terrawatch/map/QuakeMap.kt` (minimal: render map with OpenFreeMap liberty style, centered 20°N 0°E zoom 1.5)
- Create: `docs/superpowers/plans/plan-2-spike-maplibre.md` (findings)

**Interfaces:**
- Produces: `@Composable fun QuakeMap(modifier: Modifier = Modifier)` — Task 8 extends its signature; keep a single map composable WE own so library API churn stays inside this file. Spike report MUST answer: (1) does it render on android? (2) desktop? (3) wasmJs — compiles? renders? pan/zoom? (4) how are runtime marker/symbol layers added in 0.13.0 (exact API names)? (5) style re-tint mechanism (style JSON patch vs layer paint overrides)? (6) attribution rendering?

- [ ] **Step 1: Add dep; if GitHub-Packages-only, add the repo per library docs** (Maven Central preferred — check; note which).
- [ ] **Step 2: Minimal QuakeMap + temporary debug route** — render behind a `showMapSpike` flag replacing FeedScreen temporarily on a branch commit (NOT merged UI).
- [ ] **Step 3: Verify android (emulator + REAL DEVICE screenshot), desktop run, wasm build+browser attempt.** Record exact findings in spike report, incl. the runtime-annotation API for Task 8.
- [ ] **Step 4: DECISION** — wasm: if map renders usably → web keeps map; else → web v1 = list+static-snapshot path (pre-approved spec §7). Write decision in spike doc; controller reviews before Task 8 dispatch.
- [ ] **Step 5: Commit** "maplibre-compose spike: android/desktop/wasm findings + decision".

---

### Task 7: Wire location + home coordinates (minimal, pill's dependency)

**Files:**
- Create: `core/data/src/commonMain/kotlin/com/yugma/terrawatch/data/HomeLocation.kt`
- Modify: `core/database` meta usage (no schema change — meta table)
- Create: `composeApp/src/androidMain/kotlin/com/yugma/terrawatch/location/LocationProvider.android.kt`
- Create: `composeApp/src/jvmMain/kotlin/com/yugma/terrawatch/location/LocationProvider.jvm.kt`
- Create: `composeApp/src/wasmJsMain/kotlin/com/yugma/terrawatch/location/LocationProvider.wasmJs.kt`
- Create: `composeApp/src/commonMain/kotlin/com/yugma/terrawatch/location/LocationProvider.kt` (expect)
- Test: `core/data/src/jvmTest/kotlin/com/yugma/terrawatch/data/HomeLocationTest.kt`

**Interfaces:**
```kotlin
// core:data — TDD'd store over meta table:
class HomeLocationStore(private val dao: QuakeDao) {
    fun get(): GeoPoint?                      // meta keys "home_lat"/"home_lon"
    fun set(point: GeoPoint)
}
// composeApp — expect/actual, best-effort one-shot:
expect class LocationProvider { suspend fun current(): GeoPoint? }
// android actual: ACCESS_COARSE_LOCATION runtime permission via Accompanist-free
//   ActivityResult API; null when denied. Manifest gains the permission.
// jvm actual: null (manual city picker is desktop's path — Plan 3 settings UI; for now null)
// wasm actual: null (browser geolocation Plan 3)
```
- Pill logic (Task 9) uses `HomeLocationStore.get() ?: LocationProvider.current()` (and stores a granted fix as home).

- [ ] **Step 1: TDD HomeLocationStore** (get on empty → null; set→get round-trip; overwrite works) against in-memory dao. Red → green.
- [ ] **Step 2: expect/actual LocationProvider; android permission flow; manifest `ACCESS_COARSE_LOCATION`.**
- [ ] **Step 3: Device verify** — first launch asks permission; deny → app fine; grant → fix acquired (log). Screenshot the permission dialog.
- [ ] **Step 4: All-target builds + commit** "Home location: meta-backed store + coarse location provider".

---

### Task 8: Home map with live pins (the centerpiece)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/yugma/terrawatch/map/QuakeMap.kt`
- Create: `composeApp/src/commonMain/kotlin/com/yugma/terrawatch/home/HomeViewModel.kt`
- Create: `composeApp/src/commonMain/kotlin/com/yugma/terrawatch/home/HomeScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/yugma/terrawatch/App.kt` (App renders HomeScreen)
- Test: `composeApp/src/jvmTest/kotlin/com/yugma/terrawatch/home/HomeViewModelTest.kt`

**Interfaces:**
```kotlin
data class QuakePin(val id: String, val lat: Double, val lon: Double,
                    val mag: Double?, val band: MagnitudeBand, val isNew: Boolean)
sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Content(
        val pins: List<QuakePin>, val quakes: List<Quake>,
        val isLive: Boolean, val lastUpdatedMillis: Long?,
        val refreshFailed: Boolean,          // true -> offline/staleness banner over map
    ) : HomeUiState
    // NOTE: no Error terminal state on Home — map always renders; failure is a banner. Empty == Content(empty pins).
}
class HomeViewModel(repository: QuakeRepository, dao-lastFetched via repository helper) : ViewModel {
    val state: StateFlow<HomeUiState>
    val newQuakeIds: SharedFlow<String>      // drives pin-drop animation, fed from repository.alertEvents? NO —
    // new dedicated flow: repository exposes `insertedQuakeIds: SharedFlow<String>` (add in this task,
    // emitted from ingest() when previous == null — TDD in core:data first, 2 tests: new emits, update doesn't)
}
// QuakeMap grows: fun QuakeMap(pins: List<QuakePin>, newQuakeId: String?, onPinTap: (String) -> Unit, modifier)
// pin size 8+band.ordinal*4 dp-equivalent, color magnitudeColor(band), cluster at zoom<3 if spike showed API
```

- [ ] **Step 1: TDD `insertedQuakeIds` in QuakeRepository** (core:data): new quake → id emitted; revision/update → NOT emitted. Red → green → per-target compiles.
- [ ] **Step 2: TDD HomeViewModel** (jvmTest, same fake-repo pattern as FeedViewModelTest): Loading → Content; refreshFailed=true when FAILED; pins mapped with correct bands; lastUpdated populated.
- [ ] **Step 3: HomeScreen composition** — QuakeMap full-bleed; offline/staleness glass banner top (uses formatRelativeTime + lastFetchedAt) when refreshFailed or stale>10min; NO other chrome yet (pill/sheet next task). Adapt QuakeMap internals to spike findings.
- [ ] **Step 4: Verify on REAL DEVICE + emulator: pins visible on live data, colors match bands, tap logs id.** Desktop run. Wasm per spike decision. Screenshots into the report (device REQUIRED).
- [ ] **Step 5: Commit** "Home: full-bleed map with live magnitude-banded pins".

---

### Task 9: Status pill + feed sheet

**Files:**
- Create: `core/ui/src/commonMain/kotlin/com/yugma/terrawatch/ui/components/StatusShield.kt` (pill: safe/alert/ask variants, glass, shape-morph corner animation safe⇄alert)
- Create: `core/ui/src/commonMain/kotlin/com/yugma/terrawatch/ui/components/QuakeCard.kt`
- Create: `core/ui/src/commonMain/kotlin/com/yugma/terrawatch/ui/components/MagnitudeBadge.kt`
- Create: `core/ui/src/commonMain/kotlin/com/yugma/terrawatch/ui/components/StalenessChip.kt`
- Create: `composeApp/src/commonMain/kotlin/com/yugma/terrawatch/home/FeedSheet.kt`
- Modify: `HomeScreen.kt`, `HomeViewModel.kt`
- Test: `core/data` pill-logic function (pure, TDD): `nearestSignificant(quakes, home, sinceMillis): Quake?` + `core/ui` no new pure logic beyond what's tested

**Interfaces:**
```kotlin
// core:data (TDD): the pill's brain — pure:
data class PillStatus(val kind: Kind, val quake: Quake?) { enum class Kind { CALM, ALERT, ASK_LOCATION } }
fun pillStatus(quakes: List<Quake>, home: GeoPoint?, nowMillis: Long,
               radiusKm: Double = 500.0, windowMs: Long = 86_400_000, minMag: Double = 4.5): PillStatus
// home==null -> ASK_LOCATION; quake within radius+window+minMag -> ALERT(nearest by distance); else CALM
// MagnitudeBadge(mag: Double?, band: MagnitudeBand, size: BadgeSize) — rounded square, band color bg, WHITE bold number
// QuakeCard(quake, distanceKm: Double?, nowMillis, onClick) — white card, badge left, place+meta, radius 16
// FeedSheet: Material3 BottomSheetScaffold NOT used — custom draggable sheet? NO: use
//   BottomSheetScaffold with sheetPeekHeight = 30% of maxHeight; half-expand via standard behavior; full = expanded.
//   Compromise accepted (M3 gives 2-3 detents, not arbitrary): peek + expanded for v1; 55% detent only if free.
// Header: grabber, "LIVE" + pulsing dot when isLive, "N NEW" chip cleared on expand.
```

- [ ] **Step 1: TDD `pillStatus`** — 6 tests: no home→ASK; calm; alert nearest-wins; boundary radius; boundary mag; stale-window exclusion. Red→green.
- [ ] **Step 2: Components in core:ui** (compile-verified; visual verify step 4).
- [ ] **Step 3: FeedSheet + HomeScreen integration** — pill top-center overlaying map (glass), sheet bottom with QuakeCards (uses formatRelativeTime/DepthKm/DistanceKm), ad-slot placeholder Spacer(50dp) reserved above nav-less bottom (Plan 4 fills it).
- [ ] **Step 4: REAL DEVICE matrix** — screenshots: calm pill (no local quake), sheet peek/expanded, LIVE dot present, card fields formatted (no float garbage), dark mode (Dusk) toggle via device settings. Emulator: same. Desktop: two-pane NOT yet — sheet ok on desktop this task.
- [ ] **Step 5: Commit** "Status pill + feed sheet over the live map".

---

### Task 10: New-quake animation + live badge truth

**Files:**
- Modify: `QuakeMap.kt` (pin drop: spring scale-in overshoot + 2 expanding fading rings on `newQuakeId`)
- Modify: `core/network/.../EmscLiveSource.kt` (+ `val connected: StateFlow<Boolean>` flipped inside webSocket block / on catch)
- Modify: `QuakeRepository` (expose `val liveConnected: StateFlow<Boolean>` pass-through)
- Modify: `HomeViewModel` (isLive = real state), FeedSheet NEW badge wiring
- Test: core:network jvmTest — connected flag: initial false; (structure-level test acceptable: flag flips are inside the reconnect loop; test via a fake WS is Plan-2-integration scope — minimum: initial value + type; note honestly)

**Interfaces:** `EmscLiveSource.connected: StateFlow<Boolean>` (false initial, true on session open, false on drop). `QuakeRepository.liveConnected` mirrors it. HomeUiState.isLive binds to it — the Task 10 TODO from Plan 1 dies here.

- [ ] **Step 1: connected flag + repository pass-through + red/green what's testable.**
- [ ] **Step 2: Pin animation** — `Animatable` scale 0→1.15→1 spring on newQuakeId match; rings via Canvas circles alpha/scale animation; reduced-motion: skip (respect a `LocalReducedMotion` composition local defaulting to system where obtainable, else false).
- [ ] **Step 3: Device verify** — WS events are rare on demand; verify via debug hook: long-press on map (debug builds only) injects a fake quake through `ingest()` → pin drops with animation ON THE REAL DEVICE. Screenshot/screenrecord (`adb shell screenrecord`, 10s, pull).
- [ ] **Step 4: Commit** "Live pin-drop animation + truthful LIVE indicator".

---

### Task 11: Detail sheet

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/yugma/terrawatch/detail/DetailSheet.kt`
- Create: `core/ui` bits: `RevisionBadge.kt`, `TsunamiBanner.kt`, `StatRow.kt`
- Modify: `HomeScreen.kt` (pin tap / card tap → ModalBottomSheet with quake id), `HomeViewModel` (selectedQuake StateFlow + select(id)/dismiss())
- Test: `HomeViewModelTest` extend: select populates from dao byId; revision text derived correctly ("revised from M 5.9" uses second-latest revision — pure fn `revisionNote(revisions): String?` TDD in core:ui or core:data)

**Interfaces:**
```kotlin
fun revisionNote(revisions: List<MagRevision>, nowMillis: Long): String?
// null when <2 distinct mags; else "revised from M {prev} · {relative time of latest}"
// DetailSheet(quake, distanceKm?, nowMillis, onShare, onDismiss)
// Share: android actual = ACTION_SEND text "M 6.1 — Mindanao, Philippines. Depth 10 km. via TerraWatch";
// desktop/web actual = copy to clipboard.
expect fun shareQuakeText(text: String)  // composeApp platform sets
```
Content per mockup: magnitude hero block (band color, big number), place, absolute+relative time, revision badge (amber), stat trio (depth/distance/felt), tsunami banner (green not-expected / red advisory when `tsunami==true`), coordinates row, source row ("USGS · confirmed by EMSC" when sources has both), Share/Dismiss.

- [ ] **Step 1: TDD `revisionNote`** (none, one mag only, revised up, revised down, uses latest two distinct). Red→green.
- [ ] **Step 2: DetailSheet + selection wiring + share expect/actual.**
- [ ] **Step 3: REAL DEVICE verify** — tap pin → sheet; every field present + formatted; tsunami banner states (fake-inject a tsunami=true quake via debug hook); share intent opens chooser (screenshot); dismiss returns to map cleanly.
- [ ] **Step 4: Commit** "Quake detail sheet with revision honesty and share".

---

### Task 12: Desktop two-pane + web per spike decision

**Files:**
- Modify: `HomeScreen.kt` — `BoxWithConstraints`: width ≥ 900.dp → Row(map weight 1f, right panel 360.dp fixed: pill top + feed list + detail replaces list on selection); else phone layout
- Modify: `composeApp/src/wasmJsMain/kotlin/com/yugma/terrawatch/main.kt` — **SPIKE DECISION (recorded 2026-08-08): maplibre-compose publishes NO wasmJs artifact, and its desktop target demands a JDK 25 runtime (incompatible with our JDK 17 toolchain + Gradle 8.14). Therefore: web AND desktop both use `FallbackMapPane` (one shared implementation: bundled world-map PNG + Compose-drawn pins); live desktop map deferred to Plan 3 (entry-condition note). Android is the only live-map target in Plan 2 — it is also the only judged target.** Web keeps placeholder main until Task 12 wires the fallback screen.
- Test: pure breakpoint fn `layoutMode(widthDp): LayoutMode` TDD (PHONE <900, TWO_PANE ≥900)

- [ ] **Step 1: TDD layoutMode. Step 2: two-pane composition. Step 3: desktop run — resize window across 900dp, verify swap; screenshots. Step 4: wasm path per decision + browser screenshot. Step 5: commit** "Adaptive two-pane desktop; web per spike decision".

---

### Task 13: Compose UI tests on REAL DEVICE (user mandate) + full device matrix

**Files:**
- Modify: `composeApp/build.gradle.kts` — androidTest setup: `androidTestImplementation(compose.uiTest)`, `androidTestImplementation(libs.androidx.compose.ui.test.manifest)` (add catalog entry `androidx-compose-ui-test-manifest = { module = "androidx.compose.ui:ui-test-manifest", version = "1.8.3" }` — bump to current stable), `defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"` + catalog `androidx-test-runner`
- Create: `composeApp/src/androidInstrumentedTest/kotlin/com/yugma/terrawatch/ui/ComponentsTest.kt`
- Create: `composeApp/src/androidInstrumentedTest/kotlin/com/yugma/terrawatch/ui/HomeFlowTest.kt`

**Interfaces:** tests use `createComposeRule()` with direct composable content (no Activity dependency on DI): MagnitudeBadge shows number + band color; QuakeCard shows formatted fields (feed a Quake with depth 31.1599998 → asserts "31.2 km" on screen); StatusShield three variants render their texts; DetailSheet full render with revision badge + tsunami banner; pillStatus-driven variant switch. HomeFlowTest (DI-backed, fake repository module): Loading→Content, offline banner appears when refreshFailed.

- [ ] **Step 1: Gradle androidTest wiring; write ComponentsTest (red impossible pre-UI — these pin behavior; run on device).**
- [ ] **Step 2: Run ON THE PHYSICAL DEVICE: `./gradlew :composeApp:connectedDebugAndroidTest` with only 98bc1cd8 connected (or `ANDROID_SERIAL=98bc1cd8`). All green. Then same on emulator-5554.**
- [ ] **Step 3: Manual matrix re-run on device (screenshots archived in repo `docs/qa/plan-2-device-matrix/`):** live map+pins · pill calm · sheet peek/expand · detail via pin tap · share sheet · dark mode · offline relaunch (cached, banner) · airplane fresh-ish (emulator for pm-clear case) · rotation ×2 (manual rotate — verify single WS collector via logcat marker absence and no crash) · debug-hook pin-drop animation (screenrecord).
- [ ] **Step 4: Commit** "Compose instrumentation tests green on physical device + QA matrix".

---

### Task 14: CI + docs close-out

**Files:**
- Modify: `.github/workflows/ci.yml` — add `:core:ui:jvmTest` to unit-test task list; bump `actions/setup-java@v5`; (connected tests stay local — document)
- Modify: `README.md` — screenshots (device shots from Task 13), feature list, architecture note
- Modify: `docs/superpowers/plans/plan-2-entry-conditions.md` — mark paid debts ✅ with commit refs

- [ ] **Step 1: CI edit + README + entry-conditions checkoff. Step 2: push, watch run green. Step 3: commit+merge flow via finishing-a-development-branch (controller).**

---

## Self-Review (write time)

1. **Coverage vs promise:** debts 1-4 → Tasks 1-3 (+2 covers clock, 4 proxy); design system → 5; map → 6/8; pill+sheet → 9; animation+LIVE truth → 10; detail → 11; desktop/web → 12; device testing mandate → every task's verify + dedicated 13; CI → 14. Spec §4 tokens/motion/glass rules embedded as Global Constraints.
2. **Placeholders:** maplibre call-sites intentionally spike-deferred (Task 6 gates 8/10) — the interfaces WE own are exact; library adaptation documented as the spike's explicit product. M3 sheet detent compromise stated, not hidden. No TBDs elsewhere.
3. **Type consistency:** QuakePin/HomeUiState/PillStatus/pillStatus/insertedQuakeIds/liveConnected/formatters cross-referenced between Tasks 5/8/9/10/11/13 — names match. `HomeLocationStore` (Task 7) consumed in Task 9 pill wiring via home param.
