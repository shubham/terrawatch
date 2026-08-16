package com.yugma.terrawatch.alerts

// Web has no WorkManager equivalent either — same reasoning as the jvm actual; see
// AlertDigestScheduler's own kdoc.
actual class AlertDigestScheduler {
    actual suspend fun isEnqueued(): Boolean = false
    actual fun isDebugTriggerAvailable(): Boolean = false
    actual fun triggerNow() {}
    actual fun ensureEnqueued() {}
}
