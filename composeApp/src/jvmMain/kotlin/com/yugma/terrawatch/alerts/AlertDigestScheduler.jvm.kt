package com.yugma.terrawatch.alerts

// Desktop has no WorkManager equivalent (spec §7: background work is Android-only in v1) — see
// AlertDigestScheduler's own kdoc. Still needs a real, constructible actual for AppModule's uniform
// `single { AlertDigestScheduler() }`.
actual class AlertDigestScheduler {
    actual suspend fun isEnqueued(): Boolean = false
    actual fun isDebugTriggerAvailable(): Boolean = false
    actual fun triggerNow() {}
    actual fun ensureEnqueued() {}
}
