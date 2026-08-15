package com.yugma.terrawatch.alerts

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.yugma.terrawatch.MainActivity
import com.yugma.terrawatch.R
import com.yugma.terrawatch.data.AlertEvent
import com.yugma.terrawatch.data.AlertRuleEngine
import com.yugma.terrawatch.data.HomeLocationStore
import com.yugma.terrawatch.data.QuakeRepository
import com.yugma.terrawatch.data.RefreshStatus
import com.yugma.terrawatch.data.appendNotifiedIds
import com.yugma.terrawatch.data.parseNotifiedIds
import com.yugma.terrawatch.data.planDigestNotifications
import com.yugma.terrawatch.database.QuakeStore
import com.yugma.terrawatch.di.ensureKoinStarted
import com.yugma.terrawatch.location.LocationProvider
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import org.koin.core.context.GlobalContext

/**
 * Plan 4 Task 3: the app's first background notification producer — a periodic (every 45 min,
 * `AlertDigestScheduler.android.kt`'s own `enqueueAlertDigestWorker`) re-evaluation of
 * [AlertRuleEngine] over whatever's genuinely new since the LAST run, independent of [QuakeRepository.
 * alertEvents] (that `SharedFlow`'s buffer-16/no-replay shape is fine for a foregrounded live
 * session, but nobody reliably collects it while the app is backgrounded — exactly the gap this
 * worker closes, per this task's own dispatch: "NOT the live SharedFlow — worker-side evaluation").
 *
 * `doWork()`'s own five steps, in order:
 * 1. [ensureKoinStarted] — see that function's own kdoc for why a worker-only process start needs
 *    this at all (no custom `Application` subclass starts Koin ahead of a headless run otherwise).
 * 2. [QuakeRepository.refreshFeed] — the SAME feed poll `HomeViewModel`'s own foreground loop uses.
 * 3. [QuakeStore.newSince] against the persisted `"alert_last_run"` cutoff — feed/live rows only
 *    (excludes archive/debug — see that method's own kdoc for the F5-guard parity this enforces).
 * 4. Each new row through a fresh [AlertRuleEngine], `previous = null` always: these are rows whose
 *    own `timeMillis` is newer than the last run, i.e. genuinely new arrivals from this worker's own
 *    point of view — there is no real "previous state" to diff against the way a live revision
 *    would have one, so `null` (never suppresses on an "already exceeded" basis) is the honest
 *    mapping, not an approximation of one.
 * 5. Individual notifications for the top [MAX_INDIVIDUAL_NOTIFICATIONS] (by magnitude, strongest
 *    first) plus one summary if more matched — [planDigestNotifications]'s own contract.
 *
 * A [RefreshStatus.FAILED] feed poll returns [Result.retry] immediately, touching NO meta state —
 * "we didn't actually get to look at anything new" must not silently advance `alert_last_run` past
 * a window this run never actually checked, which would permanently lose whatever occurred during
 * the outage. [RefreshStatus.NOT_MODIFIED] does NOT short-circuit the same way: a foregrounded live
 * WebSocket session between digest runs can still have deposited genuinely new `'live'`-origin rows
 * even when the FEED itself hasn't changed, so this worker always proceeds to its own [QuakeStore.
 * newSince] check regardless of the feed poll's own outcome, as long as it didn't outright fail.
 *
 * The top-level `try`/`catch` below (not just a leap of faith in [CoroutineWorker]'s own default
 * behavior) matters specifically because this is PERIODIC work: an uncaught exception propagating
 * out of `doWork()` becomes an implicit [Result.failure], and WorkManager's periodic series (unlike
 * one-time work, and unlike this worker's own deliberate [Result.retry] above) treats a genuine
 * [Result.failure] as terminal — the whole schedule stops rescheduling forever, silently, with no
 * further digests ever running again on this device. Catching everything except
 * [CancellationException] (which must always propagate, per structured-concurrency convention —
 * swallowing it would break cooperative cancellation) and mapping it to [Result.retry] instead means
 * a genuine bug degrades to "try again next period," never to "alerts silently stop forever."
 */
class AlertDigestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @OptIn(ExperimentalTime::class)
    override suspend fun doWork(): Result = try {
        runDigest()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.retry()
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun runDigest(): Result {
        ensureKoinStarted(applicationContext, LocationProvider(applicationContext))
        val koin = GlobalContext.get()
        val repository: QuakeRepository = koin.get()
        val store: QuakeStore = koin.get()
        val homeLocationStore: HomeLocationStore = koin.get()

        if (repository.refreshFeed() == RefreshStatus.FAILED) return Result.retry()

        val nowMillis = Clock.System.now().toEpochMilliseconds()
        val lastRun = store.metaGet(KEY_LAST_RUN)?.toLongOrNull() ?: 0L
        val newRows = store.newSince(lastRun)

        val rules = repository.currentRules()
        val home = homeLocationStore.get()
        val engine = AlertRuleEngine()
        val matched = newRows.mapNotNull { engine.evaluate(previous = null, current = it, rules = rules, home = home) }

        val alreadyNotifiedCsv = store.metaGet(KEY_NOTIFIED_IDS)
        val alreadyNotified = parseNotifiedIds(alreadyNotifiedCsv).toSet()
        val fresh = matched
            .filter { it.quake.id !in alreadyNotified }
            .sortedWith(compareByDescending<AlertEvent> { it.quake.mag ?: 0.0 }.thenByDescending { it.quake.timeMillis })

        if (fresh.isNotEmpty() && NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) {
            ensureNotificationChannel(applicationContext)
            val plan = planDigestNotifications(fresh, maxIndividual = MAX_INDIVIDUAL_NOTIFICATIONS)
            plan.individual.forEach { event -> notifyIndividual(applicationContext, event, nowMillis) }
            if (plan.summaryExtraCount > 0) notifySummary(applicationContext, plan.summaryExtraCount)
        }

        store.metaPutAll(
            KEY_LAST_RUN to nowMillis.toString(),
            KEY_NOTIFIED_IDS to appendNotifiedIds(alreadyNotifiedCsv, fresh.map { it.quake.id }, cap = NOTIFIED_IDS_CAP),
        )
        return Result.success()
    }

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
            // Digest honesty (spec §6.5): this channel's own description is where the OS actually
            // shows users what this class of notification is for (Settings > Apps > TerraWatch >
            // Notifications) — the one place this framing needs to live exactly once, independent
            // of any single notification's own copy.
            description = CHANNEL_DESCRIPTION
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun contentIntentFor(context: Context, quakeId: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            quakeId?.let { putExtra(EXTRA_QUAKE_ID, it) }
        }
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // Lint's MissingPermission check can't see across this call chain that NotificationManagerCompat.
    // areNotificationsEnabled() already gated whether this ever runs (runDigest's own `if`, above) —
    // suppressed rather than restructured, since POST_NOTIFICATIONS itself is one of the rare runtime
    // permissions Android deliberately designed to fail SILENTLY (no SecurityException) rather than
    // crash the caller when absent, so this is a real, already-guarded no-op risk, not a live crash risk.
    @SuppressLint("MissingPermission")
    private fun notifyIndividual(context: Context, event: AlertEvent, nowMillis: Long) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(digestNotificationTitle(event, nowMillis))
            .setContentText(digestNotificationBody(event))
            .setAutoCancel(true)
            .setContentIntent(contentIntentFor(context, event.quake.id, event.quake.id.hashCode()))
            .build()
        NotificationManagerCompat.from(context).notify(event.quake.id.hashCode(), notification)
    }

    @SuppressLint("MissingPermission")
    private fun notifySummary(context: Context, extraCount: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("More earthquake alerts")
            .setContentText(summaryNotificationText(extraCount))
            .setAutoCancel(true)
            .setContentIntent(contentIntentFor(context, quakeId = null, requestCode = SUMMARY_REQUEST_CODE))
            .build()
        NotificationManagerCompat.from(context).notify(SUMMARY_NOTIFICATION_ID, notification)
    }

    companion object {
        /** [androidx.work.WorkManager.enqueueUniquePeriodicWork]'s own unique name — also what
         * [AlertDigestScheduler]'s `isEnqueued`/`triggerNow` (the debug immediate-run hook) key
         * off of; the SAME worker class backs both the periodic schedule and that debug
         * `OneTimeWorkRequest`, just enqueued through two different WorkManager calls. */
        const val UNIQUE_WORK_NAME = "alert_digest"

        /** The tap-through deep-link's intent extra key — `MainActivity` reads this on `onCreate`/
         * `onNewIntent` and calls `QuakeSelectionViewModel.select(id)` with it. */
        const val EXTRA_QUAKE_ID = "com.yugma.terrawatch.EXTRA_QUAKE_ID"

        const val CHANNEL_ID = "quake_digests"
        private const val CHANNEL_NAME = "Earthquake digests"

        // Digest honesty (spec §6.5): never "early warning" -- this is a periodic poll, not
        // real-time seismic detection, and the channel description is the one place Android shows
        // users that distinction unprompted (Settings > Apps > TerraWatch > Notifications).
        private const val CHANNEL_DESCRIPTION = "Periodic digests — not an early-warning system"

        private const val KEY_LAST_RUN = "alert_last_run"
        private const val KEY_NOTIFIED_IDS = "alert_notified_ids"
        private const val MAX_INDIVIDUAL_NOTIFICATIONS = 3
        private const val NOTIFIED_IDS_CAP = 100
        private const val SUMMARY_NOTIFICATION_ID = 0
        private const val SUMMARY_REQUEST_CODE = 0
    }
}
