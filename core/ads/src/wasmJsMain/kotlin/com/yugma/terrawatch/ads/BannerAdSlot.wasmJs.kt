package com.yugma.terrawatch.ads

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Task 6 (Plan 4): same "Android-only runtime scope, nothing to render" reasoning as
// BannerAdSlot.jvm.kt's own comment — web never shows ads (spec §7's own platform table).
@Composable
actual fun BannerAdSlot(visible: Boolean, modifier: Modifier) {
}
