package com.yugma.terrawatch.location

import com.yugma.terrawatch.model.GeoPoint

// Browser geolocation lands in Plan 3. Always null for now.
actual class LocationProvider {
    actual suspend fun current(): GeoPoint? = null
}
