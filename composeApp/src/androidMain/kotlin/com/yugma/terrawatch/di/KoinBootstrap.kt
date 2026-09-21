package com.yugma.terrawatch.di

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.revenuecat.purchases.kmp.LogLevel
import com.revenuecat.purchases.kmp.Purchases
import com.revenuecat.purchases.kmp.configure
import com.yugma.terrawatch.alerts.initAlertDigestSchedulerContext
import com.yugma.terrawatch.database.DriverFactory
import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.database.QuakeStore
import com.yugma.terrawatch.database.createDatabase
import com.yugma.terrawatch.location.LocationProvider
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

/**
 * Devices that should receive AdMob TEST creatives even when a real ad unit id is configured.
 *
 * Each entry is the hashed id AdMob itself prints to logcat on an un-registered device:
 * `Use RequestConfiguration.Builder.setTestDeviceIds(Arrays.asList("<hash>"))`. The hash is derived
 * per app install, so it changes on a reinstall -- a stale entry silently stops working, which is
 * why the logcat line is the source of truth rather than this list.
 *
 * Only ever applied to debuggable builds (see the call site), so adding a device here can never
 * affect what real users are served.
 */
private val ADMOB_TEST_DEVICE_IDS: List<String> = listOf(
    // Pixel 8 (3C161FDJH000H2), the device this app is verified on. Read from its own logcat
    // line on 2026-09-05, for the .debug applicationId.
    "AD65382B06917EC84724647F29EB6F05",
)

/** Mirrors `AlertDigestScheduler.android.kt`'s own private check verbatim, for the same reason it
 * exists there: a debug-only behaviour that must never be reachable in a release build. */
private fun isDebuggableBuild(context: Context): Boolean =
    (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

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
 * Configures RevenueCat, whose only remaining job is recording ad revenue (see
 * `core/ads/AdRevenueTracker.kt`). Purchases were removed in 1.1.0 (2026-09-21
 * ads-only-monetization plan) — this replaces `buildEntitlementsProvider`/`buildPlusPurchases`,
 * which used to construct `EntitlementsProvider`/`PlusPurchases` instances as a side effect of
 * running this same configure call; neither type exists any more (both lived in the deleted
 * `core:monetization` module), so this function's only job is the configure call itself.
 *
 * A blank key is the normal, supported state: we simply never configure, and
 * `AdRevenueTracker`'s own `Purchases.isConfigured` guard turns revenue tracking into a silent
 * no-op. The app is fully functional — ads included — with no RevenueCat account at all.
 *
 * Uses the same `configure(apiKey) { }` extension the deleted `RevenueCatEntitlements.kt`'s own
 * init block used, not a `PurchasesConfiguration.Builder(context, key).build()` shape: no such
 * builder type exists anywhere in this codebase or on `feat/plus-purchase-parked` (grep-verified
 * for `PurchasesConfiguration` on both). Context is captured internally via AndroidX App Startup
 * per RevenueCat's own KMP docs, so no `Context` parameter is threaded past [context] itself,
 * which is only here to reach [readRevenueCatApiKey].
 */
private fun configureRevenueCatForAdRevenue(context: Context) {
    val apiKey = readRevenueCatApiKey(context)
    if (apiKey.isNullOrBlank()) return
    Purchases.logLevel = LogLevel.WARN
    Purchases.configure(apiKey = apiKey) {}
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
            // 2026-09-21 ads-only-monetization plan (Task 3): configuring RevenueCat used to be a
            // side effect of buildEntitlementsProvider(appContext), one of appModule(...)'s own
            // arguments below — Kotlin's left-to-right argument evaluation is what made
            // entitlements-before-purchases load-bearing there (commit 415e76c). Both parameters
            // are gone now, so that ordering hazard at this call site is gone with them; this call
            // is simply made before startKoin instead, as its own explicit step.
            configureRevenueCatForAdRevenue(appContext)
            val dao = storeOverride
                ?: QuakeDao(createDatabase(DriverFactory(appContext)), clock = { Clock.System.now().toEpochMilliseconds() })
            val http = httpClientOverride ?: HttpClient(OkHttp) {
                install(WebSockets) { pingIntervalMillis = 30_000 }
                install(HttpTimeout) {
                    requestTimeoutMillis = 15_000
                    connectTimeoutMillis = 10_000
                }
            }
            startKoin {
                modules(
                    appModule(
                        http,
                        dao,
                        locationProvider,
                    )
                )
            }
        }
    }
    // Plan 4 Task 6 (this task's own brief: "MobileAds.initialize in ensureKoinStarted (android)"),
    // fix round: deliberately OUTSIDE koinBootstrapLock and off the calling thread now — see this
    // function's own "Fix round" kdoc paragraph above for the full before/after reasoning. Guarded by
    // its own [mobileAdsInitStarted] flag, not by this block's Koin-started check, so it stays
    // exactly-once regardless of how many times either real entry point calls this function.
    if (mobileAdsInitStarted.compareAndSet(false, true)) {
        Thread({
            // DEBUG ONLY, and load-bearing for account safety rather than convenience. Once a real
            // ADMOB_BANNER_UNIT is configured, every debug run serves LIVE ads against the owner's
            // own account, and a device pass loads that banner repeatedly -- Google's own guidance
            // is blunt about where that leads: "If you click too many ads without being in test
            // mode, you risk your account being flagged for invalid activity."
            // (developers.google.com/admob/android/test-ads). Registering this build's device as a
            // test device makes the SDK serve test creatives from the REAL unit id, so the wiring is
            // still genuinely verified end to end while the traffic stays non-billable.
            //
            // Gated on FLAG_DEBUGGABLE, mirroring `AlertDigestScheduler.android.kt`'s own
            // isDebuggableBuild check -- release builds never reach this, which is exactly what
            // Google's "remove the code that sets these test device IDs before you release" means
            // in a codebase that would rather guard it than delete and forget it.
            if (isDebuggableBuild(appContext) && ADMOB_TEST_DEVICE_IDS.isNotEmpty()) {
                MobileAds.setRequestConfiguration(
                    RequestConfiguration.Builder()
                        .setTestDeviceIds(ADMOB_TEST_DEVICE_IDS)
                        .build()
                )
            }
            MobileAds.initialize(appContext)
        }, "MobileAdsInit").start()
    }
}
