package com.yugma.terrawatch.location

// Desktop has no OS-level location permission to request — see LocationProvider.jvm.kt.
// LocationAskDialog offers ONLY "Choose city" here (canRequestLocation() below), so nothing ever
// calls request() in practice, but the expect/actual contract — and AppModule's uniform
// `single { LocationRequester() }` — still need a real, constructible actual on every target.
actual class LocationRequester {
    actual fun request() {}
}

actual fun canRequestLocation(): Boolean = false
