package com.yugma.terrawatch.motion

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * `ANIMATOR_DURATION_SCALE == 0` is the same signal Android's own Accessibility "Remove
 * animations" toggle sets (it drives `ANIMATOR_DURATION_SCALE`, `TRANSITION_ANIMATION_SCALE`, and
 * `WINDOW_ANIMATION_SCALE` together) — reading just this one is the standard, minimal way apps
 * detect it; there is no dedicated "reduce motion" API on Android the way iOS/web expose one.
 */
@Composable
actual fun systemReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
