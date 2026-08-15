package com.yugma.terrawatch.monetization

/** Free tier: home + this many favorites beyond it. Plan 5 Task 2's own dispatch: "free tier = home
 * + 1 favorite; Plus = unlimited." */
private const val FREE_TIER_FAVORITE_LIMIT = 1

/**
 * Task 2 (Plan 5), FIRST REAL PLUS GATE: whether a new favorite can be added right now, given
 * [currentCount] (the caller's current favorite count — never includes home, which isn't a favorite
 * row at all) and [isPlus] ([com.yugma.terrawatch.monetization.EntitlementsProvider.isPlusActive]'s
 * current value). Plus bypasses the limit entirely; the free tier allows [currentCount] up to (but
 * not including) [FREE_TIER_FAVORITE_LIMIT].
 *
 * Deliberately unaware of WHERE the count came from or what happens when this returns `false` — the
 * gate-blocked UI path (routing to the paywall instead of opening the city picker) is the caller's
 * job, same "pure decision, caller wires the consequence" shape [revenueCatKeyIsConfigured] already
 * establishes for this module's other gate.
 */
fun canAddFavorite(currentCount: Int, isPlus: Boolean): Boolean =
    isPlus || currentCount < FREE_TIER_FAVORITE_LIMIT
