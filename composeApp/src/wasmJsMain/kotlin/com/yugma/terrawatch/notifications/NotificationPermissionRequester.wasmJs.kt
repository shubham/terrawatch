package com.yugma.terrawatch.notifications

// Browser notifications land later, if ever (spec §7: notifications are Android-only in v1) — same
// "PRE_33 is the accurate 'nothing to gate' answer" reasoning as the jvm actual; see that file's
// own kdoc and NotificationPermissionRequester's.
actual class NotificationPermissionRequester {
    actual fun currentCondition(): NotificationPermissionCondition = NotificationPermissionCondition.PRE_33
    actual fun shouldShowRationale(): Boolean = false
    actual fun request() {}
    actual fun openSettings() {}
}
