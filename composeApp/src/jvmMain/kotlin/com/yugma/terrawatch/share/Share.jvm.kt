package com.yugma.terrawatch.share

import androidx.compose.ui.graphics.ImageBitmap
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI

// Desktop has no OS-level share-sheet concept to hook into - copying to the system clipboard is
// the closest equivalent action ("share this text with whatever I paste it into next"), and
// java.awt.Toolkit's clipboard is available on every desktop JVM this project's compose.desktop
// target runs on (no extra dependency).
actual fun shareQuakeText(text: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}

// No installed-mobile-app concept exists on desktop - every quick-share button in DetailSheet's
// row simply never appears here (see Share.kt's own kdoc). Matches this project's Android-only
// runtime directive: jvm is compile-only for CI, not a real target this feature needs to serve.
actual fun isPackageInstalled(packageName: String): Boolean = false

// Unreachable in practice (isPackageInstalled always false above means DetailSheet's quick-share
// row never renders a button here to tap) - falls back to the same clipboard action
// shareQuakeText already uses, rather than silently doing nothing, in case that assumption ever
// changes.
actual fun sharePackaged(packageName: String, text: String) {
    shareQuakeText(text)
}

/** Best-effort real browser launch via [Desktop] - wrapped defensively since a headless/CI JVM
 * (this project's own jvm target is "compile-only for CI," not a real runtime surface per the
 * plan's Architecture line) may not support [Desktop.Action.BROWSE] at all. */
actual fun openUrl(url: String) {
    try {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        }
    } catch (t: Throwable) {
        // No fallback needed - see this function's own kdoc for why jvm is not a real target here.
    }
}

// Unreachable in practice (isPackageInstalled always false above means DetailSheet's quick-share
// row never renders a button here to load an icon for) - see Share.kt's own common kdoc.
actual fun appIcon(packageName: String): ImageBitmap? = null
