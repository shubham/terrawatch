package com.yugma.terrawatch

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.yugma.terrawatch.database.InMemoryQuakeStore
import com.yugma.terrawatch.di.appModule
import com.yugma.terrawatch.location.LocationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import org.koin.core.context.startKoin
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

// Task 9 (Plan 3): web enablement. Replaces the Task 12/Plan 2 WebPlaceholder() hotfix (deleted
// below — its own kdoc is now obsolete: web's data layer is no longer "a separate, still-unpaid
// Plan 3 debt", it's this file) with the SAME real Koin-wired App() every other target renders.
//
// Storage: InMemoryQuakeStore, not a real SqlDriver — see QuakeStore.kt's and
// InMemoryQuakeStore.kt's own kdoc for the 30-minute storage-decision spike (SQLDelight's
// web-worker driver needs generateAsync=true, confirmed too invasive by actually flipping the flag
// and recompiling core:database) that sanctioned this fallback. task-9-report.md has the full
// timing/evidence record.
//
// Network: the Js ktor engine (browser fetch/XHR + native WebSocket) — the one platform-specific
// choice this file makes, mirroring jvmMain's CIO/androidMain's OkHttp construction exactly
// (WebSockets + HttpTimeout, same 30s ping / 15s request / 10s connect figures). Whether the
// browser's own CORS/Zscaler-trust reality actually lets USGS/EMSC traffic through is a runtime
// question no amount of source-reading answers — see task-9-report.md's Browser Verify section for
// what was actually observed live, not assumed.
//
// LocationProvider(): the wasmJs actual (LocationProvider.wasmJs.kt) always returns null — real
// browser geolocation is a future task, not this one (LocationAskDialog already degrades to
// "Choose city"-only via canRequestLocation() == false on this target, unchanged by this file).
//
// Fix (found live in the browser, not anticipated): InMemoryQuakeStore's `clock` constructor param
// defaults to `{ 0L }` (same default QuakeDao itself carries) — omitting it here left
// lastFetchedAtMillis() permanently reporting epoch zero, rendered as the staleness banner's
// "Updated Jan 1" instead of a real time. jvmMain's main.kt/MainActivity.kt both pass a real
// `Clock.System.now().toEpochMilliseconds()` when constructing their own QuakeDao; this is the
// identical fix for InMemoryQuakeStore.
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTime::class)
fun main() {
    val http = HttpClient(Js) {
        install(WebSockets) { pingIntervalMillis = 30_000 }
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
        }
    }
    val store = InMemoryQuakeStore(clock = { Clock.System.now().toEpochMilliseconds() })
    startKoin { modules(appModule(http, store, LocationProvider())) }
    ComposeViewport(viewportContainerId = "app") { App() }
}
