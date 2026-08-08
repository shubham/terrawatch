package com.yugma.terrawatch.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import com.yugma.terrawatch.model.GeoPoint

/**
 * Reads the device's last-known coarse fix via the plain [LocationManager] API — deliberately no
 * play-services dependency for this task (see Task 7 brief). Requesting the runtime permission is
 * NOT this class's job: MainActivity owns the `ActivityResultContracts.RequestPermission()` ask on
 * first launch, entirely separate from this read-only, best-effort probe.
 */
actual class LocationProvider(private val context: Context) {
    actual suspend fun current(): GeoPoint? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        // Best-effort only: no active location request, just whatever fix each provider already
        // has cached. Try network first (usually fresher/faster, works indoors), then GPS. Either
        // provider can legitimately be absent (many emulator images ship no network provider at
        // all) or throw for reasons unrelated to our already-confirmed permission grant, so each
        // probe is swallowed independently — a missing/misbehaving provider degrades to null for
        // that provider rather than crashing the caller.
        for (provider in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
            val location = runCatching {
                if (manager.isProviderEnabled(provider)) manager.getLastKnownLocation(provider) else null
            }.getOrNull()
            if (location != null) return GeoPoint(location.latitude, location.longitude)
        }
        return null
    }
}
