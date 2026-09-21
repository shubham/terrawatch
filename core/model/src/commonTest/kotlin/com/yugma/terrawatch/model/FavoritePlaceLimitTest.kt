package com.yugma.terrawatch.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FavoritePlaceLimitTest {

    @Test
    fun `the cap is five`() {
        assertEquals(5, MAX_FAVORITE_PLACES)
    }

    @Test
    fun `adding is allowed below the cap`() {
        assertTrue(canAddFavorite(currentCount = 0))
        assertTrue(canAddFavorite(currentCount = 1))
        assertTrue(canAddFavorite(currentCount = 4))
    }

    @Test
    fun `adding is blocked at and above the cap`() {
        assertFalse(canAddFavorite(currentCount = 5))
        assertFalse(canAddFavorite(currentCount = 6))
    }

    @Test
    fun `the cap applies identically to every user - there is no tier parameter`() {
        // Regression guard for the removed Plus tier: this function must stay single-argument.
        // If someone reintroduces an entitlement parameter, this file stops compiling, which is
        // the intended alarm.
        val allowed: (Int) -> Boolean = ::canAddFavorite
        assertTrue(allowed(0))
        assertFalse(allowed(5))
    }
}
