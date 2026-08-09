package com.yugma.terrawatch.share

// TODO(Plan 3): wire the Web Share API (`navigator.share`) where available, falling back to
// `navigator.clipboard.writeText`. No-op for now: wasmJs's App() doesn't reach a live screen yet
// (web's data layer is Plan 3 - see QuakeMap.wasmJs.kt/App.kt's WebPlaceholder), so there is no
// real call site to exercise this against today, same reasoning as
// [com.yugma.terrawatch.motion.systemReducedMotion]'s wasmJs actual.
actual fun shareQuakeText(text: String) {
}
