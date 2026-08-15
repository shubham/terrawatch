package com.yugma.terrawatch.notifications

/**
 * The platform half of the notification-permission ask — commonMain has no Activity and no
 * Composable-scoped permission API of its own to call directly, same reason [com.yugma.terrawatch.
 * location.LocationRequester] exists for the coarse-location ask. Explicit no-arg constructor for
 * the identical reason that class documents: registered as a plain `single { NotificationPermissionRequester() }`
 * in the shared `AppModule.kt`, so it must be constructible the same way on every target.
 *
 * - android: reads live state via a process-lifetime holder `MainActivity` rebinds on every
 *   `onCreate` (mirrors [com.yugma.terrawatch.location.bindLocationRequestLauncher]'s own shape —
 *   see `NotificationPermissionRequester.android.kt`).
 * - jvm/wasmJs: [currentCondition] always answers [NotificationPermissionCondition.PRE_33] —
 *   neither platform has a POST_NOTIFICATIONS concept at all (spec §7: notifications are
 *   Android-only in v1), so "nothing to gate" is the accurate answer, not a stand-in for "denied."
 *   [shouldShowRationale] is always false and [request]/[openSettings] are no-ops there.
 */
expect class NotificationPermissionRequester() {
    /** Resolves the current [NotificationPermissionCondition] — see that enum's own kdoc for what
     * each value means and how the android actual disambiguates [NotificationPermissionCondition.
     * DENIED] from [NotificationPermissionCondition.PERMANENTLY_DENIED]. */
    fun currentCondition(): NotificationPermissionCondition

    /** Whether the OS wants a rationale shown before asking again right now (Android's own
     * `shouldShowRequestPermissionRationale`) — queried at the moment a user taps an "Enable
     * alerts" affordance, not folded into the steady-state [currentCondition], since it's a
     * one-shot "how should THIS tap behave" question rather part of what the UI shows at rest. */
    fun shouldShowRationale(): Boolean

    /** Launches the real OS permission dialog. No-op if [currentCondition] is already [
     * NotificationPermissionCondition.GRANTED]/[NotificationPermissionCondition.PRE_33] (nothing to
     * ask), or on jvm/wasmJs (nothing CAN ask). */
    fun request()

    /** Deep-links to this app's notification settings page in the system Settings app —
     * `Settings.ACTION_APP_NOTIFICATION_SETTINGS` on android; a no-op elsewhere. The only recovery
     * path once [NotificationPermissionCondition.PERMANENTLY_DENIED], since the OS itself refuses
     * to show the in-app dialog again at that point. */
    fun openSettings()
}
