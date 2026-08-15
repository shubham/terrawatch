package com.yugma.terrawatch.notifications

/**
 * The four raw conditions [NotificationPermissionRequester.currentCondition] can resolve to —
 * Task 3 (Plan 4), Task 4d slice's own dispatch names exactly these four. Resolving WHICH one
 * currently holds is the impure, platform-specific half (Android's `ContextCompat.
 * checkSelfPermission` + `ActivityCompat.shouldShowRequestPermissionRationale` + a persisted
 * "have we asked before" flag to fully disambiguate [DENIED] from [PERMANENTLY_DENIED] — see
 * [NotificationPermissionRequester]'s android actual for that) — this enum and
 * [reduceNotificationPermissionState] only model the pure mapping from an already-resolved
 * condition to what the UI should show.
 */
enum class NotificationPermissionCondition {
    /** POST_NOTIFICATIONS is currently granted. */
    GRANTED,

    /** Not granted, but the OS will still show the system ask dialog again (either never asked
     * before, or asked once and denied without "don't ask again"). */
    DENIED,

    /** Not granted, and the OS will NOT show the system ask dialog again — only a Settings
     * deep-link can recover this. */
    PERMANENTLY_DENIED,

    /** API < 33: POST_NOTIFICATIONS doesn't exist as a runtime permission at all; notifications
     * were always allowed. Also what jvm/wasmJs's `NotificationPermissionRequester` actual always
     * reports, for the identical reason (see that file's own kdoc) — neither platform has any
     * such concept, so "no gate at all" is the accurate answer everywhere this isn't Android 13+. */
    PRE_33,
}

/** What the alerts UI (onboarding step 3's button, Settings' ALERTS row) should show for a given
 * [NotificationPermissionCondition]. */
enum class NotificationAlertsUiState {
    /** Alerts can fire — render as "on"/"enabled", no action needed. */
    ENABLED,

    /** Not enabled, but an in-app ask can still work — render an "Enable alerts" affordance. */
    CAN_ASK,

    /** Not enabled, and only the system Settings screen can fix it — render an explainer plus a
     * Settings deep-link, never a re-ask button the OS would silently no-op. */
    NEEDS_SETTINGS,
}

/**
 * The pure reducer Task 3's dispatch calls for. [GRANTED] and [PRE_33] both mean "the user will
 * actually receive alerts" — collapsing to the same [NotificationAlertsUiState.ENABLED] is
 * deliberate: the UI has no reason to distinguish "really granted" from "nothing to grant" when
 * both produce the identical lived experience (digests just work).
 */
fun reduceNotificationPermissionState(condition: NotificationPermissionCondition): NotificationAlertsUiState =
    when (condition) {
        NotificationPermissionCondition.GRANTED, NotificationPermissionCondition.PRE_33 -> NotificationAlertsUiState.ENABLED
        NotificationPermissionCondition.DENIED -> NotificationAlertsUiState.CAN_ASK
        NotificationPermissionCondition.PERMANENTLY_DENIED -> NotificationAlertsUiState.NEEDS_SETTINGS
    }

/**
 * Fix Round 1 (I4, review finding): folds the two SYSTEM-level "notifications are actually
 * silenced" signals — `NotificationManagerCompat.areNotificationsEnabled()` (the app-level toggle)
 * and the digest channel's own importance (the per-channel toggle) — into whatever the OS
 * PERMISSION-only [rawCondition] resolved to. A POST_NOTIFICATIONS grant (or [NotificationPermissionCondition.
 * PRE_33]'s "nothing to gate at all") is necessary but not sufficient for a digest to actually
 * reach the user: `checkSelfPermission` only ever answers the permission question, never "will
 * this notification actually show" — a user can leave the runtime permission granted while
 * disabling the app's notifications wholesale, or muting just this one channel, via system
 * Settings, entirely independent of the permission dialog.
 *
 * [NotificationPermissionCondition.GRANTED]/[NotificationPermissionCondition.PRE_33] downgrade to
 * [NotificationPermissionCondition.PERMANENTLY_DENIED] whenever EITHER silencing signal is true —
 * re-using that condition rather than inventing a fifth, because its own UI meaning ("not enabled,
 * and only a Settings visit can fix it — an in-app re-ask would be a silent no-op") is EXACTLY
 * right here too: re-showing the OS permission dialog cannot un-silence an app-level or
 * channel-level toggle, only Settings can. [NotificationPermissionCondition.DENIED]/
 * [NotificationPermissionCondition.PERMANENTLY_DENIED] pass through unchanged — a permission that
 * isn't even granted yet is already the row's real blocker, independent of a channel/app toggle
 * this function has no need to consult on top of that.
 *
 * Also closes a second, narrower gap: the ORIGINAL [NotificationPermissionCondition.PRE_33] path
 * (`Build.VERSION.SDK_INT < TIRAMISU`) used to return unconditionally, before ever consulting
 * [android.app.NotificationManager] at all — meaning a pre-33 device with notifications disabled
 * app-wide via system Settings (the ONLY control such a device has, since POST_NOTIFICATIONS
 * doesn't exist as a runtime concept there) still read as "enabled" here. Folding [PRE_33] through
 * the exact same check as [GRANTED] above closes that.
 */
fun foldSystemNotificationState(
    rawCondition: NotificationPermissionCondition,
    notificationsEnabledAtSystemLevel: Boolean,
    channelBlocked: Boolean,
): NotificationPermissionCondition = when (rawCondition) {
    NotificationPermissionCondition.GRANTED, NotificationPermissionCondition.PRE_33 ->
        if (!notificationsEnabledAtSystemLevel || channelBlocked) {
            NotificationPermissionCondition.PERMANENTLY_DENIED
        } else {
            rawCondition
        }
    NotificationPermissionCondition.DENIED, NotificationPermissionCondition.PERMANENTLY_DENIED -> rawCondition
}
