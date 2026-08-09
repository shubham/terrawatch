package com.yugma.terrawatch.share

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

// Desktop has no OS-level share-sheet concept to hook into - copying to the system clipboard is
// the closest equivalent action ("share this text with whatever I paste it into next"), and
// java.awt.Toolkit's clipboard is available on every desktop JVM this project's compose.desktop
// target runs on (no extra dependency).
actual fun shareQuakeText(text: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}
