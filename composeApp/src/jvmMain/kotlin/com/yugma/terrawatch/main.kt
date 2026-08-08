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
import io.ktor.client.plugins.websocket.WebSockets
import kotlin.time.Clock
import org.koin.core.context.startKoin
import org.maplibre.compose.desktop.DesktopRuntimeOptions
import org.maplibre.compose.desktop.MapLibre
import org.maplibre.compose.desktop.ProvideMapHost
import org.maplibre.compose.desktop.desktopCachePath
import org.maplibre.compose.desktop.rememberAwtComposeGpuHost

@OptIn(kotlin.time.ExperimentalTime::class)
fun main() {
    val dao = QuakeDao(createDatabase(DriverFactory()), clock = { Clock.System.now().toEpochMilliseconds() })
    val http = HttpClient(CIO) { install(WebSockets) }
    startKoin { modules(appModule(http, dao, LocationProvider())) }
    // SPIKE (Task 6): maplibre-compose desktop requires a process-wide native runtime config
    // installed before any map is created, plus a per-window GPU host (see "Set up Desktop (JVM)"
    // in the maplibre-compose docs).
    MapLibre.configure(DesktopRuntimeOptions(cachePath = desktopCachePath("com.yugma.terrawatch")))
    application {
        Window(onCloseRequest = ::exitApplication, title = "TerraWatch") {
            ProvideMapHost(host = rememberAwtComposeGpuHost(window)) { App() }
        }
    }
}
