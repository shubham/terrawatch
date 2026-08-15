package com.yugma.terrawatch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.yugma.terrawatch.alerts.AlertDigestWorker
import com.yugma.terrawatch.alerts.enqueueAlertDigestWorker
import com.yugma.terrawatch.alerts.initAlertDigestSchedulerContext
import com.yugma.terrawatch.data.HomeLocationStore
import com.yugma.terrawatch.di.ensureKoinStarted
import com.yugma.terrawatch.location.LocationProvider
import com.yugma.terrawatch.location.bindLocationRequestLauncher
import com.yugma.terrawatch.notifications.bindNotificationPermissionController
import com.yugma.terrawatch.notifications.computeNotificationPermissionCondition
import com.yugma.terrawatch.notifications.currentNotificationRationale
import com.yugma.terrawatch.notifications.markNotificationPermissionAsked
import com.yugma.terrawatch.notifications.openNotificationSettings
import com.yugma.terrawatch.share.initShareContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

class MainActivity : ComponentActivity() {
    // Built once per Activity instance (not per onCreate/Koin-guard branch — applicationContext is
    // stable across a config-change recreate anyway, and the permission callback below needs a
    // reference regardless of whether this particular onCreate() call ends up starting Koin).
    private val locationProvider by lazy { LocationProvider(applicationContext) }

    // Task 2 (Plan 3): resolved through Koin, unlike locationProvider above — this class is
    // entirely Koin-built (`single { HomeLocationStore(get()) }`, AppModule.kt) with no
    // Context/Activity dependency of its own, so there's no reason for MainActivity to construct it
    // directly the way it must for locationProvider. `by lazy` matters here, not just style: this
    // initializer calls into Koin's GlobalContext, which the firstLaunchThisProcess guard in
    // onCreate() below doesn't start until partway through — deferring the lookup until this
    // property is actually first READ (the grant callback below, which can only fire once the OS
    // permission dialog has been answered, well after onCreate has returned) guarantees startKoin()
    // has always already run by then.
    private val homeLocationStore: HomeLocationStore by lazy { GlobalContext.get().get() }

    // Plan 4 Task 3: the notification tap-through deep link — a non-null id means MainActivity was
    // opened (cold `onCreate`, or a `singleTask` re-front via `onNewIntent`) from a digest
    // notification's own PendingIntent. Compose `mutableStateOf`, not a bare `var`: `onNewIntent`
    // can fire AFTER `setContent {}` has already composed once (the Activity is already showing,
    // singleTask just re-fronts it with a new Intent) — a plain field mutation wouldn't recompose
    // anything on its own, but a State write does.
    private var pendingQuakeId by mutableStateOf<String?>(null)

    // Coarse-location runtime permission (Task 7). Per the ActivityResultContracts contract this
    // MUST be registered before the Activity reaches STARTED — a property initializer (running
    // during Activity construction, before onCreate) satisfies that, whereas registering from
    // inside onCreate's body would still technically work here too, but a field keeps the launcher
    // reusable/testable and matches the common Android idiom. The callback fires asynchronously
    // whenever the user answers the OS dialog.
    //
    // Task 2 (Plan 3), release hygiene (F2): this used to unconditionally Log.d both the grant/deny
    // outcome AND the resolved fix's raw coordinates on every device, release builds included (a
    // location leak into logcat), via a bare, never-joined/cancelled `CoroutineScope(Dispatchers.
    // Main)` — a structured-concurrency smell independent of the logging problem. Replaced outright:
    // a grant now writes straight into [HomeLocationStore] via [lifecycleScope] (tied to this
    // Activity's own lifecycle, unlike the old bare scope), which is what actually closes the
    // location loop — [com.yugma.terrawatch.home.HomeViewModel.homeLocation] collects
    // [HomeLocationStore.updates] and flips the ASK pill to CALM/ALERT in-session the moment this
    // write lands, no restart needed (see that ViewModel's own kdoc). A denial writes nothing and
    // logs nothing — there is no fix to store, and per this task's device verification, nothing
    // about a denial belongs in logcat either.
    //
    // Task 3 (Plan 3) carry-in — the Task 2 ledger minor: launched on [Dispatchers.Default], not
    // bare [lifecycleScope.launch] (which would run on Main). [LocationProvider.current] reads a
    // system location service and [HomeLocationStore.set] is a synchronous SQLDelight DAO write —
    // same "neither belongs on Main" reasoning `HomeViewModel.init`'s own one-shot home-location
    // resolution already applies to this exact pair of calls (see that class's kdoc). This callback
    // was the one remaining call site still doing both on Main; now consistent with the rest of the
    // codebase.
    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            lifecycleScope.launch(Dispatchers.Default) {
                locationProvider.current()?.let { homeLocationStore.set(it) }
            }
        }
    }

    // Plan 4 Task 3: POST_NOTIFICATIONS runtime permission (API 33+ only — see
    // NotificationPermissionCondition.PRE_33). A grant enqueues the digest worker immediately
    // (rather than waiting for the next app start) so onboarding's/Settings' own "Enable alerts"
    // tap is followed by a genuinely scheduled worker in the SAME session, not just a permission
    // flag that only takes effect after a relaunch.
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) enqueueAlertDigestWorker(applicationContext)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingQuakeId = intent.getStringExtra(AlertDigestWorker.EXTRA_QUAKE_ID)
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Task 2 (Plan 3): rebind on EVERY onCreate, NOT gated behind firstLaunchThisProcess below —
        // a config-change recreate constructs a brand-new MainActivity instance with its own
        // brand-new requestLocationPermission launcher, and LocationRequester's holder (see
        // LocationRequester.android.kt) must always point at the CURRENT instance's launcher, never
        // a previous (destroyed) Activity's.
        bindLocationRequestLauncher {
            requestLocationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        // Plan 4 Task 3: same "rebind every onCreate, not just first launch" reasoning as
        // bindLocationRequestLauncher above — shouldShowRequestPermissionRationale needs THIS
        // Activity instance, never a previous (destroyed) one's.
        bindNotificationPermissionController(
            condition = { computeNotificationPermissionCondition(this) },
            rationale = { currentNotificationRationale(this) },
            launch = {
                markNotificationPermissionAsked(this)
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            openSettingsAction = { openNotificationSettings(this) },
        )
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
            // spirit as ensureKoinStarted's own dao/http/locationProvider construction.
            initShareContext(applicationContext)
            // Plan 4 Task 3: same one-time wiring shape as initShareContext above — see that
            // function's own kdoc for the "process-lifetime holder" pattern this mirrors.
            initAlertDigestSchedulerContext(applicationContext)
            // Plan 4 Task 3: factored out of this block (was inline dao/http construction +
            // startKoin call) — see ensureKoinStarted's own kdoc for why AlertDigestWorker.doWork()
            // needs to be able to call the exact same bootstrap from a headless process start.
            ensureKoinStarted(applicationContext, locationProvider)
        }
        pendingQuakeId = intent?.getStringExtra(AlertDigestWorker.EXTRA_QUAKE_ID)
        setContent {
            App(
                pendingQuakeId = pendingQuakeId,
                onQuakeIdConsumed = { pendingQuakeId = null },
            )
        }
        if (firstLaunchThisProcess) {
            // Ask AFTER content is showing, not before — a permission dialog racing the first frame
            // would otherwise cover a still-blank window. Activity-level by design (not a
            // LaunchedEffect from App()): the ask is one-shot Activity plumbing, independent of
            // whatever composable happens to be on screen (currently the Task 6 map spike; see
            // App.kt).
            requestLocationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        // Plan 4 Task 3: unlike the location ask above (one-shot, firstLaunchThisProcess-gated),
        // this runs on EVERY onCreate — ExistingPeriodicWorkPolicy.KEEP (see
        // enqueueAlertDigestWorker's own kdoc) makes every call past the first a harmless no-op, and
        // re-checking on every app start (not just first-ever-install) is what picks up a
        // permission grant that happened via system Settings while the app was closed.
        enqueueDigestWorkerIfPermitted()
    }

    private fun enqueueDigestWorkerIfPermitted() {
        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (permitted) enqueueAlertDigestWorker(applicationContext)
    }
}
