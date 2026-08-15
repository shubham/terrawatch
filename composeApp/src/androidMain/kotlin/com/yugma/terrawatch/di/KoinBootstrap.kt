package com.yugma.terrawatch.di

import android.content.Context
import android.content.pm.PackageManager
import com.google.android.gms.ads.MobileAds
import com.yugma.terrawatch.alerts.initAlertDigestSchedulerContext
import com.yugma.terrawatch.database.DriverFactory
import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.database.QuakeStore
import com.yugma.terrawatch.database.createDatabase
import com.yugma.terrawatch.location.LocationProvider
import com.yugma.terrawatch.monetization.AlwaysFreeEntitlements
import com.yugma.terrawatch.monetization.EntitlementsProvider
import com.yugma.terrawatch.monetization.RevenueCatEntitlements
import com.yugma.terrawatch.monetization.revenueCatKeyIsConfigured
import com.yugma.terrawatch.share.initShareContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

private val koinBootstrapLock = Any()

/** Fix round (Plan 4 Task 6 review): [MobileAds.initialize]'s own one-shot guard — deliberately a
 * SEPARATE flag from [GlobalContext]'s own started-check inside [ensureKoinStarted], not a re-use of
 * it. See that function's own "Fix round" kdoc paragraph for why. */
private val mobileAdsInitStarted = AtomicBoolean(false)

/** Plan 4 Task 6: this app's own manifest meta-data key for the RevenueCat API key — mirrors
 * `BannerAdSlot.android.kt`'s identical `com.yugma.terrawatch.ADMOB_BANNER_UNIT` key, both sourced
 * from the SAME `composeApp/monetization.properties` file via `composeApp/build.gradle.kts`'s
 * manifest placeholders (see that file's own kdoc for why meta-data, not BuildConfig, carries this
 * across module boundaries). `private` — only [readRevenueCatApiKey] below needs it. */
private const val REVENUECAT_API_KEY_METADATA_KEY = "com.yugma.terrawatch.REVENUECAT_API_KEY"

private fun readRevenueCatApiKey(context: Context): String? {
    val metaData = context.packageManager
        .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        .metaData
    return metaData?.getString(REVENUECAT_API_KEY_METADATA_KEY)
}

/**
 * Task 6 (Plan 4): the real android [EntitlementsProvider] gate — [revenueCatKeyIsConfigured] (the
 * pure, TDD'd rule, `core:monetization`) decides between [RevenueCatEntitlements] and
 * [AlwaysFreeEntitlements]; this function is the thin, obviously-correct wiring around it (same
 * "push the decision to a pure fn, keep the platform glue thin" split this codebase already applies
 * everywhere else — e.g. `alertsRowStatusText`/`AlertsPermissionRow`). Always resolves to
 * [AlwaysFreeEntitlements] throughout Task 6: no RevenueCat account exists yet, so
 * `composeApp/monetization.properties`'s `REVENUECAT_API_KEY` is absent/blank on every build this
 * task ships (a USER-GATED prerequisite, plan's own Global Constraints).
 */
private fun buildEntitlementsProvider(context: Context): EntitlementsProvider {
    val apiKey = readRevenueCatApiKey(context)
    return if (revenueCatKeyIsConfigured(apiKey)) RevenueCatEntitlements(apiKey!!) else AlwaysFreeEntitlements
}

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
 *
 * Fix Round 1 (review Critical C1): [initShareContext]/[initAlertDigestSchedulerContext] now run
 * INSIDE this same guarded block, not as separate calls `MainActivity` made alongside this function
 * under its OWN external `GlobalContext.getOrNull() == null` check. That external duplication was
 * the bug: once `AlertDigestWorker.doWork()` started calling this function directly (a headless
 * WorkManager process start, `MainActivity` never created), Koin's `GlobalContext` was already
 * non-null by the time `MainActivity.onCreate` finally ran later in the SAME process — its own
 * external guard read that as "not first launch" and skipped `initShareContext`/
 * `initAlertDigestSchedulerContext` entirely, permanently, for the rest of that process's lifetime.
 * The Share button, Settings' ALERTS row, and [com.yugma.terrawatch.alerts.AlertDigestScheduler]'s
 * own `isEnqueued`/`triggerNow` all read their respective `lateinit appContext` unconditionally
 * with no null-check (matching every other "process-lifetime holder" in this codebase, e.g.
 * `NotificationPermissionRequester.android.kt`'s own `controller`) — so any of the three being
 * skipped is not a degraded-but-safe path, it is an `UninitializedPropertyAccessException` the
 * instant the user opens Settings, taps Share, or the ALERTS row queries the scheduler.
 *
 * Single fix: fold all three inits into ONE guarded, idempotent function, and have BOTH entry
 * points (`MainActivity.onCreate`, `AlertDigestWorker.doWork`) call ONLY this function,
 * unconditionally — never re-derive "is this the first bootstrap" externally from `GlobalContext`
 * state again (see `MainActivity.onCreate`'s own comment for the SEPARATE, Koin-independent flag it
 * now uses for its own activity-scoped first-run behavior instead).
 *
 * **Fix round (Plan 4 Task 6 review, post-device-verify): `MobileAds.initialize` moved off the
 * calling thread and out of [koinBootstrapLock].** It used to run right here, synchronously, as the
 * last statement inside the `synchronized` block below — correct for "exactly once" (folded into
 * the same guarded bootstrap every real entry point already funnels through) but wrong for "off the
 * caller's thread": `MainActivity.onCreate` calls this function on the MAIN thread, BEFORE
 * `setContent`, so that inline call blocked the first composed frame on however long the AdMob SDK's
 * own init took — Google's own documented guidance is to call `MobileAds.initialize` from a
 * background thread precisely to avoid this
 * (developers.google.com/admob/android/quick-start#initialize_the_mobile_ads_sdk). Now it runs on a
 * short-lived background [Thread], started AFTER the `synchronized` block releases the lock, so
 * neither the lock's critical section nor the calling thread waits on it. Safe to detach like this:
 * the ads SDK is documented to queue a `loadAd()` that fires before its own async init completes
 * rather than drop it (`BannerAdSlot.android.kt`'s own kdoc already relies on exactly this), so
 * nothing downstream needs to block on this thread finishing.
 *
 * Idempotence for this ONE call is now [mobileAdsInitStarted] — a dedicated `AtomicBoolean` — not a
 * side effect of [GlobalContext]'s own started-check above. Those are genuinely two different
 * questions ("has Koin started" vs "has `MobileAds.initialize` fired"); the original code answered
 * both from the SAME `if (GlobalContext.getOrNull() != null) return`, which is why the call had to
 * physically live INSIDE that guarded block to only ever fire once. Un-coupling it onto its own flag
 * is what lets it move outside the block at all while staying exactly-once, on purpose rather than
 * as an accident of where a `return` happens to sit.
 *
 * `AlertDigestWorker.doWork()` (this function's own "second caller," directly above) has no ads UI
 * of its own, but it calls this SAME shared function purely to reach Koin/the DB/network — so it
 * triggers this block too, once, the first time either entry point wins the race. Not worth
 * splitting out behind a new caller-identity parameter just to skip it there: `doWork()` already
 * runs off a `CoroutineWorker`'s own background dispatcher (never the Main thread to begin with),
 * and the block below is now just "spin up one more cheap background thread" rather than a blocking
 * call — an acceptable, documented cost on a periodic (45min) worker tick, not a silent one.
 *
 * Round 2 (review finding): [storeOverride]/[httpClientOverride] are a new trailing seam, both
 * defaulted to `null` — a `null` reproduces this function's ORIGINAL, only-ever-real-[DriverFactory]/
 * real-`OkHttp` construction byte-for-byte, so neither of this function's two real call sites
 * (`MainActivity.onCreate`, `AlertDigestWorker.doWork`) changes at all: both still call this with
 * exactly the same two positional arguments as before. The seam exists purely so
 * `NavRoundTripTest` (`androidInstrumentedTest`) can go through this SAME bootstrap — Share/
 * AlertDigestScheduler context init included — instead of a hand-rolled `startKoin {}` call that
 * used to skip both entirely (see that test's own kdoc for the crash this closes: Settings' ALERTS
 * row reads `AlertDigestScheduler`'s `appContext` unconditionally, which was never set when the
 * test built its own bare Koin graph, since [initShareContext]/[initAlertDigestSchedulerContext]
 * only ever fire from INSIDE this function) — while still substituting a throwaway in-memory
 * driver/`MockEngine` for the real device DB/network, the same way `HomeFlowTest.freshDriver`'s own
 * kdoc explains an instrumented test must never touch the real on-device "terrawatch.db"/network.
 */
@OptIn(ExperimentalTime::class)
fun ensureKoinStarted(
    context: Context,
    locationProvider: LocationProvider,
    storeOverride: QuakeStore? = null,
    httpClientOverride: HttpClient? = null,
) {
    val appContext = context.applicationContext
    synchronized(koinBootstrapLock) {
        if (GlobalContext.getOrNull() == null) {
            initShareContext(appContext)
            initAlertDigestSchedulerContext(appContext)
            val dao = storeOverride
                ?: QuakeDao(createDatabase(DriverFactory(appContext)), clock = { Clock.System.now().toEpochMilliseconds() })
            val http = httpClientOverride ?: HttpClient(OkHttp) {
                install(WebSockets) { pingIntervalMillis = 30_000 }
                install(HttpTimeout) {
                    requestTimeoutMillis = 15_000
                    connectTimeoutMillis = 10_000
                }
            }
            startKoin { modules(appModule(http, dao, locationProvider, buildEntitlementsProvider(appContext))) }
        }
    }
    // Plan 4 Task 6 (this task's own brief: "MobileAds.initialize in ensureKoinStarted (android)"),
    // fix round: deliberately OUTSIDE koinBootstrapLock and off the calling thread now — see this
    // function's own "Fix round" kdoc paragraph above for the full before/after reasoning. Guarded by
    // its own [mobileAdsInitStarted] flag, not by this block's Koin-started check, so it stays
    // exactly-once regardless of how many times either real entry point calls this function.
    if (mobileAdsInitStarted.compareAndSet(false, true)) {
        Thread({ MobileAds.initialize(appContext) }, "MobileAdsInit").start()
    }
}
