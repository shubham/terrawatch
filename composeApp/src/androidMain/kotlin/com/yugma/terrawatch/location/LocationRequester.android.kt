package com.yugma.terrawatch.location

// MainActivity assigns this to itself on EVERY onCreate() (see MainActivity.kt's onCreate), not
// only its firstLaunchThisProcess guard: a config-change recreate constructs a brand-new
// ActivityResultLauncher tied to a brand-new Activity instance, so this holder must always point at
// the CURRENT one, never a stale reference to a destroyed Activity's launcher. Same
// "process-lifetime holder substitutes for a constructor parameter the shared expect signature
// can't carry" shape as Share.android.kt's appContext/initShareContext — applied here to
// LocationRequester's no-arg constructor (needed so AppModule.kt's commonMain
// `single { LocationRequester() }` can build it uniformly across all three targets) instead of a
// top-level function's own parameter.
private var launchPermissionRequest: (() -> Unit)? = null

/** Must run before the ASK-pill's "Use my location" button can plausibly be tapped — i.e. before
 * MainActivity's setContent{} composes it — same ordering guarantee Share.android.kt's
 * initShareContext documents for the identical reason. */
fun bindLocationRequestLauncher(launch: () -> Unit) {
    launchPermissionRequest = launch
}

actual class LocationRequester {
    /** A call before [bindLocationRequestLauncher] has ever run degrades to a silent no-op rather
     * than throwing — impossible in practice (see [bindLocationRequestLauncher]'s own kdoc), but
     * matches this app's established "a missing precondition degrades quietly, never crashes"
     * convention (see [com.yugma.terrawatch.location.LocationProvider]'s android actual). */
    actual fun request() {
        launchPermissionRequest?.invoke()
    }
}

actual fun canRequestLocation(): Boolean = true
