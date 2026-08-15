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
import com.yugma.terrawatch.data.filterFreshAlertEvents
import com.yugma.terrawatch.data.notifiedIdentifiers
import com.yugma.terrawatch.data.parseNotifiedIds
import com.yugma.terrawatch.data.planDigestNotifications
import com.yugma.terrawatch.database.QuakeStore
import com.yugma.terrawatch.di.ensureKoinStarted
import com.yugma.terrawatch.location.LocationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * `doWork()`'s own steps, in order:
 * 1. [ensureKoinStarted] — see that function's own kdoc for why a worker-only process start needs
 *    this at all (no custom `Application` subclass starts Koin ahead of a headless run otherwise).
 * 2. [QuakeRepository.refreshFeed] — the SAME feed poll `HomeViewModel`'s own foreground loop uses.
 * 3. [QuakeRepository.pruneOldRows] (Fix Round 1, I6) — the SAME 30-day retention sweep
 *    `HomeViewModel.init` already runs on a foreground open, now also on this worker's own
 *    periodic cadence, so a device that's rarely opened in the foreground still gets pruned.
 * 4. [QuakeStore.newSince] against the persisted `"alert_last_run"` cutoff (clamped, see the I2
 *    note below) — feed/live rows only (excludes archive/debug — see that method's own kdoc for
 *    the F5-guard parity this enforces).
 * 5. Each new row through a fresh [AlertRuleEngine], `previous = null` always: these are rows whose
 *    own `fetchedAtMillis` is newer than the last run, i.e. genuinely new-TO-THIS-DEVICE arrivals
 *    from this worker's own point of view — there is no real "previous state" to diff against the
 *    way a live revision would have one, so `null` (never suppresses on an "already exceeded"
 *    basis) is the honest mapping, not an approximation of one.
 * 6. [com.yugma.terrawatch.data.filterFreshAlertEvents] (Fix Round 1, I1) against the persisted
 *    `"alert_notified_ids"` ring buffer, then individual notifications for the top
 *    [MAX_INDIVIDUAL_NOTIFICATIONS] (by magnitude, strongest first) plus one summary if more
 *    matched — [planDigestNotifications]'s own contract.
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
 *
 * **Fix Round 1 (review findings), what changed and why:**
 * - **I1 — `newSince` cursor + ring-buffer absorption**: [QuakeStore.newSince] itself moved from a
 *   `timeMillis` to a `fetchedAtMillis` cursor (see that method's own kdoc) — this worker gets a
 *   publication-lag quake or a late magnitude revision for free from that fix, no call-site change
 *   needed here. What DOES change here: the notified-buffer filter/append now goes through
 *   [com.yugma.terrawatch.data.notifiedIdentifiers]/[filterFreshAlertEvents] (both id AND every
 *   `sources.values` entry), not a bare `quake.id` check — absorbing a canonical-id swap on a later
 *   [com.yugma.terrawatch.data.DedupeEngine] merge so it never reads as a brand-new, unnotified
 *   event.
 * - **I2 — first-run storm**: a device with no persisted `alert_last_run` yet seeds it to `now` and
 *   notifies nothing on that run, rather than treating a missing cutoff as `0L` (which would select
 *   this device's ENTIRE current feed/live table the first time this worker ever executes). Every
 *   run past the first also clamps its lookback to `now - 24h`, so a device that's gone dark for
 *   days never replays a multi-day backlog in one run.
 * - **I3**: [enqueueAlertDigestWorker]'s own `ExistingPeriodicWorkPolicy` moved `KEEP` -> `UPDATE`
 *   — see that function's own kdoc.
 * - **I5 — channel timing**: [ensureNotificationChannel] moved to the companion object (still
 *   called defensively from `runDigest` below, but its REAL creation point is now
 *   [enqueueAlertDigestWorker] — see that function's own kdoc) so the channel exists well before
 *   any notification could ever fire.
 * - **I6**: [QuakeRepository.pruneOldRows] now runs every digest, unconditionally, after the feed
 *   refresh (see step 3 above).
 * - **Minors**: individual/summary notifications now carry `setOnlyAlertOnce(true)`; the summary's
 *   notification id/`PendingIntent` request code moved `0` -> `1` (avoids a literal-zero value
 *   doubling as an "absent" sentinel elsewhere); the worker's own direct `store`/`homeLocationStore`
 *   reads (never wrapped before) now hop onto [Dispatchers.IO], matching [AlertDigestScheduler]'s
 *   own `isEnqueued` precedent — real blocking DB/synchronous I/O belongs off [Dispatchers.Default]
 *   (a small, CPU-core-sized pool this app's OTHER coroutines share), not directly on whatever
 *   dispatcher [CoroutineWorker.doWork] happens to run on by default.
 *
 * **Round 2 (re-review), what changed and why:**
 * - **Ring-buffer adequacy**: [NOTIFIED_IDS_CAP] 100 -> 1000 — see [com.yugma.terrawatch.data.
 *   appendNotifiedIds]'s own kdoc for the full worst-case-identifiers-per-run math this reflects.
 *   100 was provably too small for a permissive "near" rule (this app's own 1000 km/M3.0
 *   device-tested config, task-3-report.md) sustained over 24h: this worker's own [QuakeStore.
 *   newSince] re-selects every still-qualifying quake on every run (unaffected by whether anything
 *   about it actually changed — see [com.yugma.terrawatch.data.QuakeRepository.ingest]'s missing
 *   content-diff gate, logged to `docs/superpowers/plans/plan-4-backlog.md`, not fixed this round),
 *   so a busy enough period could evict a still-active quake's id from a too-small buffer and let
 *   that same re-selection present it as freshly-fetched again on a later run — a genuine duplicate
 *   notification for something the user already saw.
 * - **I2's clamp, disclosed honestly**: the 24h lookback clamp (I2, above) is a deliberate trade,
 *   not a hidden gap — any matching quake, including a world-rule M6+, that falls entirely inside
 *   the clamped-away portion of a long-dark gap is never evaluated by this worker at all and
 *   produces no notification, with no user-visible signal that a window was skipped, in exchange
 *   for never storming a returning user with a multi-day backlog (the same "digest, not
 *   early-warning" honesty spec §6.5 already asks of every notification's own copy).
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

        // Fix Round 1 (I6): retention housekeeping, unconditional -- independent of whatever the
        // alert cursor below decides. Mirrors HomeViewModel.init's own identical call (see
        // QuakeRepository.pruneOldRows' own kdoc), just off this worker's own periodic cadence
        // instead of a foreground app-open, so a rarely-opened device still gets pruned.
        repository.pruneOldRows(nowMillis - PRUNE_WINDOW_MS)

        val persistedLastRun = withContext(Dispatchers.IO) { store.metaGet(KEY_LAST_RUN) }?.toLongOrNull()
        if (persistedLastRun == null) {
            // Fix Round 1 (I2): first-ever run on this device -- seed the baseline and notify
            // nothing. Without this, newSince(0L) would return this device's entire feed/live
            // table the first time this worker ever executes (WorkManager runs a periodic
            // request's first tick almost immediately after enqueue -- see AlertDigestScheduler's
            // own kdoc -- so this is the NORMAL first-run shape, not a rare edge case), storming
            // the user with a backlog of quakes that are only "new" to this worker, not to the
            // world.
            withContext(Dispatchers.IO) { store.metaPutAll(KEY_LAST_RUN to nowMillis.toString()) }
            return Result.success()
        }
        // Fix Round 1 (I2): clamp the lookback to the last 24h on every run past the first -- a
        // device that's gone dark for days (Doze, OS-deferred jobs, a long app-kill) would
        // otherwise replay its ENTIRE multi-day backlog the moment this worker finally gets to run
        // again.
        val lastRun = maxOf(persistedLastRun, nowMillis - LOOKBACK_CAP_MS)

        val newRows = withContext(Dispatchers.IO) { store.newSince(lastRun) }

        val rules = repository.currentRules()
        val home = withContext(Dispatchers.IO) { homeLocationStore.get() }
        val engine = AlertRuleEngine()
        val matched = newRows.mapNotNull { engine.evaluate(previous = null, current = it, rules = rules, home = home) }

        val alreadyNotifiedCsv = withContext(Dispatchers.IO) { store.metaGet(KEY_NOTIFIED_IDS) }
        val alreadyNotified = parseNotifiedIds(alreadyNotifiedCsv).toSet()
        // Fix Round 1 (I1): filterFreshAlertEvents (core:data, TDD'd) absorbs a canonical-id swap
        // on a later DedupeEngine merge -- see that function's own kdoc, and notifiedIdentifiers'
        // own kdoc for why `sources.values` (not just the row's CURRENT id) is what has to be
        // checked/recorded on both sides of this ring buffer.
        val fresh = filterFreshAlertEvents(matched, alreadyNotified)
            .sortedWith(compareByDescending<AlertEvent> { it.quake.mag ?: 0.0 }.thenByDescending { it.quake.timeMillis })

        if (fresh.isNotEmpty() && NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) {
            // Fix Round 1 (I5): idempotent belt-and-suspenders only -- the channel's REAL creation
            // point is now enqueueAlertDigestWorker (AlertDigestScheduler.android.kt), at enqueue
            // time, not first-notify. See that function's own kdoc for why the timing matters.
            ensureNotificationChannel(applicationContext)
            val plan = planDigestNotifications(fresh, maxIndividual = MAX_INDIVIDUAL_NOTIFICATIONS)
            plan.individual.forEach { event -> notifyIndividual(applicationContext, event, nowMillis) }
            if (plan.summaryExtraCount > 0) notifySummary(applicationContext, plan.summaryExtraCount)
        }

        withContext(Dispatchers.IO) {
            store.metaPutAll(
                KEY_LAST_RUN to nowMillis.toString(),
                KEY_NOTIFIED_IDS to appendNotifiedIds(
                    alreadyNotifiedCsv,
                    fresh.flatMap { notifiedIdentifiers(it) },
                    cap = NOTIFIED_IDS_CAP,
                ),
            )
        }
        return Result.success()
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
            // Fix Round 1 (minor): a re-post of the SAME notification id (shouldn't normally
            // happen -- the ring buffer already suppresses re-notifying an already-seen event --
            // but costs nothing to make inert if it ever did) must not re-alert (sound/vibrate)
            // the user a second time for what would visually be the identical notification.
            .setOnlyAlertOnce(true)
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
            // Fix Round 1 (minor): same reasoning as notifyIndividual above -- the summary uses a
            // single fixed id across every run, so a later run updating its count must not re-alert.
            .setOnlyAlertOnce(true)
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

        // Round 2 (ring-buffer adequacy, review finding): 100 -> 1000 -- see
        // com.yugma.terrawatch.data.appendNotifiedIds' own kdoc for the full adequacy math this
        // reflects (100 was provably too small for a permissive "near" rule, e.g. this app's own
        // 1000 km/M3.0 device-tested config, sustained over 24h).
        private const val NOTIFIED_IDS_CAP = 1000

        // Fix Round 1 (minor): 0 -> 1 -- a literal-zero notification id/PendingIntent request code
        // reads too easily as an "absent"/unset sentinel elsewhere in this codebase and the wider
        // Android ecosystem (e.g. `Service.startForeground`'s own "notification id must not be 0"
        // rule); 1 carries no such ambiguity while remaining just as fixed/constant as 0 was.
        private const val SUMMARY_NOTIFICATION_ID = 1
        private const val SUMMARY_REQUEST_CODE = 1

        // Fix Round 1 (I6): QuakeRepository.pruneOldRows' own 30-day retention window, run from
        // this worker on every digest (see runDigest's own step 3) -- same literal window
        // HomeViewModel.init already uses for its foreground-open sweep.
        private const val PRUNE_WINDOW_MS = 30L * 24 * 60 * 60 * 1000

        // Fix Round 1 (I2): the lookback clamp -- see runDigest's own comment for why a stale
        // persisted alert_last_run must not be allowed to replay an unbounded backlog.
        private const val LOOKBACK_CAP_MS = 24L * 60 * 60 * 1000

        /**
         * Fix Round 1 (I5, review finding): moved here (companion, non-private, was a private
         * instance method) so [com.yugma.terrawatch.alerts.enqueueAlertDigestWorker]
         * (`AlertDigestScheduler.android.kt`, same package) can create this channel at ENQUEUE
         * time — see that function's own kdoc for why "first notify" was too late. `runDigest`
         * above still calls this too, defensively (idempotent — creating an already-existing
         * channel is a documented no-op), but is no longer this channel's real creation point.
         */
        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                // Digest honesty (spec §6.5): this channel's own description is where the OS
                // actually shows users what this class of notification is for (Settings > Apps >
                // TerraWatch > Notifications) — the one place this framing needs to live exactly
                // once, independent of any single notification's own copy.
                description = CHANNEL_DESCRIPTION
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
