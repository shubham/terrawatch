package com.yugma.terrawatch.monetization

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task 2 (Plan 5), TDD, FIRST REAL PLUS GATE: [canAddFavorite] is the pure decision every
 * "add a favorite place" call site (Settings' "Add place" row) consults before opening the picker —
 * same "one pure fn, TDD'd here, no Compose/RevenueCat dependency" shape [revenueCatKeyIsConfigured]
 * already establishes in this same module (see that function's own kdoc).
 *
 * Free tier: home + 1 favorite ([FREE_TIER_FAVORITE_LIMIT]). Plus: unlimited. [currentCount] is the
 * caller's CURRENT favorite count (home itself is never part of this count — home isn't a row in
 * `favoritePlace` at all, see `FavoritePlace.sq`'s own kdoc), so `currentCount = 0` is "the free
 * tier's one allowed favorite is still available," not "already at the limit."
 */
class FavoritePlaceGateTest {
    @Test fun `free tier can add the first favorite`() {
        assertTrue(canAddFavorite(currentCount = 0, isPlus = false))
    }

    @Test fun `free tier cannot add a second favorite`() {
        assertFalse(canAddFavorite(currentCount = 1, isPlus = false))
    }

    @Test fun `free tier cannot add further favorites once already over the limit`() {
        assertFalse(canAddFavorite(currentCount = 5, isPlus = false))
    }

    @Test fun `plus can add the first favorite`() {
        assertTrue(canAddFavorite(currentCount = 0, isPlus = true))
    }

    @Test fun `plus can add well beyond the free-tier limit -- unlimited`() {
        assertTrue(canAddFavorite(currentCount = 1, isPlus = true))
        assertTrue(canAddFavorite(currentCount = 50, isPlus = true))
    }
}
