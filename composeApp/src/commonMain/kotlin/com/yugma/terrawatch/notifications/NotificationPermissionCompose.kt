package com.yugma.terrawatch.notifications

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
 * Task 3 (Plan 4), Task 4d slice: the live, resume-aware read both the onboarding step-3 ask and
 * Settings' ALERTS row need. A plain `requester.currentCondition()` call inside a composable body
 * only ever reads once per composition — Compose has no reason to re-invoke a bare function call
 * on its own — which would leave either screen showing a stale condition after the user leaves
 * this app for the SYSTEM notification settings page (this app's own `openSettings()` deep link,
 * or a manual visit) and returns without navigating away from/back to the screen itself (an
 * `ON_RESUME` on the same still-alive Activity/composable, not a fresh navigation into it).
 *
 * [LocalLifecycleOwner] (`androidx.lifecycle.compose`, the JetBrains KMP artifact — see
 * `composeApp/build.gradle.kts`'s own dependency comment) is what makes this composable at all on
 * every target this app compiles for, not an Android-only helper — jvm/wasmJs never actually show
 * an ask affordance in practice (their own `NotificationPermissionRequester` actuals always report
 * [NotificationPermissionCondition.PRE_33], which reduces straight to `ENABLED`), but this
 * composable itself still needs to compile and behave sanely there.
 */
@Composable
fun rememberNotificationCondition(requester: NotificationPermissionRequester): NotificationPermissionCondition {
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
