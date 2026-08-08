package com.yugma.terrawatch.location

import com.yugma.terrawatch.model.GeoPoint

/**
 * Best-effort, one-shot device location. No constructor is declared here on purpose — each actual
 * is built at its platform's entry point (MainActivity for android, `main()` for jvm/wasmJs; see
 * [com.yugma.terrawatch.database.DriverFactory] for the identical established pattern in this
 * codebase) and handed to Koin as an already-constructed `single`, so commonMain never needs to
 * name a constructor signature that would otherwise have to match across every actual.
 *
 * - android: reads the last known fix from [android.location.LocationManager] when
 *   `ACCESS_COARSE_LOCATION` is already granted; null otherwise. Requesting the runtime permission
 *   itself is Activity-level plumbing (MainActivity), not this class's job.
 * - jvm: always null for now — desktop's path is a manual city picker (Plan 3 settings UI).
 * - wasmJs: always null for now — browser geolocation lands in Plan 3.
 */
expect class LocationProvider {
    suspend fun current(): GeoPoint?
}
