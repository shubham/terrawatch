package com.yugma.terrawatch.home

import com.yugma.terrawatch.model.GeoPoint
import com.yugma.terrawatch.model.haversineKm

// The brief's own number (plan Task 1, feedback items 1+2): "when the fix differs >50km from
// saved camera target". Strictly greater-than (see startupCameraTarget's own kdoc for why the
// boundary itself falls on the "leave it alone" side).
private const val COLD_START_RECENTER_THRESHOLD_KM = 50.0

/**
 * Task 1 (Plan 5), USER REQUIREMENT (dogfooding feedback item 1, "map should open at my
 * location"): the cold-start camera-centering decision, pure and platform-free so it's directly
 * unit-testable with no Compose/maplibre-compose runtime involved — same "pure fn decoupled from
 * the Compose API" shape [layoutMode] already established (see LayoutMode.kt's own kdoc for that
 * precedent).
 *
 * Returns the point the camera should jump to at cold start, or `null` to mean "leave the camera
 * exactly as it already is" — whatever `QuakeMap`'s own `rememberCameraState`/`CameraStateSaver`
 * restore produced, or its `firstPosition` world-view default if nothing was restored (see
 * QuakeMap.android.kt's own kdoc for that mechanism). The brief's own words: "camera centers there
 * (zoom ~6) INSTEAD of last-camera-restore when the fix differs >50km from saved camera target...
 * avoid fighting deliberate pans: only auto-center on cold start, never mid-session" — this
 * function is meant to be consulted exactly ONCE, at the moment a process starts (see
 * HomeViewModel's own "ViewModel-init one-shot" wiring — [savedTarget]/[fix]/[permissionGranted]
 * are all read once there), never on a later recomposition/rotation/pan; the "never mid-session"
 * half of that guarantee is the CALLER's job (a one-shot flag), not something this pure function
 * can enforce on its own.
 *
 * - [permissionGranted] false, OR [fix] null (no location permission, or a permission with nothing
 *   cached yet — e.g. an emulator with no location provider enabled): always `null`, degrading to
 *   whatever the existing camera-restore/world-view behavior already is, exactly as if this
 *   feature didn't exist. These are kept as two SEPARATE inputs (rather than one derived
 *   `hasUsableFix: Boolean`) so this function's own test suite can pin "no permission" and "no fix"
 *   as the two independent real-world conditions they are, even though today's only caller
 *   ([com.yugma.terrawatch.home.HomeViewModel]) happens to make their OUTPUT coincide
 *   ([com.yugma.terrawatch.location.LocationProvider.current]'s own contract already returns null
 *   without permission — see its own kdoc) — a future caller isn't guaranteed to share that
 *   coincidence, and this function's contract shouldn't quietly depend on it.
 * - [savedTarget] null (nothing meaningful to compare against — e.g. a genuinely first-ever
 *   resolution, home never previously stored) and a real [fix]: always the fix — there is no
 *   "existing camera" worth preserving when there was never a real prior reference point to begin
 *   with.
 * - Both present: the fix wins ONLY when it differs from [savedTarget] by MORE than
 *   [COLD_START_RECENTER_THRESHOLD_KM] km ([haversineKm], the same great-circle distance every
 *   other distance comparison in this codebase already uses — the home-radius ring, the status
 *   pill). Strictly greater-than, not inclusive: a fix that lands exactly at the threshold, or
 *   anywhere closer, is treated as "basically the same place" and the existing camera stands — the
 *   brief's own "avoid fighting deliberate pans" concern is about NOT re-centering for a
 *   difference too small to matter, so the boundary itself falls on the "leave it alone" side, not
 *   the "move it" side.
 */
fun startupCameraTarget(savedTarget: GeoPoint?, fix: GeoPoint?, permissionGranted: Boolean): GeoPoint? {
    if (!permissionGranted || fix == null) return null
    if (savedTarget == null) return fix
    return if (haversineKm(savedTarget, fix) > COLD_START_RECENTER_THRESHOLD_KM) fix else null
}
