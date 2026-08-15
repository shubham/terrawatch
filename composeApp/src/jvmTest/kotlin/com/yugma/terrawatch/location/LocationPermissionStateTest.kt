package com.yugma.terrawatch.location

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Plan 4 Task 4 (d): TDD for [reduceLocationPermissionState] — mirrors
 * `NotificationPermissionStateTest` (composeApp/jvmTest/notifications) exactly, one test per
 * [LocationPermissionCondition] entry plus an exhaustiveness guard, for the identical "TDD the pure
 * mapping, keep the messy ContextCompat/ActivityCompat resolution out of it" reason that class's own
 * kdoc gives.
 */
class LocationPermissionStateTest {
    @Test fun `granted reduces to GRANTED`() {
        assertEquals(LocationAskUiState.GRANTED, reduceLocationPermissionState(LocationPermissionCondition.GRANTED))
    }

    @Test fun `not applicable (jvm-wasmJs, no OS permission concept) reduces to GRANTED`() {
        assertEquals(LocationAskUiState.GRANTED, reduceLocationPermissionState(LocationPermissionCondition.NOT_APPLICABLE))
    }

    @Test fun `denied reduces to CAN_ASK`() {
        assertEquals(LocationAskUiState.CAN_ASK, reduceLocationPermissionState(LocationPermissionCondition.DENIED))
    }

    @Test fun `permanently denied reduces to NEEDS_SETTINGS`() {
        assertEquals(
            LocationAskUiState.NEEDS_SETTINGS,
            reduceLocationPermissionState(LocationPermissionCondition.PERMANENTLY_DENIED),
        )
    }

    @Test fun `every condition maps to exactly one UI state -- no condition is left unhandled`() {
        // Guards against a future LocationPermissionCondition entry silently falling through to an
        // unrelated branch -- reduceLocationPermissionState uses an exhaustive `when` with no `else`,
        // so this test would fail to COMPILE (not just fail at runtime) the moment a new entry is
        // added without a matching branch.
        LocationPermissionCondition.entries.forEach { condition ->
            reduceLocationPermissionState(condition) // must not throw
        }
    }
}
