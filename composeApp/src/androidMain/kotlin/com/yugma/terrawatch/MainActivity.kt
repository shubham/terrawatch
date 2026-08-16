package com.yugma.terrawatch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.yugma.terrawatch.alerts.AlertDigestWorker
import com.yugma.terrawatch.alerts.enqueueAlertDigestWorker
import com.yugma.terrawatch.data.HomeLocationStore
import com.yugma.terrawatch.data.OnboardingStore
import com.yugma.terrawatch.di.ensureKoinStarted
import com.yugma.terrawatch.location.LocationProvider
import com.yugma.terrawatch.location.bindLocationPermissionController
import com.yugma.terrawatch.location.computeLocationPermissionCondition
import com.yugma.terrawatch.location.currentLocationPermissionRationale
import com.yugma.terrawatch.location.markLocationPermissionAsked
import com.yugma.terrawatch.location.openLocationSettings
import com.yugma.terrawatch.notifications.bindNotificationPermissionController
import com.yugma.terrawatch.notifications.computeNotificationPermissionCondition
import com.yugma.terrawatch.notifications.currentNotificationRationale
import com.yugma.terrawatch.notifications.markNotificationPermissionAsked
import com.yugma.terrawatch.notifications.openNotificationSettings
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
    // initializer calls into Koin's GlobalContext, which onCreate() below doesn't start until
    // partway through its own body (ensureKoinStarted's call site) — deferring the lookup until
    // this property is actually first READ (the grant callback below, which can only fire once the
    // OS permission dialog has been answered, well after onCreate has returned) guarantees
    // startKoin() has always already run by then, regardless of which entry point (this Activity,
    // or a headless AlertDigestWorker run — see ensureKoinStarted's own kdoc) got there first.
    private val homeLocationStore: HomeLocationStore by lazy { GlobalContext.get().get() }

    // Plan 4 Task 6 (Fix Round 1, M1): onboarding gate for digest worker — same Koin-lazy
    // resolution as homeLocationStore above, for the identical reason (ensureKoinStarted runs
    // mid-onCreate, and enqueueDigestWorkerIfPermitted is called well after that point).
    private val onboardingStore: OnboardingStore by lazy { GlobalContext.get().get() }

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
        // feat/feed-visit-ux, "Splash app name": must run before super.onCreate() — Google's own
        // splash-screen migration guide (developer.android.com/develop/ui/views/launch/
        // splash-screen#kotlin) shows this as the first line of onCreate(), same "configure the
        // Window before decor-view creation" family of constraint enableEdgeToEdge() below is
        // already under (the two are otherwise unrelated — one sets up the SplashScreen
        // exit/postSplashScreenTheme handoff, the other configures edge-to-edge insets). Backs
        // Theme.App.Starting's windowSplashScreenBrandingImage (values/themes.xml) — this app had
        // no installSplashScreen() call and no custom splash theme at all before this commit, so
        // Android 12+'s own default splash showed only the launcher icon, never the app name.
        //
        // installSplashScreen() is a Kotlin extension function on Activity, but declared as a
        // member of SplashScreen's companion object (@JvmStatic, so Java callers see a plain
        // static SplashScreen.installSplashScreen(activity)) — verified against the real
        // decompiled androidx.core:core-splashscreen:1.2.0 classes.jar via javap (the
        // `$this$installSplashScreen` receiver-parameter naming in its Kotlin metadata is the
        // tell) after two other call-site guesses (a plain ComponentActivity extension import,
        // then an explicit SplashScreen.installSplashScreen(this) static-style call) both failed
        // to resolve at compile time. The import above pulls the companion member in directly, so
        // the call below reads as a normal extension-function call on this Activity.
        installSplashScreen()
        // Plan 4 Task 4 (a): must run before super.onCreate() (Google's own documented ordering —
        // see developer.android.com/develop/ui/compose/system/edge-to-edge) so the very first frame
        // this Activity ever draws is already edge-to-edge, not a legacy-inset frame that then jumps
        // once this call lands. Makes this app draw edge-to-edge on EVERY supported API level
        // (minSdk 26+), not only once targetSdk 36 + a 35+ device FORCES it regardless (see
        // developer.android.com/about/versions/16/behavior-changes-16: "Edge-to-edge opt-out
        // disabled" — this app never set that opt-out flag to begin with, so the only actual change
        // here is making the behavior uniform and deliberate across API 26-35 too, instead of
        // OS-version-dependent). Every screen's top/bottom content is threaded through
        // windowInsetsPadding(...) precisely because of this call — see task-4-report.md's SDK-36
        // audit table for the per-screen sweep.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Plan 4 Task 4 (d): rebind on EVERY onCreate, same "a config-change recreate constructs a
        // brand-new Activity instance, and the holder must always point at the CURRENT one" reasoning
        // bindNotificationPermissionController below already established (mirrored here 1:1 — see
        // LocationRequester.android.kt's own kdoc). Replaces the old bindLocationRequestLauncher's
        // bare launch-only closure: the launch-time ask this used to ALSO trigger unconditionally
        // (see this class's git history) is deleted outright per this task's brief — location is now
        // asked ONLY from onboarding step 2 and Settings' "Use my location" row, both of which read
        // condition/rationale live through this same controller rather than firing blind.
        bindLocationPermissionController(
            condition = { computeLocationPermissionCondition(this) },
            rationale = { currentLocationPermissionRationale(this) },
            launch = {
                markLocationPermissionAsked(this)
                requestLocationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            },
            openSettingsAction = { openLocationSettings(this) },
        )
        // Plan 4 Task 3: same "rebind every onCreate, not just first launch" reasoning as
        // bindLocationPermissionController above — shouldShowRequestPermissionRationale needs THIS
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
        pendingQuakeId = intent?.getStringExtra(AlertDigestWorker.EXTRA_QUAKE_ID)
        setContent {
            App(
                pendingQuakeId = pendingQuakeId,
                onQuakeIdConsumed = { pendingQuakeId = null },
            )
        }
        // Plan 4 Task 4 (d): the old one-shot, first-launch-only location-permission ask that used
        // to fire here is DELETED outright, not merely moved — per this task's brief, location is
        // now asked ONLY from two explicit, user-initiated affordances (onboarding step 2's "Use my
        // location", Settings' PLACE-section "Use my location" row), never blind at launch. Both
        // sites call through bindLocationPermissionController above via LocationRequester.request().
        //
        // This runs on EVERY onCreate regardless — ExistingPeriodicWorkPolicy.UPDATE (Fix Round 1, I3
        // — see enqueueAlertDigestWorker's own kdoc) makes every call past the first a harmless
        // no-op for anything about the request that hasn't actually changed, and re-checking on
        // every app start (not just first-ever-install) is what picks up a permission grant that
        // happened via system Settings while the app was closed.
        enqueueDigestWorkerIfPermitted()
    }

    private fun enqueueDigestWorkerIfPermitted() {
        // Gate on onboarding — post-33 is already gated by the permission check itself (can't
        // POST_NOTIFICATIONS until explicitly granted during or after onboarding), but pre-33 has
        // no permission to gate on, so the permission check alone would unconditionally enqueue
        // on first onCreate (before the user has seen onboarding). Check isOnboarded() to
        // suppress the pre-33 case until onboarding is actually complete.
        if (!onboardingStore.isOnboarded()) return

        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (permitted) enqueueAlertDigestWorker(applicationContext)
    }
}
