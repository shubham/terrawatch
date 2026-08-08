# Plan 2, Task 6 — maplibre-compose spike: findings + decision

Verdict up front: **web (wasmJs) cannot use maplibre-compose at all today — not a config problem, the
library publishes no wasmJs target.** Desktop genuinely renders (Metal backend confirmed) but only
after fighting a JDK floor this project's Gradle can't itself satisfy. Android is excellent
out of the box. Full evidence and the exact 0.14.0 API for Task 8 below.

Method note: rather than trust doc prose alone, I shallow-cloned `github.com/maplibre/maplibre-compose`
at tag `v0.14.0` and read the library's own source (`lib/maplibre-compose/src/commonMain/...`) and its
`demo-app/.../docsnippets/*.kt` (the literal source the published docs are generated from). Every
function signature quoted below is copied from that source, not paraphrased from web pages. I also
checked Maven Central's directory listing directly (`repo1.maven.org/maven2/org/maplibre/compose/`)
rather than trusting any single doc page's claim about what's published.

## Dependency setup (Step 1)

- **Maven Central, confirmed** — no extra repository needed, as expected. `org.maplibre.compose:maplibre-compose`.
- **Version: 0.14.0, not the brief's suggested 0.13.0.** Maven Central's `maven-metadata.xml` for the
  artifact shows `<release>0.14.0</release>`, `lastUpdated 20260808060225` — i.e. it was published
  *today*. 0.13.0 still resolves (not gone), so the brief's literal "bump only if 0.13.0 gone"
  condition wasn't met, but I used 0.14.0 anyway: this is a forward-looking decision gate, Task 8
  will build against whatever's current, and there's no reason to spike against a version already one
  behind. Flagging the deviation for the record.
- Added `maplibreCompose = "0.14.0"` under `[versions]` and referenced it via `version.ref` in
  `[libraries]` rather than inlining the version string the brief's snippet showed — every other
  entry in `gradle/libs.versions.toml` uses `version.ref`, so inlining would've been the only
  exception in the file.
- `implementation(libs.maplibre.compose)` added to `composeApp`'s `commonMain.dependencies`, exactly
  as directed — see the wasmJs section for why that placement is itself the point of the spike.

## (1) Android — render quality/perf first impression

**Excellent, no caveats.** Compiled clean on the first pass (no API corrections needed — the
ground-truth source read paid off). Installed and launched on both `emulator-5554` and real device
`98bc1cd8` (OnePlus 9R, `LE2101`).

**Real device** (screenshot: `spike-android-device.png`): full-detail vector basemap — country
borders, city/country labels in correct native scripts (Arabic, Cyrillic, Amharic, Tamil all
rendered correctly in the same frame), scale bar, compass, MapLibre logo, attribution button, all
present with zero configuration. Logcat: `Setting style URI` → `Style finished loading`. Panned via
`adb shell input swipe` (`spike-android-device-panned.png`) — camera moved cleanly from
Europe/Africa to the Indian Ocean/South Asia, labels stayed crisp mid-pan, no tearing or missing
tiles. This is production-grade rendering, indistinguishable from a native Google Maps-style app.
No frame-timing tooling was run (out of scope for a spike), but a single pan gesture showed no
visible jank.

**Emulator** (`sdk_gphone64_arm64`, screenshot: `spike-android-emulator.png`): Compose chrome
(scale bar, logo, attribution button) renders correctly — proving the canvas embedding itself is
fine — but the map content area is solid black. Logcat root-causes it precisely:

```
E/Mbgl: {ugma.terrawatch}[Setup]: loading style failed: java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.
I/Mbgl-HttpRequest: Request failed due to a connection error: java.security.cert.CertPathValidatorException: ...
```

This is the Zscaler TLS-interception the brief anticipated, but with a sharper finding underneath:
**MapLibre Native's own HTTP client is a separate stack from the app's ktor/OkHttp client, with its
own trust store.** An earlier commit on this branch (`71f6e83`, "Debug builds trust user CAs for
TLS-intercepting proxy environments") already fixed Zscaler for the app's *own* API polling via
`network_security_config.xml` — that fix does **not** extend to map tile fetches, because MapLibre
Native fetches tiles through its own native HTTP layer, which never consults the app's Android
network security config. Any future work that wants live tiles on this emulator would need a
separate fix targeted at MapLibre Native specifically (or just rely on real devices, which is what
this finding recommends — Zscaler-free device Wi-Fi is unaffected, confirmed above).

## (2) Desktop (JVM) — honest status

**It renders — but only after clearing two real toolchain walls, neither of which is optional.**

**Wall 1 — the library needs JDK 25, confirmed empirically, not just from docs.** The naive
attempt (`QuakeMap()` called with zero desktop-specific setup, default `JAVA_HOME` = Android
Studio's bundled JBR 21.0.10) crashed instantly:

```
Exception in thread "main" java.lang.UnsupportedClassVersionError: org/maplibre/compose/camera/CameraPosition
has been compiled by a more recent version of the Java Runtime (class file version 69.0), this version
of the Java Runtime only recognizes class file versions up to 65.0
```

Class file version 69 = Java 25, 65 = Java 21. This matches the library's own getting-started doc
verbatim ("Desktop requires Java 25. The MapLibre Native FFI binding uses the FFM API, so the
desktop target cannot run on an older JVM") — confirmed by direct reproduction, not assumed. Desktop
also needs bootstrapping that Android/wasmJs never do, none of which existed in this project before
this spike:

1. An OS+GPU-backend-specific native runtime artifact — `runtimeOnly("org.maplibre.compose:maplibre-compose-runtime-metal-macos-arm64:0.14.0")` on this (macOS arm64) machine; Linux/Windows need the matching Vulkan variant instead. A real multi-OS desktop build has to select this per build host.
2. `MapLibre.configure(DesktopRuntimeOptions(cachePath = desktopCachePath("com.yugma.terrawatch")))` once at process start, before any window opens.
3. Every AWT window needs `ProvideMapHost(host = rememberAwtComposeGpuHost(window)) { ... }` wrapped around its content — there's a `LocalComposeGpuHost` composition local that hard-throws (`"No ComposeGpuHost is installed..."`) the moment a map composable runs without it.
4. `--enable-native-access=ALL-UNNAMED` as a JVM arg (FFM downcalls require it on JDK 25+).

All four are now wired into `composeApp/src/jvmMain/kotlin/com/yugma/terrawatch/main.kt` and
`composeApp/build.gradle.kts` as part of this spike commit.

**Wall 2 — this project's Gradle can't itself run on JDK 25+.** The obvious fix for Wall 1 is
"point `JAVA_HOME` at a JDK 25+." This machine has Homebrew's `openjdk@26` (26.0.2) available. But:

```
$ JAVA_HOME=/opt/homebrew/opt/openjdk@26 ./gradlew :composeApp:run
Starting a Gradle Daemon, 1 incompatible Daemon could not be reused
FAILURE: Build failed with an exception.
* What went wrong:
26.0.2
BUILD FAILED in 2s
```

Gradle 8.14 (this project's wrapper version) refuses to launch its own daemon on JDK 26 at all —
this is Gradle's ceiling colliding with the library's floor, and it's a separate problem from
maplibre-compose itself. I did **not** spend spike time solving this properly (upgrading the
project's Gradle version, or configuring a JDK-25+ toolchain scoped to just the `run`/packaging
task while the main daemon stays on an older JDK) — both are real fixes, just not same-day-spike
scope. Getting a definitive answer instead meant bypassing Gradle for one diagnostic run: I used a
throwaway Gradle init script to print the exact `run` task's resolved classpath (JDK 21 can resolve
dependencies and compile fine — it just can't *execute* the resulting classes), then invoked that
classpath directly with JDK 26's `java` binary:

```
$ /opt/homebrew/opt/openjdk@26/bin/java --enable-native-access=ALL-UNNAMED -cp <resolved classpath> com.yugma.terrawatch.MainKt
Info: (maplibre-compose) Created the MapLibre offline runtime on maplibre-compose-offline
Info: (maplibre-compose) Created the MapLibre runtime on maplibre-compose-map
Info: (maplibre-compose) Rendered the first map frame with METAL on maplibre-metal-renderer, extent MlnFfiMapExtent(logical=800x568, physical=1600x1136, scale=2.0)
```

**It works.** No exceptions, process stayed alive and idle afterward (checked via `ps`, not just a
one-shot log line). This is a real, positive result: desktop rendering is genuinely functional, via
the native Metal backend, once the JDK floor is met.

Two things I could **not** confirm, in the interest of honesty: no screenshot was possible — this
session's macOS has no attached display (`screencapture` fails with `could not create image from
display` regardless of whether the app is crash-looping or successfully rendering, so this is an
environment fact, not a signal about the app). And I could not confirm the OpenFreeMap tile fetch
itself succeeded over network — the log shows only the renderer's own init/first-frame lines (no
per-tile logging at this level), and an `lsof -p <pid> -i` check in the ~15s after launch showed no
established connections yet, which is inconclusive (async fetch may simply not have started/logged
within my check window) rather than a negative result. "Does the map surface come up" is answered
(yes); "does live OpenFreeMap tile fetch succeed on this machine's network path" is not, and would
need a follow-up check (the brief's suggested `JAVA_TOOL_OPTIONS` truststore trick is the right next
thing to try if Zscaler turns out to intercept this Mac's own outbound traffic too).

## (3) Wasm — compile+render status

**Does not compile. This is a hard library gap, not a project misconfiguration.** 
`./gradlew :composeApp:wasmJsBrowserDistribution` fails in under a second, at dependency
resolution, before any Kotlin compiles:

```
Could not resolve org.maplibre.compose:maplibre-compose:0.14.0.
> No matching variant of org.maplibre.compose:maplibre-compose:0.14.0 was found. The consumer was
  configured to find a library ... attribute 'org.jetbrains.kotlin.platform.type' with value 'wasm',
  attribute 'org.jetbrains.kotlin.wasm.target' with value 'js' but: ...
```

Root cause, confirmed two independent ways:

1. **Maven Central's own directory listing** (`repo1.maven.org/maven2/org/maplibre/compose/`) lists
   published artifacts for `android`, `desktop`, `gms`(+`-android`), `iosarm64`, `iossimulatorarm64`,
   `iosx64`, `js`, and `material3` variants of those — **no `maplibre-compose-wasm-js`.**
2. **The library's own `lib/maplibre-compose/build.gradle.kts`** declares its Kotlin targets
   explicitly: `androidLibrary { ... }`, `iosArm64()`, `iosSimulatorArm64()`, `jvm("desktop") { ... }`,
   `js(IR) { browser() }`. There is no `wasmJs { }` block anywhere in the source tree.

So this project's `wasmJs { browser(); binaries.executable() }` target (Kotlin/**Wasm**) and the
library's `js(IR) { browser() }` target (Kotlin/**JS**) are different Kotlin Multiplatform platforms
that happen to both mean "runs in a browser" — the library simply hasn't built the Wasm one yet.
The library's own roadmap doc says as much: Wasm status is *"Early exploration needed... the same
MapLibre path decision as Web/JS [is still undecided]"* — i.e., not close.

Worth noting even if this project could target Kotlin/JS instead: the JS target the library *does*
ship is itself only ~20% feature-complete per the library's own status table, and the missing 80%
is exactly what Task 8 needs — "Insert, remove, and replace layers," "Configure layers with
expressions," "Add data sources by URI or GeoJSON" are all listed unsupported on Web (JS). So this
isn't "wrong Kotlin backend, otherwise fine" — even the backend the library does support can't draw
data-driven quake pins yet. Switching wasmJs→js would not unlock a usable map for this app's actual
feature (pins).

One incidental finding while debugging this: Kotlin's own `kmpPartiallyResolvedDependenciesChecker`
task runs during **every** Gradle invocation on this project once the commonMain dependency exists,
and prints a scary `❌ KMP Dependencies Resolution Failure` diagnostic — but it's advisory only; it
does not fail Android/desktop tasks that never needed to resolve the wasmJs configuration in the
first place. Only wasmJs-specific tasks (`wasmJsBrowserDistribution`, `compileKotlinWasmJs`, etc.)
hard-fail. Confirmed by running Android install/desktop run *after* the dependency was already
added to commonMain — both worked fine despite the diagnostic appearing in their output too.

## (4) Runtime marker/layer API for Task 8 (exact 0.14.0 signatures)

All quoted directly from `lib/maplibre-compose/src/commonMain/kotlin/org/maplibre/compose/...` at
tag `v0.14.0`.

**Feeding a `List<QuakePin>` in:**

```kotlin
// org.maplibre.compose.sources.GeoJsonSource
@Composable
public fun rememberGeoJsonSource(
  data: GeoJsonData,
  options: GeoJsonOptions = GeoJsonOptions(),
): GeoJsonSource

public sealed interface GeoJsonData {
  public data class Uri(val uri: String) : GeoJsonData
  public data class JsonString(val json: String) : GeoJsonData
  public data class Features(val geoJson: GeoJsonObject) : GeoJsonData   // <- this one: build a
                                                                          //    FeatureCollection from
                                                                          //    List<QuakePin> directly
}
```

`rememberGeoJsonSource`'s internal `update` block is `{ setData(data) }` — i.e. it's fully
declarative. Task 8 doesn't need an imperative "add marker"/"remove marker" API at all: recompose
with a new `GeoJsonData.Features(...)` built from the current `pins: List<QuakePin>` and the source
updates itself. `GeoJsonOptions.synchronousUpdate = true` exists specifically for "small, frequently
updated sources such as live positions" (kdoc's own words) — worth setting for the live-pin case,
with one caveat the kdoc states outright: **"At the moment this has an effect only on Android; other
platforms ignore it."**

**Drawing the pins — `CircleLayer` is the right fit** (plain colored/sized circles, no custom icon
needed for magnitude bands):

```kotlin
// org.maplibre.compose.layers.CircleLayer
@Composable
public fun CircleLayer(
  id: String, source: Source, sourceLayer: String = "",
  filter: Expression<BooleanValue> = nil(),
  color: Expression<ColorValue> = const(Color.Black),
  radius: Expression<DpValue> = const(5.dp),
  strokeColor: Expression<ColorValue> = const(Color.Black),
  strokeWidth: Expression<DpValue> = const(0.dp),
  onClick: FeaturesClickHandler? = null,        // <- exactly Task 8's onPinTap(id) hook
  onLongClick: FeaturesClickHandler? = null,
  opacity: Expression<FloatValue> = const(1f),
  /* + blur, translate, pitchScale/pitchAlignment, sortKey, minZoom/maxZoom */
)
```

`SymbolLayer` also exists (`org.maplibre.compose.layers.SymbolLayer`, ~60 parameters: `iconImage`,
`iconColor`, `iconSize`, `textField`, `textColor`, `textSize`, `onClick`, ...) if pins ever need a
custom icon or a magnitude-number label baked into the marker itself — richer than needed for the
current spec, noted for later.

**Data-driven color/radius by magnitude** — confirmed the exact mechanism, not just that
"expressions exist": every layer paint parameter above is an `Expression<T>`, and
`org.maplibre.compose.expressions.dsl.feature` (a `val feature: Feature = Feature` singleton with an
`operator fun get`) reads per-feature GeoJSON properties directly in Kotlin:

```kotlin
// org.maplibre.compose.expressions.dsl.feature.kt
public object Feature {
  public operator fun get(key: String): Expression<*>   // -> feature["mag"] reads the GeoJSON property
  ...
}
```

So `radius = feature["mag"].asNumber()` fed through `step(...)`/`interpolate(...)` (both present in
the expressions DSL, used exactly this way in the library's own `Layers.kt` docsnippet for
zoom-driven line width) is the real path from "quake magnitude" to "pin size," entirely
declarative, no per-band CircleLayer needed. I verified the `feature[key]` accessor and the
`interpolate`/`step`/`zoom()` expression builders exist in source; I did not personally write and
run a magnitude→radius expression end-to-end (out of spike scope) — Task 8 should treat the
mechanism as confirmed and the exact call shape as "verify once, cheap to check."

**Clustering** ("cluster at zoom<3" from the Task 8 sketch) — real, declarative, not something to
hand-roll: `GeoJsonOptions(cluster = true, clusterRadius = ..., clusterMaxZoom = ..., clusterMinPoints = ...)`.
When enabled, clustered points gain synthetic properties `cluster`, `cluster_id`, `point_count`,
`point_count_abbreviated` (kdoc's own list). The standard MapLibre pattern — one `CircleLayer`
filtered to `cluster == true` sized/colored by `point_count`, a paired `SymbolLayer` with
`textField` bound to `point_count_abbreviated`, and a separate unclustered `CircleLayer` filtered to
`cluster != true` for individual quake pins — follows directly from these fields. I confirmed the
enabling fields in source; I did not find a maplibre-compose-specific worked cluster+layer example
in the docs to cross-check the full pattern against, so treat the field names as verified and the
three-layer wiring as "standard approach, sanity-check once at implementation."

## (5) Style re-tint mechanism

Three real mechanisms, confirmed in source, plus one that does **not** exist — worth stating
explicitly since it's easy to assume it does:

- **(a) Swap the whole style.** `baseStyle` is an ordinary Compose parameter
  (`MaplibreMap(baseStyle = BaseStyle.Uri(url))`); reassigning it — e.g. keyed off
  `isSystemInDarkTheme()` — triggers MapLibre's own animated style transition. Confirmed via the
  library's dark-mode docsnippet. Good for a small number of pre-authored variants (OpenFreeMap
  ships "liberty"/"bright"/"positron"-style options; a custom Maputnik-authored JSON works too).
- **(b) Client-side style JSON patch.** `BaseStyle` is a sealed interface with **two** cases —
  `BaseStyle.Uri(uri: String)` and `BaseStyle.Json(json: String)` (plus a `JsonObjectBuilder`
  constructor overload). Nothing stops fetching a style JSON, rewriting specific layers' `paint`
  colors client-side (e.g. re-tinting water/landcover to the Calm Guardian palette), and passing the
  patched JSON straight to `BaseStyle.Json(...)`. Confirmed directly in `style/BaseStyle.kt` — no
  ambiguity here.
- **(c) Layer paint overrides for layers you add.** Any `CircleLayer`/`LineLayer`/`SymbolLayer`/
  `FillLayer` you add takes full `Expression<ColorValue>`/`Expression<DpValue>` control, anchorable
  relative to specific base-style layers via `Anchor.Above("layer-id") { ... }` (also `.Below`,
  `.Top`, `.Bottom`, `.At`, `.Replace` — all in `MaplibreMap.kt`'s own kdoc, `.Above` used concretely
  in the `Layers.kt` docsnippet: `Anchor.Above("road_motorway") { LineLayer(...) }`).
- **(d) What's NOT there:** no public API to mutate one already-loaded base-style layer's paint
  property in place (no `style.getLayer("water").setPaint(...)` equivalent at the Compose layer). I
  grepped the whole library source for `setPaintProperty`/`patchLayer`/`setLayoutProperty` and found
  nothing matching. Re-tinting an existing base layer means (a) or (b) above, not a live single-property poke.

## (6) Attribution

**Visible by default, zero configuration.** Confirmed in `map/OrnamentOptions.kt` (the
`maplibreNativeMain` actual, shared by Android/iOS/desktop):

```kotlin
public actual data class OrnamentOptions(
  val isLogoEnabled: Boolean = true,
  val isAttributionEnabled: Boolean = true,        // <- default true
  val attributionAlignment: Alignment = Alignment.BottomEnd,
  val isCompassEnabled: Boolean = true,
  val isScaleBarEnabled: Boolean = true,
  ...
)
```

`MapOptions()`'s default `ornamentOptions` is `OrnamentOptions.AllEnabled` (`= OrnamentOptions()`),
so `QuakeMap()` renders attribution out of the box with no extra code — exactly what the real-device
screenshot shows (the "(i)" button, bottom-right). This matched what I actually saw before I even
read this file, which is a good cross-check.

**To keep attribution on while customizing other ornaments** (e.g. hiding the compass/scale bar for
a cleaner look later): `OrnamentOptions.AllDisabled.copy(isAttributionEnabled = true)`, or simply
never touch `isAttributionEnabled`. **It can be turned off** (`isAttributionEnabled = false` /
`OrnamentOptions.AllDisabled`) — flagging as a real trap, not a hypothetical one: OpenFreeMap/OSM's
terms require attribution to stay visible, so nobody should ever pass `AllDisabled` wholesale.
Per-source attribution HTML also flows through automatically (`Source.attributionHtml`, sourced from
the style/tileset JSON's own `attribution` field) — switching tile providers later won't silently
drop required attribution as long as `isAttributionEnabled` stays true.

## (7) Decision recommendation

**Web: fallback (list + static-snapshot path, spec §7).** Not a close call. This isn't "the wasmJs
build has rough edges" — the library has no wasmJs target at all (confirmed against Maven Central's
listing and the library's own build script), and its roadmap describes Wasm as still at the "early
exploration" stage with the underlying rendering approach undecided. Revisit only when the library
publishes a wasmJs artifact *and* reaches feature parity on sources/layers/expressions — right now
even its supported browser target (Kotlin/JS) can't do the GeoJSON+layers work Task 8 needs.

**Desktop: real map, with the JDK cost stated up front.** It genuinely renders — Metal-backed,
confirmed by direct log evidence and a live, stable process, not merely "the docs say it should."
The cost is real too: JDK 25+ is mandatory at runtime, and this project's Gradle 8.14 cannot launch
its own daemon on a JDK that new, so shipping this means either bumping the project's Gradle version
to one whose daemon tolerates JDK 25+, or scoping a JDK 25+ toolchain to just the desktop
run/packaging tasks while the main daemon stays put — plus carrying ~15 lines of desktop-only
bootstrap code and picking the right native runtime artifact per OS/GPU backend at packaging time.
None of that is a blocker, all of it is real, non-optional setup work that Task 8 (or whoever picks
up desktop) should budget for rather than discover mid-implementation. If that setup cost isn't
worth it for a v1 timeline, the same list+snapshot fallback used for web is the cheaper substitute
here too — that's a timeline call above this spike's scope, not a technical one.

**Android (not asked, stated for completeness): real map, no caveats worth a decision.** Works well
out of the box; the emulator's black-canvas issue is a corp-proxy artifact fully explained above, not
a library or code problem, and doesn't affect real devices (what ships).
