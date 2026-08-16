package com.yugma.terrawatch.share

import androidx.compose.ui.graphics.ImageBitmap

// TODO(Plan 3): wire the Web Share API (`navigator.share`) where available, falling back to
// `navigator.clipboard.writeText`. No-op for now: wasmJs's App() doesn't reach a live screen yet
// (web's data layer is Plan 3 - see QuakeMap.wasmJs.kt/App.kt's WebPlaceholder), so there is no
// real call site to exercise this against today, same reasoning as
// [com.yugma.terrawatch.motion.systemReducedMotion]'s wasmJs actual.
actual fun shareQuakeText(text: String) {
}

// No installed-mobile-app concept in a browser - every quick-share button in DetailSheet's row
// simply never appears here, same reasoning as shareQuakeText's own no-op above.
actual fun isPackageInstalled(packageName: String): Boolean = false

// Unreachable in practice (isPackageInstalled always false above) - see shareQuakeText's own kdoc.
actual fun sharePackaged(packageName: String, text: String) {
}

// TODO(Plan 3): wire `window.open(url, "_blank")` once wasmJs reaches a live screen. No-op for
// now, same reasoning as shareQuakeText's own no-op above.
actual fun openUrl(url: String) {
}

// Unreachable in practice (isPackageInstalled always false above) - see Share.kt's own common kdoc.
actual fun appIcon(packageName: String): ImageBitmap? = null
