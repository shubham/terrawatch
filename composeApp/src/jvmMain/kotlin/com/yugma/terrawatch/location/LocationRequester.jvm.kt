package com.yugma.terrawatch.location

// Desktop has no OS-level location permission to request — see LocationProvider.jvm.kt.
// LocationAskDialog offers ONLY "Choose city" here (canRequestLocation() below), so nothing ever
// calls request()/currentCondition()/shouldShowRationale()/openSettings() in practice, but the
// expect/actual contract — and AppModule's uniform `single { LocationRequester() }` — still need a
// real, constructible actual on every target.
actual class LocationRequester {
    actual fun request() {}

    actual fun currentCondition(): LocationPermissionCondition = LocationPermissionCondition.NOT_APPLICABLE

    actual fun shouldShowRationale(): Boolean = false

    actual fun openSettings() {}
}

actual fun canRequestLocation(): Boolean = false
