package com.yugma.terrawatch.location

/**
 * Triggers the platform's "give me a location fix" flow from the ASK-pill's "Use my location"
 * button ([com.yugma.terrawatch.location.LocationAskDialog], composeApp/home) — commonMain has no
 * Activity and no Composable-scoped permission API of its own to call directly.
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
}

/**
 * Whether [LocationRequester.request] can plausibly do anything on this platform — true on android
 * only. [LocationAskDialog] reads this to decide whether "Use my location" renders at all; on
 * jvm/wasmJs it's always false, so that dialog offers ONLY "Choose city" there, per this task's
 * brief.
 */
expect fun canRequestLocation(): Boolean
