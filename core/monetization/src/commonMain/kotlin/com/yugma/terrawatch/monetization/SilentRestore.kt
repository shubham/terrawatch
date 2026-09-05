package com.yugma.terrawatch.monetization

/**
 * One quiet restore attempt on a cold start, so the common reinstall case recovers Plus without the
 * user ever needing to discover the "Restore purchases" button.
 *
 * Why this exists at all: a Play one-time purchase belongs to the user's Google account, but
 * RevenueCat's anonymous App User ID lives in app data and dies with an uninstall. Android Auto
 * Backup usually carries that id across a reinstall — usually, not always, since backups run
 * periodically and can be off entirely. This closes the gap for everyone it can, silently, and the
 * paywall's manual Restore button covers whoever is left.
 *
 * **Silent in BOTH directions, deliberately.** Success reports nothing: the entitlement StateFlow
 * updating is the only visible effect, which is exactly right — the ads simply never appear.
 * Failure reports nothing either, because a cold start is the worst possible moment to interrupt
 * someone with a store error they did not ask for, and a failed attempt costs them nothing they can
 * act on. [runCatching] also means a throwing implementation cannot take the launch down with it;
 * the caller is fire-and-forget, where an escaping exception is a crash rather than a log line.
 *
 * Skipped entirely when Plus is already active — there is nothing to recover, and calling restore
 * regardless would be a store round-trip on every single launch.
 *
 * @return whether an attempt was actually made. For tests, and for callers that want to avoid
 *   retrying within one session. Never throws.
 */
suspend fun attemptSilentRestore(
    purchases: PlusPurchases,
    isPlusAlreadyActive: Boolean,
): Boolean {
    if (isPlusAlreadyActive) return false
    runCatching { purchases.restore() }
    return true
}
