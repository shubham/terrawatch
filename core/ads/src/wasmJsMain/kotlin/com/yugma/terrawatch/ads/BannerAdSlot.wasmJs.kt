package com.yugma.terrawatch.ads

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Task 6 (Plan 4): same "Android-only runtime scope, nothing to render" reasoning as
// BannerAdSlot.jvm.kt's own comment — web never shows ads (spec §7's own platform table).
// Plan 5 Task 3: gained `reducedMotion` to match the `expect`'s widened signature — see
// BannerAdSlot.jvm.kt's own identical comment.
@Composable
actual fun BannerAdSlot(visible: Boolean, reducedMotion: Boolean, modifier: Modifier) {
}
