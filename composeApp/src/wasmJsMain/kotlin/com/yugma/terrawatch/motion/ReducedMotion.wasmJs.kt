package com.yugma.terrawatch.motion

import androidx.compose.runtime.Composable

// The browser's `prefers-reduced-motion` media query would be the real signal here, but wasmJs's
// App() doesn't even reach HomeScreen yet (web data layer is Plan 3; see QuakeMap.wasmJs.kt) — no
// wiring to a live screen exists to make reading it meaningful today. Always false for now.
@Composable
actual fun systemReducedMotion(): Boolean = false
