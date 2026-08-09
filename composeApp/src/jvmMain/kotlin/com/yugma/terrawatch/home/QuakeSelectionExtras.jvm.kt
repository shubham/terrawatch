package com.yugma.terrawatch.home

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.CreationExtras

// See QuakeSelectionExtras.kt's kdoc. Unchanged/unverified by Task 9, deliberately: jvm/desktop's
// interactive run (Window { App() } actually launched, not just compiled) is explicitly out of
// this plan's scope (plan's own self-review: "desktop live map consciously NOT in Plan 3 — JDK
// toolchain unresolved; stays Plan 4/later"). Whether Compose Multiplatform's desktop `Window {}`
// provides a working SavedStateRegistryOwner the way Android's ComponentActivity does is UNKNOWN —
// not fixed or investigated here, since this task's brief is web, not desktop. Flagged honestly as
// a concern in task-9-report.md rather than silently assumed fine.
@Composable
actual fun rememberQuakeSelectionExtras(): CreationExtras? = null
