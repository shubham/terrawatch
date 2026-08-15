package com.yugma.terrawatch.location

/**
 * The raw conditions [LocationRequester.currentCondition] can resolve to — Plan 4 Task 4 (d)'s own
 * dispatch calls for mirroring Task 3's notification-permission reducer pattern
 * ([com.yugma.terrawatch.notifications.NotificationPermissionCondition]) for the location half.
 * Resolving WHICH one currently holds is the impure, platform-specific half (Android's
 * `ContextCompat.checkSelfPermission` + `ActivityCompat.shouldShowRequestPermissionRationale` + a
 * persisted "have we asked before" flag to fully disambiguate [DENIED] from [PERMANENTLY_DENIED] —
 * see [LocationRequester]'s android actual for that) — this enum and
 * [reduceLocationPermissionState] only model the pure mapping from an already-resolved condition to
 * what the UI should show.
 *
 * No `PRE_33`-equivalent value here: unlike `POST_NOTIFICATIONS` (a runtime permission only as of
 * API 33), `ACCESS_COARSE_LOCATION` has been a runtime-checked permission since API 23 — every API
 * level this app supports (minSdk 26+) always has a real permission to resolve. [NOT_APPLICABLE]
 * fills the platform-parity role [com.yugma.terrawatch.notifications.NotificationPermissionCondition
 * .PRE_33] plays instead: jvm/wasmJs have no OS-level location permission concept at all (see
 * [LocationProvider]'s own kdoc), so their [LocationRequester] actuals always report it.
 */
enum class LocationPermissionCondition {
    /** ACCESS_COARSE_LOCATION is currently granted. */
    GRANTED,

    /** Not granted, but the OS will still show the system ask dialog again (either never asked
     * before, or asked once and denied without "don't ask again"). */
    DENIED,

    /** Not granted, and the OS will NOT show the system ask dialog again — only a Settings
     * deep-link can recover this. */
    PERMANENTLY_DENIED,

    /** jvm/wasmJs only: neither platform has an OS-level location permission to ask for at all
     * (see [canRequestLocation]'s own kdoc) — "nothing to gate" is the accurate answer, not a
     * stand-in for "denied." */
    NOT_APPLICABLE,
}

/** What the location-ask UI (onboarding step 2, Settings' PLACE-section "Use my location" row)
 * should show for a given [LocationPermissionCondition]. */
enum class LocationAskUiState {
    /** Already usable — either really granted, or [LocationPermissionCondition.NOT_APPLICABLE]
     * (nothing to grant in the first place). No action needed. */
    GRANTED,

    /** Not usable yet, but an in-app ask can still work — render a "Use my location" affordance. */
    CAN_ASK,

    /** Not usable, and only the system Settings screen can fix it — render an explainer plus a
     * Settings deep-link, never a re-ask affordance the OS would silently no-op. */
    NEEDS_SETTINGS,
}

/**
 * The pure reducer Task 4 (d)'s dispatch calls for — mirrors
 * [com.yugma.terrawatch.notifications.reduceNotificationPermissionState] exactly: [GRANTED] and
 * [LocationPermissionCondition.NOT_APPLICABLE] both mean "a location ask/lookup will actually work
 * right now," collapsing to the same [LocationAskUiState.GRANTED] deliberately, for the identical
 * reason that function's own kdoc gives — the UI has no reason to distinguish "really granted" from
 * "nothing to grant" when both produce the same lived experience.
 */
fun reduceLocationPermissionState(condition: LocationPermissionCondition): LocationAskUiState =
    when (condition) {
        LocationPermissionCondition.GRANTED, LocationPermissionCondition.NOT_APPLICABLE -> LocationAskUiState.GRANTED
        LocationPermissionCondition.DENIED -> LocationAskUiState.CAN_ASK
        LocationPermissionCondition.PERMANENTLY_DENIED -> LocationAskUiState.NEEDS_SETTINGS
    }
