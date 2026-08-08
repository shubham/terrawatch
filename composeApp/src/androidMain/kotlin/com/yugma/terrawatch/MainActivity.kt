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
import kotlin.time.Clock
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

class MainActivity : ComponentActivity() {
    @OptIn(kotlin.time.ExperimentalTime::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Guard against a second startKoin() call if the Activity is ever recreated in the same
        // process (e.g. a config change) — Koin throws KoinAppAlreadyStartedException otherwise.
        // ALL construction lives inside this guard now: on a rotation, onCreate() runs again, but
        // Koin (and the singletons it holds — QuakeRepository, etc.) is built exactly once per
        // process. FeedViewModel itself is no longer built here at all — App() resolves it via
        // koinViewModel(), which binds to this Activity's ViewModelStore (survives rotation) rather
        // than being newly constructed on every onCreate().
        if (GlobalContext.getOrNull() == null) {
            val dao = QuakeDao(createDatabase(DriverFactory(applicationContext)), clock = { Clock.System.now().toEpochMilliseconds() })
            val http = HttpClient(OkHttp) { install(WebSockets) }
            startKoin { modules(appModule(http, dao)) }
        }
        setContent { App() }
    }
}
