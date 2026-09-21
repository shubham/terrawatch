package com.yugma.terrawatch.model

/**
 * How many favourite places a user may keep, beyond home — which is not a favourite row at all and
 * is never counted here.
 *
 * This used to be a monetization gate: the free tier allowed 1 and TerraWatch Plus removed the
 * limit entirely. Plus was withdrawn before it ever sold (see
 * `docs/superpowers/specs/2026-09-21-ads-only-monetization-design.md`), so the cap is now a plain
 * product limit applying identically to everyone, and it lives in `core/model` next to
 * [FavoritePlace] rather than in a monetization module that no longer exists.
 */
const val MAX_FAVORITE_PLACES = 5

/**
 * Whether another favourite can be added right now, given [currentCount] — the caller's current
 * favourite count, never including home.
 *
 * Deliberately unaware of where the count came from or what happens when it returns `false`. The
 * caller wires the consequence: `SettingsScreen` renders its "Add place" row disabled rather than
 * routing anywhere, since the paywall this once led to no longer exists.
 */
fun canAddFavorite(currentCount: Int): Boolean = currentCount < MAX_FAVORITE_PLACES
