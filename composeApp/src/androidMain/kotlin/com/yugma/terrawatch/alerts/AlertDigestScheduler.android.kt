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
 * to get alerts scheduled.
 *
 * 45 minutes sits inside the spec's own "every 30-60 min" band (§6.5) and WorkManager's own
 * documented 15-minute periodic-work floor (any interval below that is silently clamped up to it
 * by the platform — 45 is comfortably clear of that floor, no clamping risk).
 *
 * Fix Round 1 (I5, review finding): creates the digest notification channel HERE, at enqueue time
 * — not deferred until the first real notification (`AlertDigestWorker.runDigest`'s own defensive
 * call, now belt-and-suspenders only). Two reasons this timing matters, not just style: (a) a
 * channel only shows up in system Settings once it exists at all, so a user who wants to
 * pre-emptively mute digests currently has no channel to find until one has already fired; (b)
 * [com.yugma.terrawatch.notifications.computeNotificationPermissionCondition]'s own I4 fix reads
 * this channel's importance to decide whether the ALERTS row/onboarding button can honestly claim
 * "On" — a channel that doesn't exist yet reads as "not blocked" there (see that function's own
 * kdoc), which is the right degrade, but only creating the channel this early makes the importance
 * check meaningful well before any notification could ever have fired. Since every real call site
 * of this function runs long before the periodic worker's own first tick (this is the function
 * that SCHEDULES that first tick), the channel is always created before doWork() could ever reach
 * the point of wanting one.
 */
fun enqueueAlertDigestWorker(context: Context) {
    val appContext = context.applicationContext
    AlertDigestWorker.ensureNotificationChannel(appContext)
    val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    val request = PeriodicWorkRequestBuilder<AlertDigestWorker>(45, TimeUnit.MINUTES)
        .setConstraints(constraints)
        .build()
    // Fix Round 1 (I3, review minor): KEEP -> UPDATE. KEEP made every call past the very first a
    // no-op even for the constraints/interval baked into `request` above — harmless while those
    // values never change, but a silent trap the moment either one ever needs a post-ship tuning
    // pass (e.g. widening the interval, dropping the network constraint): a KEEP-enqueued update
    // would build and compile cleanly and then simply never take effect on any device that already
    // had the OLD periodic request enqueued, with no error anywhere. UPDATE re-applies this
    // request's own current definition on every call while still being a no-op wherever nothing
    // about it actually changed (WorkManager diffs the request, not a blind re-enqueue) — the
    // enqueue call stays exactly as safe to call on every app start as KEEP was, just no longer
    // silently frozen to whichever definition happened to be enqueued first.
    WorkManager.getInstance(appContext)
        .enqueueUniquePeriodicWork(AlertDigestWorker.UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
}

actual class AlertDigestScheduler {
    /**
     * A real WorkManager query, not an inference from permission state — Settings' ALERTS row
     * wants to say "On" only when the digest is ACTUALLY scheduled, not merely "permission allows
     * it to be." `AlertDigestWorker.doWork()`'s own top-level try/catch (see that class's kdoc) is
     * what keeps an unexpected exception from ever driving this into WorkManager's real terminal
     * FAILED state, which — unlike one-time work — permanently stops a periodic series from
     * rescheduling; this method does not separately guard against that, it relies on doWork()
     * never producing it.
     *
     * Fix Round 1 (review minor): narrowed from "anything but CANCELLED" to exactly
     * [WorkInfo.State.ENQUEUED]/[WorkInfo.State.RUNNING] — the only two states a HEALTHY periodic
     * job actually cycles through. The old "anything but CANCELLED" check would have kept
     * reporting "On" even after WorkManager drove this into a genuine [WorkInfo.State.FAILED] —
     * which, per `AlertDigestWorker`'s own kdoc, is TERMINAL for periodic work (the whole schedule
     * stops rescheduling forever) — exactly the dishonest-row scenario this method exists to avoid.
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
        infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
    }

    actual fun isDebugTriggerAvailable(): Boolean = isDebuggableBuild(appContext)

    actual fun triggerNow() {
        if (!isDebugTriggerAvailable()) return
        WorkManager.getInstance(appContext).enqueue(OneTimeWorkRequestBuilder<AlertDigestWorker>().build())
    }
}
