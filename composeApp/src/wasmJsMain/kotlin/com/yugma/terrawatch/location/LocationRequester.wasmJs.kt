package com.yugma.terrawatch.location

// Browser geolocation lands later — see LocationProvider.wasmJs.kt. LocationAskDialog offers ONLY
// "Choose city" here (canRequestLocation() below); same "still need a real actual" note as
// LocationRequester.jvm.kt.
actual class LocationRequester {
    actual fun request() {}
}

actual fun canRequestLocation(): Boolean = false
