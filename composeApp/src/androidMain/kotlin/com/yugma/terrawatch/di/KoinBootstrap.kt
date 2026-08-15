package com.yugma.terrawatch.di

import android.content.Context
import com.yugma.terrawatch.database.DriverFactory
import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.database.createDatabase
import com.yugma.terrawatch.location.LocationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

private val koinBootstrapLock = Any()

/**
 * Plan 4 Task 3: factored out of `MainActivity.onCreate`'s own former inline block — byte-for-byte
 * the same `dao`/`http` construction + `startKoin` call that block already did, just given a name
 * so a SECOND caller can share it instead of duplicating it.
 *
 * That second caller is `AlertDigestWorker.doWork()`: WorkManager can start this app's process
 * purely to run a scheduled periodic job, with `MainActivity` never created at all in that run —
 * `Application.onCreate()` always runs first regardless of which entry point woke the process up,
 * but this app has no custom `Application` subclass (no `android:name` on the manifest's
 * `<application>`), so nothing was starting Koin in that headless path before this. Rather than add
 * an `Application` subclass just to move the SAME start-once call one layer up, the worker calls
 * this function itself at the top of `doWork()` — cheap and correct, since [GlobalContext.getOrNull]
 * already makes it a no-op on the far more common "app was already running" path.
 *
 * Guarded twice: the pre-existing `GlobalContext.getOrNull() == null` short-circuit (unchanged from
 * `MainActivity`'s own prior inline check) AND a `synchronized` block — defends the narrow
 * theoretical race of `MainActivity.onCreate` (Main thread) and a WorkManager-started process's
 * first `doWork()` (a WorkManager executor thread) both reaching this at the same process-cold-start
 * instant. Practically near-impossible in one session, but the lock costs nothing to add.
 */
@OptIn(ExperimentalTime::class)
fun ensureKoinStarted(context: Context, locationProvider: LocationProvider) {
    synchronized(koinBootstrapLock) {
        if (GlobalContext.getOrNull() != null) return
        val appContext = context.applicationContext
        val dao = QuakeDao(createDatabase(DriverFactory(appContext)), clock = { Clock.System.now().toEpochMilliseconds() })
        val http = HttpClient(OkHttp) {
            install(WebSockets) { pingIntervalMillis = 30_000 }
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 10_000
            }
        }
        startKoin { modules(appModule(http, dao, locationProvider)) }
    }
}
