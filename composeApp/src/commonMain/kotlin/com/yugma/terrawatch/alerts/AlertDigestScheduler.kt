package com.yugma.terrawatch.alerts

/**
 * The thin, commonMain-callable surface Settings' ALERTS row needs onto `AlertDigestWorker`'s
 * (androidMain) real WorkManager job — everything ELSE about that worker (its own class, the
 * periodic enqueue call, the notification channel/rendering) stays androidMain-only with no
 * commonMain expect declaration at all, since nothing outside androidMain ever needs to call
 * those directly (`MainActivity` — androidMain — is the one real caller). This class exists only
 * for the two things a commonMain SCREEN genuinely needs: "is the digest actually scheduled right
 * now" (so the ALERTS row's "On"/"Off" text is honest, not just inferred from permission state)
 * and a debug-only immediate trigger (this task's own device-verification device hook — a
 * `OneTimeWorkRequest` for the same worker, so a real notification can be produced on demand
 * without waiting up to 45 minutes for the periodic schedule).
 *
 * No-arg constructor for the same "uniform `single { AlertDigestScheduler() }` registration
 * across every target" reason [com.yugma.terrawatch.location.LocationRequester]/[com.yugma.
 * terrawatch.notifications.NotificationPermissionRequester] both already establish.
 */
expect class AlertDigestScheduler() {
    /** Whether `AlertDigestWorker`'s unique periodic work currently has a live (non-cancelled)
     * `WorkInfo` entry — a real WorkManager query on android, always `false` on jvm/wasmJs (no
     * WorkManager there at all). Suspending: queries WorkManager's own backing Room database,
     * which this call moves off Main rather than blocking composition on. */
    suspend fun isEnqueued(): Boolean

    /** Whether [triggerNow] can do anything on this build/platform — true only for a debuggable
     * Android build (mirrors `QuakeMap.android.kt`'s own `isDebuggableBuild` check, the same
     * "debug-only device verification hook" gate this codebase already established for the map's
     * long-press quake-inject trigger). Always false on jvm/wasmJs. */
    fun isDebugTriggerAvailable(): Boolean

    /** Enqueues one immediate `OneTimeWorkRequest` for the SAME `AlertDigestWorker` class the real
     * periodic schedule uses — a debug-only escape hatch so a device verification pass doesn't
     * have to wait for the periodic 45-minute cadence to see a real notification. No-op unless
     * [isDebugTriggerAvailable]. */
    fun triggerNow()
}
