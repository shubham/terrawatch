package com.yugma.terrawatch

import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.yugma.terrawatch.database.DriverFactory
import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.database.createDatabase
import com.yugma.terrawatch.di.appModule
import com.yugma.terrawatch.location.LocationProvider
import com.yugma.terrawatch.share.initShareContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.time.Clock
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

class MainActivity : ComponentActivity() {
    // Built once per Activity instance (not per onCreate/Koin-guard branch — applicationContext is
    // stable across a config-change recreate anyway, and the permission callback below needs a
    // reference regardless of whether this particular onCreate() call ends up starting Koin).
    private val locationProvider by lazy { LocationProvider(applicationContext) }

    // Coarse-location runtime permission (Task 7). Per the ActivityResultContracts contract this
    // MUST be registered before the Activity reaches STARTED — a property initializer (running
    // during Activity construction, before onCreate) satisfies that, whereas registering from
    // inside onCreate's body would still technically work here too, but a field keeps the launcher
    // reusable/testable and matches the common Android idiom. The callback fires asynchronously
    // whenever the user answers the OS dialog. Task 7's scope is only proving the ask-then-read
    // pipeline end to end — Task 9's Pill is what wires a granted fix into HomeLocationStore/the UI.
    //
    // current() is called (and logged) unconditionally, regardless of grant/deny: LocationProvider
    // already guards its own permission check internally, so calling it on the deny path is safe by
    // design and is exactly what proves — on device, via logcat, not just by code inspection — that
    // a denial degrades to a clean null instead of a crash.
    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Log.d(LOCATION_LOG_TAG, "permission ${if (granted) "granted" else "denied"}")
        CoroutineScope(Dispatchers.Main).launch {
            Log.d(LOCATION_LOG_TAG, "current()=${locationProvider.current()}")
        }
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Guard against a second startKoin() call if the Activity is ever recreated in the same
        // process (e.g. a config change) — Koin throws KoinAppAlreadyStartedException otherwise.
        // Reused below for the permission ask too: "first launch" means once per process, not once
        // per onCreate() — without this, a rotation would re-launch the request every time (a no-op
        // dialog-wise once already granted/denied, but a pointless repeat ask/log all the same).
        val firstLaunchThisProcess = GlobalContext.getOrNull() == null
        // ALL construction lives inside this guard now: on a rotation, onCreate() runs again, but
        // Koin (and the singletons it holds — QuakeRepository, etc.) is built exactly once per
        // process. HomeViewModel itself is no longer built here at all — App() resolves it via
        // koinViewModel(), which binds to this Activity's ViewModelStore (survives rotation) rather
        // than being newly constructed on every onCreate().
        if (firstLaunchThisProcess) {
            // Task 11: the Share button's Android actual needs a Context but shareQuakeText's
            // expect/actual signature can't carry one (see Share.kt's kdoc) - this is the one-time
            // wiring that substitutes for it, same "build/wire the platform-specific bit once here"
            // spirit as the dao/http/locationProvider construction right below.
            initShareContext(applicationContext)
            val dao = QuakeDao(createDatabase(DriverFactory(applicationContext)), clock = { Clock.System.now().toEpochMilliseconds() })
            // Fix Round 1 (I1): pingIntervalMillis makes ktor send a WS ping frame on this cadence
            // and expect a pong back — see EmscLiveSource.kt's kdoc for why this is required, not
            // just tidy: without it, a dead socket that never sends a TCP-level close (silently
            // dropped by a NAT/carrier/proxy) is invisible to `for (frame in incoming)`, which
            // simply never receives another frame and never throws — `connected` (and therefore
            // the LIVE indicator) stays true forever over a socket that is actually gone.
            val http = HttpClient(OkHttp) { install(WebSockets) { pingIntervalMillis = 30_000 } }
            startKoin { modules(appModule(http, dao, locationProvider)) }
        }
        setContent { App() }
        if (firstLaunchThisProcess) {
            // Ask AFTER content is showing, not before — a permission dialog racing the first frame
            // would otherwise cover a still-blank window. Activity-level by design (not a
            // LaunchedEffect from App()): the ask is one-shot Activity plumbing, independent of
            // whatever composable happens to be on screen (currently the Task 6 map spike; see
            // App.kt).
            requestLocationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    private companion object {
        const val LOCATION_LOG_TAG = "TerraWatchLocation"
    }
}
