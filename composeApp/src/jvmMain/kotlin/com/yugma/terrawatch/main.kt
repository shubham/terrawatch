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
import org.koin.core.context.startKoin

fun main() {
    val dao = QuakeDao(createDatabase(DriverFactory()))
    val http = HttpClient(CIO) { install(WebSockets) }
    val koin = startKoin { modules(appModule(http, dao)) }.koin
    application {
        Window(onCloseRequest = ::exitApplication, title = "TerraWatch") { App(koin.get()) }
    }
}
