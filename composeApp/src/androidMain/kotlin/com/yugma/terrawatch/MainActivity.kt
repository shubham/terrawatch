package com.yugma.terrawatch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.yugma.terrawatch.database.DriverFactory
import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.database.createDatabase
import com.yugma.terrawatch.di.appModule
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = QuakeDao(createDatabase(DriverFactory(applicationContext)))
        val http = HttpClient(OkHttp) { install(WebSockets) }
        // Guard against a second startKoin() call if the Activity is ever recreated in the same
        // process (e.g. a config change) — Koin throws KoinAppAlreadyStartedException otherwise.
        val koin = (GlobalContext.getOrNull() ?: startKoin { modules(appModule(http, dao)) }.koin)
        setContent { App(koin.get()) }
    }
}
