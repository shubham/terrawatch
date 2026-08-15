package com.yugma.terrawatch.alerts

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Set once from MainActivity.onCreate's own "first launch this process" guard — same
// process-lifetime-holder shape com.yugma.terrawatch.share.Share.android.kt's own appContext
// establishes (applicationContext, not the Activity, stored specifically so this can never leak an
// Activity past its lifecycle). Unlike NotificationPermissionRequester's controller (which needs
// per-Activity-instance closures for shouldShowRequestPermissionRationale), everything this file
// does — enqueueing/querying WorkManager, reading FLAG_DEBUGGABLE — only ever needs an application
// Context, which never changes across a config-change recreate, so one set-once holder suffices.
private lateinit var appContext: Context

/** Must run before [AlertDigestScheduler] or [enqueueAlertDigestWorker] are ever called — i.e.
 * before `MainActivity`'s `setContent {}` composes Settings' ALERTS row, and before this same
 * `onCreate` reaches its own permission-gated [enqueueAlertDigestWorker] call. */
fun initAlertDigestSchedulerContext(context: Context) {
    appContext = context.applicationContext
}

/** Mirrors `QuakeMap.android.kt`'s own private `isDebuggableBuild` check verbatim (same
 * `FLAG_DEBUGGABLE` bitmask read) — kept as its own small local copy here rather than exported
 * from that file: it's a one-line check, and this class already has zero other dependency on
 * anything in the `map` package. */
private fun isDebuggableBuild(context: Context): Boolean =
    (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

/**
 * The real periodic schedule — `MainActivity` calls this on every app start whenever
 * [com.yugma.terrawatch.notifications.NotificationPermissionCondition] allows it (granted or
 * pre-33), and again immediately after a fresh grant so the user doesn't have to relaunch the app
 * to get alerts scheduled. [ExistingPeriodicWorkPolicy.KEEP] makes every one of those calls a
 * harmless no-op once genuinely enqueued — WorkManager itself dedupes by [AlertDigestWorker.
 * UNIQUE_WORK_NAME], so calling this on every single app start (rather than only on first-ever
 * install) is deliberate, not wasteful.
 *
 * 45 minutes sits inside the spec's own "every 30-60 min" band (§6.5) and WorkManager's own
 * documented 15-minute periodic-work floor (any interval below that is silently clamped up to it
 * by the platform — 45 is comfortably clear of that floor, no clamping risk).
 */
fun enqueueAlertDigestWorker(context: Context) {
    val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    val request = PeriodicWorkRequestBuilder<AlertDigestWorker>(45, TimeUnit.MINUTES)
        .setConstraints(constraints)
        .build()
    WorkManager.getInstance(context.applicationContext)
        .enqueueUniquePeriodicWork(AlertDigestWorker.UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
}

actual class AlertDigestScheduler {
    /**
     * A real WorkManager query, not an inference from permission state — Settings' ALERTS row
     * wants to say "On" only when the digest is ACTUALLY scheduled, not merely "permission allows
     * it to be." [WorkInfo.State.CANCELLED] is the one state treated as "not enqueued"; every other
     * state (including the ordinary ENQUEUED/RUNNING cycle a healthy periodic job sits in) counts
     * as on. `AlertDigestWorker.doWork()`'s own top-level try/catch (see that class's kdoc) is what
     * keeps an unexpected exception from ever driving this into WorkManager's real terminal FAILED
     * state, which — unlike one-time work — permanently stops a periodic series from rescheduling;
     * this method does not separately guard against that, it relies on doWork() never producing it.
     *
     * `.get()` (blocking) on the returned `ListenableFuture` is why this is `suspend` +
     * [Dispatchers.IO] rather than a plain synchronous call a composable could invoke directly —
     * same "move a real blocking read off Main" posture this codebase's other DAO-touching reads
     * already take (e.g. `SettingsViewModel`'s own `homeLocationStore.get()` dispatch).
     */
    actual suspend fun isEnqueued(): Boolean = withContext(Dispatchers.IO) {
        val infos = WorkManager.getInstance(appContext)
            .getWorkInfosForUniqueWork(AlertDigestWorker.UNIQUE_WORK_NAME)
            .get()
        infos.any { it.state != WorkInfo.State.CANCELLED }
    }

    actual fun isDebugTriggerAvailable(): Boolean = isDebuggableBuild(appContext)

    actual fun triggerNow() {
        if (!isDebugTriggerAvailable()) return
        WorkManager.getInstance(appContext).enqueue(OneTimeWorkRequestBuilder<AlertDigestWorker>().build())
    }
}
