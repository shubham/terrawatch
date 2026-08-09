package com.yugma.terrawatch.settings

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
