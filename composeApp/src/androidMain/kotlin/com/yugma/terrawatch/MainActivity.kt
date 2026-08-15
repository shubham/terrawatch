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
import com.yugma.terrawatch.data.HomeLocationStore
import com.yugma.terrawatch.di.ensureKoinStarted
import com.yugma.terrawatch.location.LocationProvider
import com.yugma.terrawatch.location.bindLocationRequestLauncher
import com.yugma.terrawatch.notifications.bindNotificationPermissionController
import com.yugma.terrawatch.notifications.computeNotificationPermissionCondition
import com.yugma.terrawatch.notifications.currentNotificationRationale
import com.yugma.terrawatch.notifications.markNotificationPermissionAsked
import com.yugma.terrawatch.notifications.openNotificationSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

// Fix Round 1 (C1, review Critical): a SEPARATE process-lifetime flag from Koin's own
// GlobalContext state — see ensureKoinStarted's own kdoc for why GlobalContext.getOrNull() alone
// is no longer a safe proxy for "has MainActivity ever run in this process" now that
// AlertDigestWorker.doWork() can start Koin from a headless, WorkManager-only process start with
// MainActivity never created at all. If that headless run happens first and the OS keeps the
// process alive long enough for the user to then open the app, GlobalContext.getOrNull() would
// already read non-null on MainActivity's own very first onCreate — the OLD single-guard code
// would have silently skipped the one-shot location-permission ask below for what is, from THIS
// Activity's point of view, genuinely its first launch. A top-level var (not an instance field),
// same reasoning as the original GlobalContext check: a config-change recreate constructs a
// brand-new MainActivity instance whose own fields would silently reset, but this must stay true
// for the rest of the process's lifetime once set.
private var mainActivityBootstrappedThisProcess = false

class MainActivity : ComponentActivity() {
    // Built once per Activity instance (not per onCreate/Koin-guard branch — applicationContext is
    // stable across a config-change recreate anyway, and the permission callback below needs a
    // reference regardless of whether this particular onCreate() call ends up starting Koin).
    private val locationProvider by lazy { LocationProvider(applicationContext) }

    // Task 2 (Plan 3): resolved through Koin, unlike locationProvider above — this class is
    // entirely Koin-built (`single { HomeLocationStore(get()) }`, AppModule.kt) with no
    // Context/Activity dependency of its own, so there's no reason for MainActivity to construct it
    // directly the way it must for locationProvider. `by lazy` matters here, not just style: this
    // initializer calls into Koin's GlobalContext, which onCreate() below doesn't start until
    // partway through its own body (ensureKoinStarted's call site) — deferring the lookup until
    // this property is actually first READ (the grant callback below, which can only fire once the
    // OS permission dialog has been answered, well after onCreate has returned) guarantees
    // startKoin() has always already run by then, regardless of which entry point (this Activity,
    // or a headless AlertDigestWorker run — see ensureKoinStarted's own kdoc) got there first.
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
        // Fix Round 1 (C1, review Critical): unconditional on EVERY onCreate now, not gated behind
        // a "first launch" check computed from GlobalContext state — ensureKoinStarted is
        // internally idempotent (its own GlobalContext.getOrNull() != null short-circuit, unchanged)
        // and now ALSO performs the Share/AlertDigestScheduler context wiring that used to be a
        // separate, externally-gated block here (see that function's own kdoc for the bug this
        // closes: AlertDigestWorker.doWork() can start Koin from a headless process before
        // MainActivity ever runs, which used to leave initShareContext/
        // initAlertDigestSchedulerContext never called at all once THIS onCreate finally did run,
        // because the old external guard read GlobalContext as already-started by then —
        // UninitializedPropertyAccessException the moment Settings/Share/the ALERTS row was ever
        // touched). Relying on ensureKoinStarted's OWN internal guard — the single source of truth
        // for "has bootstrap happened," located inside the idempotent function itself — is strictly
        // more robust than duplicating that condition out here a second time.
        ensureKoinStarted(applicationContext, locationProvider)
        // A SEPARATE, Koin-independent guard for this Activity's own one-shot behavior below (the
        // location-permission ask) — see mainActivityBootstrappedThisProcess's own top-of-file
        // comment for why GlobalContext state can no longer answer "has MainActivity run before in
        // this process" now that ensureKoinStarted has another possible caller.
        val firstMainActivityLaunchThisProcess = !mainActivityBootstrappedThisProcess
        mainActivityBootstrappedThisProcess = true
        pendingQuakeId = intent?.getStringExtra(AlertDigestWorker.EXTRA_QUAKE_ID)
        setContent {
            App(
                pendingQuakeId = pendingQuakeId,
                onQuakeIdConsumed = { pendingQuakeId = null },
            )
        }
        if (firstMainActivityLaunchThisProcess) {
            // Ask AFTER content is showing, not before — a permission dialog racing the first frame
            // would otherwise cover a still-blank window. Activity-level by design (not a
            // LaunchedEffect from App()): the ask is one-shot Activity plumbing, independent of
            // whatever composable happens to be on screen (currently the Task 6 map spike; see
            // App.kt).
            requestLocationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        // Plan 4 Task 3: unlike the location ask above (one-shot, firstMainActivityLaunchThisProcess
        // -gated), this runs on EVERY onCreate — ExistingPeriodicWorkPolicy.UPDATE (Fix Round 1, I3
        // — see enqueueAlertDigestWorker's own kdoc) makes every call past the first a harmless
        // no-op for anything about the request that hasn't actually changed, and re-checking on
        // every app start (not just first-ever-install) is what picks up a permission grant that
        // happened via system Settings while the app was closed.
        enqueueDigestWorkerIfPermitted()
    }

    private fun enqueueDigestWorkerIfPermitted() {
        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (permitted) enqueueAlertDigestWorker(applicationContext)
    }
}
