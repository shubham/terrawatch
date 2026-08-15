package com.yugma.terrawatch.location

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

// MainActivity assigns this to itself on EVERY onCreate() (see MainActivity.kt's onCreate), not
// only its firstLaunchThisProcess guard: a config-change recreate constructs a brand-new
// ActivityResultLauncher tied to a brand-new Activity instance, so this holder must always point at
// the CURRENT one, never a stale reference to a destroyed Activity's launcher. Same
// "process-lifetime holder substitutes for a constructor parameter the shared expect signature
// can't carry" shape as Share.android.kt's appContext/initShareContext — applied here to
// LocationRequester's no-arg constructor (needed so AppModule.kt's commonMain
// `single { LocationRequester() }` can build it uniformly across all three targets) instead of a
// top-level function's own parameter.
//
// Plan 4 Task 4 (d): bundles condition/rationale/launch/openSettingsAction into one small holder,
// mirroring com.yugma.terrawatch.notifications.NotificationPermissionController's identical shape
// 1:1 — replaces the old bare `launchPermissionRequest: (() -> Unit)?` this file used to carry.
private class LocationPermissionController(
    val condition: () -> LocationPermissionCondition,
    val rationale: () -> Boolean,
    val launch: () -> Unit,
    val openSettingsAction: () -> Unit,
)

private var controller: LocationPermissionController? = null

/** Must run before onboarding step 2's/Settings' "Use my location" button can plausibly be tapped —
 * i.e. before MainActivity's setContent{} composes either screen — same ordering guarantee
 * [com.yugma.terrawatch.notifications.bindNotificationPermissionController] documents for the
 * identical reason. */
fun bindLocationPermissionController(
    condition: () -> LocationPermissionCondition,
    rationale: () -> Boolean,
    launch: () -> Unit,
    openSettingsAction: () -> Unit,
) {
    controller = LocationPermissionController(condition, rationale, launch, openSettingsAction)
}

actual class LocationRequester {
    /** A call before [bindLocationPermissionController] has ever run degrades to a silent no-op
     * rather than throwing — impossible in practice (see that function's own kdoc), but matches this
     * app's established "a missing precondition degrades quietly, never crashes" convention (see
     * [com.yugma.terrawatch.location.LocationProvider]'s android actual). */
    actual fun request() {
        controller?.launch?.invoke()
    }

    /** A call before [bindLocationPermissionController] has ever run degrades to [
     * LocationPermissionCondition.DENIED] rather than throwing — same degrade-quietly convention
     * [com.yugma.terrawatch.notifications.NotificationPermissionRequester.currentCondition] already
     * establishes for the identical reason. */
    actual fun currentCondition(): LocationPermissionCondition =
        controller?.condition?.invoke() ?: LocationPermissionCondition.DENIED

    actual fun shouldShowRationale(): Boolean = controller?.rationale?.invoke() ?: false

    actual fun openSettings() {
        controller?.openSettingsAction?.invoke()
    }
}

actual fun canRequestLocation(): Boolean = true

private const val PREFS_NAME = "terrawatch_location_prefs"
private const val KEY_HAS_ASKED = "has_asked_location"

/**
 * Resolves the real, live [LocationPermissionCondition] for [activity] — the impure half
 * [reduceLocationPermissionState] deliberately stays free of, same split
 * [com.yugma.terrawatch.notifications.computeNotificationPermissionCondition]'s own kdoc describes
 * for the identical reason.
 *
 * [LocationPermissionCondition.DENIED] vs. [LocationPermissionCondition.PERMANENTLY_DENIED] is the
 * same classic Android ambiguity that function's kdoc documents: `shouldShowRequestPermissionRationale
 * () == false` means EITHER "never asked yet" OR "permanently denied" — disambiguated here via
 * [hasAskedLocationBefore], a small local flag [markLocationPermissionAsked] sets the moment
 * [LocationRequester.request] is ever actually invoked, mirroring
 * [com.yugma.terrawatch.notifications.markNotificationPermissionAsked]'s identical role.
 *
 * Unlike [com.yugma.terrawatch.notifications.computeNotificationPermissionCondition], there is no
 * API-level branch here at all — `ACCESS_COARSE_LOCATION` has been a runtime-checked permission
 * since API 23, well below this app's minSdk 26, so every device this app runs on always has a real
 * permission to resolve (see [LocationPermissionCondition]'s own kdoc for why [LocationPermissionCondition
 * .NOT_APPLICABLE] exists only on jvm/wasmJs, never as a branch of this android function).
 */
fun computeLocationPermissionCondition(activity: Activity): LocationPermissionCondition {
    val granted = ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    if (granted) return LocationPermissionCondition.GRANTED
    return when {
        currentLocationPermissionRationale(activity) -> LocationPermissionCondition.DENIED
        hasAskedLocationBefore(activity) -> LocationPermissionCondition.PERMANENTLY_DENIED
        else -> LocationPermissionCondition.DENIED
    }
}

/** [LocationRequester.shouldShowRationale]'s real android implementation — also reused internally by
 * [computeLocationPermissionCondition] above, one source of truth for the raw OS check both need. */
fun currentLocationPermissionRationale(activity: Activity): Boolean =
    ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_COARSE_LOCATION)

private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

private fun hasAskedLocationBefore(context: Context): Boolean = prefs(context).getBoolean(KEY_HAS_ASKED, false)

/** Called the moment the real OS dialog is actually launched (`MainActivity`'s own bound `launch`
 * closure) — see [computeLocationPermissionCondition]'s own kdoc for why this flag is what
 * disambiguates [LocationPermissionCondition.DENIED] from [LocationPermissionCondition
 * .PERMANENTLY_DENIED] on every later resolve. */
fun markLocationPermissionAsked(context: Context) {
    prefs(context).edit().putBoolean(KEY_HAS_ASKED, true).apply()
}

/** [LocationRequester.openSettings]'s real android implementation —
 * `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` (a `package:` URI naming this app), the one recovery
 * path once [LocationPermissionCondition.PERMANENTLY_DENIED] — there is no location-specific settings
 * intent action the way notifications have `ACTION_APP_NOTIFICATION_SETTINGS`
 * ([com.yugma.terrawatch.notifications.openNotificationSettings]), so the general app-details page
 * (whose own Permissions row lists Location) is the standard target every Android guide points to for
 * this exact recovery flow. `FLAG_ACTIVITY_NEW_TASK` for the identical reason that function's own
 * kdoc gives: [context] is always the Activity itself in practice, but kept regardless in case a
 * future caller ever binds an application context instead. */
fun openLocationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
