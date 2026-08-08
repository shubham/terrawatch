package com.yugma.terrawatch.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether signature motion (the map's pin-drop pop + expanding rings, the feed sheet's pulsing
 * LIVE dot) should be skipped. Defaults to false (motion on) wherever a system-level "reduce
 * motion" signal can't be read. [com.yugma.terrawatch.App] provides the platform's real value via
 * [systemReducedMotion] at the composition root, so any screen can just read
 * `LocalReducedMotion.current` instead of re-deriving it — and tests/previews can still override
 * this manually via `CompositionLocalProvider` regardless of what the platform reports.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * Best-effort read of an OS-level "reduce motion" signal.
 * - Android: the Accessibility "Remove animations" toggle (`ANIMATOR_DURATION_SCALE == 0`).
 * - jvm/wasmJs: neither platform surfaces an equivalent signal today — always false.
 */
@Composable
expect fun systemReducedMotion(): Boolean
