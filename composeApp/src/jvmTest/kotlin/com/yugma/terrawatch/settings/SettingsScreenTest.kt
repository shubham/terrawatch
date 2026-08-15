package com.yugma.terrawatch.settings

import com.yugma.terrawatch.notifications.NotificationAlertsUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Task 7 (Plan 3): [closestRadiusStepIndex]/[snapToHalfMagnitude] are the two small pure helpers
 * `SettingsScreen`'s sliders lean on — pulled out `internal` (not `private`) so they're testable
 * without a Compose runtime, same "so a test can pin it" convention `InsightsScreenTest` already
 * uses for `InsightsScreen.dayCountLabels`.
 */
class SettingsScreenTest {
    @Test fun `closestRadiusStepIndex resolves each exact step to its own index`() {
        assertEquals(0, closestRadiusStepIndex(50.0))
        assertEquals(1, closestRadiusStepIndex(100.0))
        assertEquals(2, closestRadiusStepIndex(250.0))
        assertEquals(3, closestRadiusStepIndex(500.0))
        assertEquals(4, closestRadiusStepIndex(1000.0))
    }

    @Test fun `closestRadiusStepIndex snaps a value between two steps to the nearer one`() {
        assertEquals(0, closestRadiusStepIndex(60.0)) // closer to 50 than 100
        assertEquals(1, closestRadiusStepIndex(90.0)) // closer to 100 than 50
        assertEquals(2, closestRadiusStepIndex(200.0)) // closer to 250 than 100
    }

    @Test fun `closestRadiusStepIndex clamps a wildly out-of-range corrupt value to the nearest end`() {
        assertEquals(0, closestRadiusStepIndex(-500.0))
        assertEquals(4, closestRadiusStepIndex(1_000_000.0))
    }

    @Test fun `snapToHalfMagnitude leaves an exact half-step untouched`() {
        assertEquals(4.5, snapToHalfMagnitude(4.5))
        assertEquals(3.0, snapToHalfMagnitude(3.0))
        assertEquals(6.0, snapToHalfMagnitude(6.0))
    }

    @Test fun `snapToHalfMagnitude rounds to the nearest half-step`() {
        assertEquals(4.5, snapToHalfMagnitude(4.6))
        assertEquals(5.0, snapToHalfMagnitude(4.76))
        assertEquals(4.0, snapToHalfMagnitude(4.24))
    }

    @Test fun `app version constant pins the released version string`() {
        assertEquals("0.9.0", APP_VERSION)
    }

    // --- Task 3 (Plan 4): the ALERTS section's permission/worker-state row -----------------------

    @Test fun `alertsRowStatusText is On only when ENABLED and the worker is actually enqueued`() {
        assertEquals("On", alertsRowStatusText(NotificationAlertsUiState.ENABLED, enqueued = true))
    }

    @Test fun `alertsRowStatusText is Off when ENABLED but the worker isn't enqueued -- honest, not inferred`() {
        assertEquals("Off", alertsRowStatusText(NotificationAlertsUiState.ENABLED, enqueued = false))
    }

    @Test fun `alertsRowStatusText is Off when permission itself isn't enabled, regardless of enqueued`() {
        assertEquals("Off", alertsRowStatusText(NotificationAlertsUiState.CAN_ASK, enqueued = true))
        assertEquals("Off", alertsRowStatusText(NotificationAlertsUiState.NEEDS_SETTINGS, enqueued = true))
    }

    @Test fun `alertsRowExplainer is null when ENABLED -- no explanation needed`() {
        assertNull(alertsRowExplainer(NotificationAlertsUiState.ENABLED))
    }

    @Test fun `alertsRowExplainer is non-null and mentions Settings for CAN_ASK and NEEDS_SETTINGS alike`() {
        val canAsk = alertsRowExplainer(NotificationAlertsUiState.CAN_ASK)
        val needsSettings = alertsRowExplainer(NotificationAlertsUiState.NEEDS_SETTINGS)
        assertEquals(canAsk, needsSettings) // Settings' own row uses one shape for both -- see SettingsScreen's kdoc
        assertEquals(
            "Notifications are off — earthquake digests can't be delivered. Enable them in system Settings.",
            canAsk,
        )
    }
}
