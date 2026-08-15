package com.yugma.terrawatch.location

/**
 * Triggers the platform's "give me a location fix" flow from the ASK-pill's "Use my location"
 * button ([com.yugma.terrawatch.location.LocationAskDialog], composeApp/home) — commonMain has no
 * Activity and no Composable-scoped permission API of its own to call directly.
 *
 * Plan 4 Task 4 (d) grew this beyond a bare [request]: [currentCondition]/[shouldShowRationale]/
 * [openSettings] give onboarding step 2 and Settings' PLACE-section "Use my location" row the same
 * rationale-then-ask / permanently-denied-then-Settings-deep-link state machine Task 3 already built
 * for notifications ([com.yugma.terrawatch.notifications.NotificationPermissionRequester]) — see that
 * class's own kdoc for the shape being mirrored here.
 *
 * Unlike [LocationProvider]/[com.yugma.terrawatch.database.DriverFactory] (each actual takes its
 * own platform-specific constructor — see [LocationProvider]'s own kdoc — because each is built
 * once, directly, at its platform's entry point and handed to Koin as an already-constructed
 * `single`), this expect class declares an explicit no-arg constructor: it needs to be
 * constructible the SAME way on every target, because it's registered as an ordinary
 * `single { LocationRequester() }` inside the SHARED `AppModule.kt` — there is no platform-specific
 * construction-time state to hand in. Android's actual instead reads a small process-lifetime
 * holder MainActivity assigns to itself post-construction — the same "module-level holder
 * substitutes for a constructor parameter the shared signature can't carry" shape
 * [com.yugma.terrawatch.share.shareQuakeText]'s android actual already uses (see that file's own
 * kdoc), just applied here to a class's constructor instead of a top-level function's parameter;
 * see LocationRequester.android.kt.
 *
 * - android: launches the coarse-location runtime-permission request MainActivity registered.
 * - jvm/wasmJs: no-op — neither platform has an OS-level location permission to ask for (see
 *   [LocationProvider]'s own kdoc). [canRequestLocation] is false there, so
 *   [com.yugma.terrawatch.location.LocationAskDialog] never actually renders a button that would
 *   call this.
 */
expect class LocationRequester() {
    fun request()

    /** Resolves the current [LocationPermissionCondition] — Plan 4 Task 4 (d), mirrors
     * [com.yugma.terrawatch.notifications.NotificationPermissionRequester.currentCondition] exactly
     * (see that method's own kdoc for how the android actual disambiguates [LocationPermissionCondition
     * .DENIED] from [LocationPermissionCondition.PERMANENTLY_DENIED]). */
    fun currentCondition(): LocationPermissionCondition

    /** Whether the OS wants a rationale shown before asking again right now (Android's own
     * `shouldShowRequestPermissionRationale`) — queried at the moment onboarding step 2's/Settings'
     * "Use my location" is tapped, not folded into the steady-state [currentCondition], same
     * one-shot-question reasoning [com.yugma.terrawatch.notifications.NotificationPermissionRequester
     * .shouldShowRationale]'s own kdoc gives. */
    fun shouldShowRationale(): Boolean

    /** Deep-links to this app's own details page in the system Settings app —
     * `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` on android; a no-op elsewhere. The only recovery
     * path once [LocationPermissionCondition.PERMANENTLY_DENIED] — there is no location-specific
     * settings intent action the way notifications have `ACTION_APP_NOTIFICATION_SETTINGS`, so the
     * general app-details page (which lists this app's own Permissions row) is the standard target. */
    fun openSettings()
}

/**
 * Whether [LocationRequester.request] can plausibly do anything on this platform — true on android
 * only. [LocationAskDialog] reads this to decide whether "Use my location" renders at all; on
 * jvm/wasmJs it's always false, so that dialog offers ONLY "Choose city" there, per this task's
 * brief.
 */
expect fun canRequestLocation(): Boolean
