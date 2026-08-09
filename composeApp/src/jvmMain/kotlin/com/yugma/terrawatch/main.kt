package com.yugma.terrawatch

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.yugma.terrawatch.database.DriverFactory
import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.database.createDatabase
import com.yugma.terrawatch.di.appModule
import com.yugma.terrawatch.location.LocationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import kotlin.time.Clock
import org.koin.core.context.startKoin

@OptIn(kotlin.time.ExperimentalTime::class)
fun main() {
    val dao = QuakeDao(createDatabase(DriverFactory()), clock = { Clock.System.now().toEpochMilliseconds() })
    // Fix Round 1 (I1): see MainActivity.kt's matching change / EmscLiveSource.kt's kdoc for why
    // a WS ping is required to detect a dead socket that never delivers a TCP-level close.
    // Task 2 (Plan 3), release hygiene (F12): see MainActivity.kt's matching HttpTimeout change —
    // same hard ceiling on a hung request/connect attempt, applied to this client too.
    val http = HttpClient(CIO) {
        install(WebSockets) { pingIntervalMillis = 30_000 }
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
        }
    }
    startKoin { modules(appModule(http, dao, LocationProvider())) }
    application {
        Window(onCloseRequest = ::exitApplication, title = "TerraWatch") { App() }
    }
}
