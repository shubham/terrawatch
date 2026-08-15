package com.yugma.terrawatch.notifications

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Task 3 (Plan 4), Task 4d slice: TDD for [reduceNotificationPermissionState] — the pure
 * condition-to-UI-state reducer the dispatch calls for ("TDD permission-state reducer pure fn
 * (granted/denied/permanently-denied/pre-33 -> UI states)"). The messier, IMPURE half (actually
 * telling GRANTED apart from DENIED apart from PERMANENTLY_DENIED, which needs
 * ContextCompat.checkSelfPermission + ActivityCompat.shouldShowRequestPermissionRationale + a
 * persisted "have we asked before" flag to fully disambiguate) lives in
 * NotificationPermissionRequester's android actual, deliberately kept OUT of this pure function —
 * this reducer's only job is mapping an already-resolved [NotificationPermissionCondition] to a
 * [NotificationAlertsUiState], the same "resolve the messy bit once at the platform boundary, TDD
 * the pure mapping" split this codebase's other reducers/pure helpers already follow.
 */
class NotificationPermissionStateTest {
    @Test fun `granted reduces to ENABLED`() {
        assertEquals(NotificationAlertsUiState.ENABLED, reduceNotificationPermissionState(NotificationPermissionCondition.GRANTED))
    }

    @Test fun `pre-33 (no runtime permission concept) reduces to ENABLED`() {
        assertEquals(NotificationAlertsUiState.ENABLED, reduceNotificationPermissionState(NotificationPermissionCondition.PRE_33))
    }

    @Test fun `denied reduces to CAN_ASK`() {
        assertEquals(NotificationAlertsUiState.CAN_ASK, reduceNotificationPermissionState(NotificationPermissionCondition.DENIED))
    }

    @Test fun `permanently denied reduces to NEEDS_SETTINGS`() {
        assertEquals(
            NotificationAlertsUiState.NEEDS_SETTINGS,
            reduceNotificationPermissionState(NotificationPermissionCondition.PERMANENTLY_DENIED),
        )
    }

    @Test fun `every condition maps to exactly one UI state -- no condition is left unhandled`() {
        // Guards against a future NotificationPermissionCondition entry silently falling through
        // to an unrelated branch -- reduceNotificationPermissionState uses an exhaustive `when`
        // with no `else`, so this test would fail to COMPILE (not just fail at runtime) the moment
        // a new entry is added without a matching branch.
        NotificationPermissionCondition.entries.forEach { condition ->
            reduceNotificationPermissionState(condition) // must not throw
        }
    }
}
