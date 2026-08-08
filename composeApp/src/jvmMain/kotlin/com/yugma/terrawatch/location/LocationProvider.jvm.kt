package com.yugma.terrawatch.location

import com.yugma.terrawatch.model.GeoPoint

// Desktop has no OS-level "coarse location" API wired for this task — the intended path is a
// manual city picker in the Plan 3 settings UI. Always null for now.
actual class LocationProvider {
    actual suspend fun current(): GeoPoint? = null
}
