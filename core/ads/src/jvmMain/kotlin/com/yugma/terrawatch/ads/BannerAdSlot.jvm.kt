package com.yugma.terrawatch.ads

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Task 6 (Plan 4): Android-only runtime scope directive (Plan 4 Task 4) — desktop never shows ads
// (spec §7's own platform table: "Ads: Android [only], —, —"). Empty composable, not a "hidden but
// present" stub with unused parameters — there is nothing for this target to ever render here,
// matching this codebase's own jvm-actual "no-op" precedent (e.g. `LocationProvider.jvm.kt`,
// `QuakeMap.jvm.kt`'s fallback-pane delegation).
// Plan 5 Task 3: gained `reducedMotion` to match the `expect`'s widened signature (the android
// actual's new fade-in-on-first-fill animation) — still unused here, same "nothing to render"
// reasoning.
@Composable
actual fun BannerAdSlot(visible: Boolean, reducedMotion: Boolean, modifier: Modifier) {
}
