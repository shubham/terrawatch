package com.yugma.terrawatch.notifications

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.yugma.terrawatch.alerts.AlertDigestWorker

// MainActivity rebinds this on EVERY onCreate (not gated behind its own firstLaunchThisProcess
// guard) — a config-change recreate needs THIS instance's Activity-scoped closures, never a
// previous, destroyed Activity's. Same "process-lifetime holder substitutes for a constructor
// parameter the shared expect signature can't carry" shape com.yugma.terrawatch.location.
// LocationRequester.android.kt's own launchPermissionRequest holder already establishes — bundled
// into one small class here instead of four independent `private var`s purely to cut boilerplate,
// not a behavior difference.
private class NotificationPermissionController(
    val condition: () -> NotificationPermissionCondition,
    val rationale: () -> Boolean,
    val launch: () -> Unit,
    val openSettingsAction: () -> Unit,
)

private var controller: NotificationPermissionController? = null

/** Must run before an onboarding step-3/Settings-row tap can plausibly reach
 * [NotificationPermissionRequester] — i.e. before `MainActivity`'s `setContent {}` composes either
 * screen — same ordering guarantee [com.yugma.terrawatch.location.bindLocationRequestLauncher]
 * documents for the identical reason. */
fun bindNotificationPermissionController(
    condition: () -> NotificationPermissionCondition,
    rationale: () -> Boolean,
    launch: () -> Unit,
    openSettingsAction: () -> Unit,
) {
    controller = NotificationPermissionController(condition, rationale, launch, openSettingsAction)
}

actual class NotificationPermissionRequester {
    /** A call before [bindNotificationPermissionController] has ever run degrades to [
     * NotificationPermissionCondition.DENIED] rather than throwing — impossible in practice (see
     * that function's own kdoc), but matches this app's established "a missing precondition
     * degrades quietly, never crashes" convention (see [com.yugma.terrawatch.location.
     * LocationProvider]'s android actual). */
    actual fun currentCondition(): NotificationPermissionCondition =
        controller?.condition?.invoke() ?: NotificationPermissionCondition.DENIED

    actual fun shouldShowRationale(): Boolean = controller?.rationale?.invoke() ?: false

    actual fun request() { controller?.launch?.invoke() }

    actual fun openSettings() { controller?.openSettingsAction?.invoke() }
}

private const val PREFS_NAME = "terrawatch_notification_prefs"
private const val KEY_HAS_ASKED = "has_asked_post_notifications"

/**
 * Resolves the real, live [NotificationPermissionCondition] for [activity] — the impure half this
 * codebase's TDD'd [reduceNotificationPermissionState] deliberately stays free of (see that
 * function's own kdoc: the pure reducer only maps an already-resolved condition to a UI state).
 *
 * [Build.VERSION.SDK_INT] < 33 is checked FIRST, before ever calling [ContextCompat.
 * checkSelfPermission]/[currentNotificationRationale] with `POST_NOTIFICATIONS` at all —
 * that permission string didn't exist as a runtime concept before API 33 (notifications were
 * simply always allowed), so this sidesteps any ambiguity about what checking an
 * OS-version-unknown permission would even mean, rather than trusting it to degrade sensibly on
 * every OEM skin.
 *
 * [NotificationPermissionCondition.DENIED] vs. [NotificationPermissionCondition.PERMANENTLY_DENIED]
 * is the classic Android ambiguity: `shouldShowRequestPermissionRationale() == false` means EITHER
 * "never asked yet" OR "permanently denied" — the OS provides no way to tell those apart on its
 * own. Disambiguated here via [hasAskedBefore], a small local flag [markNotificationPermissionAsked]
 * sets the moment [NotificationPermissionRequester.request] is ever actually invoked: never-asked
 * (flag unset) reads as recoverable [NotificationPermissionCondition.DENIED]; asked-at-least-once
 * -and-still-denied-with-no-rationale reads as [NotificationPermissionCondition.PERMANENTLY_DENIED].
 *
 * Fix Round 1 (I4, review finding): the raw permission/rationale resolution above (now
 * [rawPermissionCondition]) only ever answered "is POST_NOTIFICATIONS granted" — it said nothing
 * about whether a notification would actually SHOW. [foldSystemNotificationState] (TDD'd,
 * `NotificationPermissionState.kt`) folds in the two system-level signals that check: the app-level
 * `NotificationManagerCompat.areNotificationsEnabled()` toggle and this app's own digest channel's
 * importance ([isDigestChannelBlocked]) — both independently toggleable via system Settings without
 * ever touching the POST_NOTIFICATIONS runtime permission itself, on 33+ AND pre-33 alike.
 */
fun computeNotificationPermissionCondition(activity: Activity): NotificationPermissionCondition =
    foldSystemNotificationState(
        rawCondition = rawPermissionCondition(activity),
        notificationsEnabledAtSystemLevel = NotificationManagerCompat.from(activity).areNotificationsEnabled(),
        channelBlocked = isDigestChannelBlocked(activity),
    )

/** The OS PERMISSION-only half of [computeNotificationPermissionCondition] — unchanged logic from
 * before Fix Round 1, just factored out so [foldSystemNotificationState] can layer the
 * system-level checks on top without duplicating this resolution. */
private fun rawPermissionCondition(activity: Activity): NotificationPermissionCondition {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return NotificationPermissionCondition.PRE_33
    val granted = ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    if (granted) return NotificationPermissionCondition.GRANTED
    return when {
        currentNotificationRationale(activity) -> NotificationPermissionCondition.DENIED
        hasAskedBefore(activity) -> NotificationPermissionCondition.PERMANENTLY_DENIED
        else -> NotificationPermissionCondition.DENIED
    }
}

/**
 * Fix Round 1 (I4): true when this app's own digest channel ([AlertDigestWorker.CHANNEL_ID])
 * exists AND the user has muted it specifically (`IMPORTANCE_NONE`) — distinct from the app-level
 * POST_NOTIFICATIONS/areNotificationsEnabled checks, since Android lets a user silence one channel
 * while leaving the app's other notifications (none exist yet here, but the API doesn't know that)
 * untouched. A channel that doesn't exist yet — shouldn't happen once [com.yugma.terrawatch.alerts.
 * enqueueAlertDigestWorker] creates it at enqueue time (see that function's own I5 note) — reads as
 * "not blocked," never as a false positive: this check can only ever make the resolved condition
 * WORSE than the permission alone would say, never better, so a not-yet-created channel silently
 * degrading to "not blocked" costs nothing.
 */
private fun isDigestChannelBlocked(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    val channel = context.getSystemService(NotificationManager::class.java)
        ?.getNotificationChannel(AlertDigestWorker.CHANNEL_ID)
        ?: return false
    return channel.importance == NotificationManager.IMPORTANCE_NONE
}

/** [NotificationPermissionRequester.shouldShowRationale]'s real android implementation — also
 * reused internally by [computeNotificationPermissionCondition] above, one source of truth for
 * the raw OS check both need. */
fun currentNotificationRationale(activity: Activity): Boolean =
    ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)

private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

private fun hasAskedBefore(context: Context): Boolean = prefs(context).getBoolean(KEY_HAS_ASKED, false)

/** Called the moment the real OS dialog is actually launched (`MainActivity`'s own bound `launch`
 * closure) — see [computeNotificationPermissionCondition]'s own kdoc for why this flag is what
 * disambiguates [NotificationPermissionCondition.DENIED] from [NotificationPermissionCondition.
 * PERMANENTLY_DENIED] on every later resolve. */
fun markNotificationPermissionAsked(context: Context) {
    prefs(context).edit().putBoolean(KEY_HAS_ASKED, true).apply()
}

/** [NotificationPermissionRequester.openSettings]'s real android implementation —
 * `Settings.ACTION_APP_NOTIFICATION_SETTINGS`, the one recovery path once [
 * NotificationPermissionCondition.PERMANENTLY_DENIED] (the OS itself refuses to show the in-app
 * ask dialog again at that point). `FLAG_ACTIVITY_NEW_TASK` because [context] here is always the
 * Activity itself in practice (bound directly from `MainActivity`), but kept regardless in case a
 * future caller ever binds an application context instead — starting an Activity from a
 * non-Activity context always needs it. */
fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
