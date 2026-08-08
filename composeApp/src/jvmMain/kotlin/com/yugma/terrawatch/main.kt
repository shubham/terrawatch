package com.yugma.terrawatch

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.yugma.terrawatch.database.DriverFactory
import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.database.createDatabase
import com.yugma.terrawatch.di.appModule
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlin.time.Clock
import org.koin.core.context.startKoin

@OptIn(kotlin.time.ExperimentalTime::class)
fun main() {
    val dao = QuakeDao(createDatabase(DriverFactory()), clock = { Clock.System.now().toEpochMilliseconds() })
    val http = HttpClient(CIO) { install(WebSockets) }
    startKoin { modules(appModule(http, dao)) }
    application {
        Window(onCloseRequest = ::exitApplication, title = "TerraWatch") { App() }
    }
}
