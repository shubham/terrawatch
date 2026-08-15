package com.yugma.terrawatch.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Plan 4 Task 4 (d): the live, resume-aware read both onboarding step 2 and Settings' PLACE-section
 * "Use my location" row need — mirrors [com.yugma.terrawatch.notifications.rememberNotificationCondition]
 * exactly (see that function's own kdoc for why a plain `requester.currentCondition()` body-level
 * call alone would leave either screen showing a stale condition after the user leaves this app for
 * the SYSTEM location-permission page — this app's own [LocationRequester.openSettings] deep link, or
 * a manual visit — and returns without navigating away from/back to the screen itself).
 */
@Composable
fun rememberLocationCondition(requester: LocationRequester): LocationPermissionCondition {
    var condition by remember { mutableStateOf(requester.currentCondition()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, requester) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) condition = requester.currentCondition()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return condition
}
