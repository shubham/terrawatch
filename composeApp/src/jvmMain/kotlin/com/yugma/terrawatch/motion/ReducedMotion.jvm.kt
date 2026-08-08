package com.yugma.terrawatch.motion

import androidx.compose.runtime.Composable

// Desktop JVM has no cross-platform-visible "reduce motion" system signal to read (and this
// project's desktop target is a static placeholder pane pending Task 12 regardless — see
// QuakeMap.jvm.kt) — always false, matching this project's Android-only-live-motion scope today.
@Composable
actual fun systemReducedMotion(): Boolean = false
