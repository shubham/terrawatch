package com.yugma.terrawatch.notifications

// Desktop has no POST_NOTIFICATIONS concept at all (spec §7: notifications are Android-only in
// v1) — PRE_33 is the accurate "nothing to gate" answer here, not a stand-in for "denied"; see
// NotificationPermissionRequester's own kdoc. Nothing on this platform ever calls request()/
// openSettings() in practice (the alerts UI reduces PRE_33 straight to ENABLED, rendering no ask
// affordance at all), but the expect/actual contract — and AppModule's uniform
// `single { NotificationPermissionRequester() }` — still need a real, constructible actual.
actual class NotificationPermissionRequester {
    actual fun currentCondition(): NotificationPermissionCondition = NotificationPermissionCondition.PRE_33
    actual fun shouldShowRationale(): Boolean = false
    actual fun request() {}
    actual fun openSettings() {}
}
