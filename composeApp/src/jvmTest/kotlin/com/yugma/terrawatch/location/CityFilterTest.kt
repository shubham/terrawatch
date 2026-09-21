package com.yugma.terrawatch.location

import com.yugma.terrawatch.model.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CityFilterTest {

    private val cities = listOf(
        PresetCity("Tokyo", "Japan", GeoPoint(35.6762, 139.6503)),
        PresetCity("Osaka", "Japan", GeoPoint(34.6937, 135.5023)),
        PresetCity("Jakarta", "Indonesia", GeoPoint(-6.2088, 106.8456)),
        PresetCity("San Francisco", "United States", GeoPoint(37.7749, -122.4194)),
    )

    @Test
    fun `a blank query returns everything`() {
        assertEquals(cities, filterCities("", cities))
        assertEquals(cities, filterCities("   ", cities))
    }

    @Test
    fun `matches on city name, case insensitively`() {
        assertEquals(listOf("Tokyo"), filterCities("tok", cities).map { it.name })
        assertEquals(listOf("Tokyo"), filterCities("TOKYO", cities).map { it.name })
    }

    @Test
    fun `matches on country, so a user can find their whole country`() {
        assertEquals(listOf("Tokyo", "Osaka"), filterCities("japan", cities).map { it.name })
    }

    @Test
    fun `matches mid-word, not only at the start`() {
        assertEquals(listOf("San Francisco"), filterCities("fran", cities).map { it.name })
    }

    @Test
    fun `surrounding whitespace in the query is ignored`() {
        assertEquals(listOf("Tokyo"), filterCities("  tokyo  ", cities).map { it.name })
    }

    @Test
    fun `a query matching nothing returns empty rather than everything`() {
        assertTrue(filterCities("atlantis", cities).isEmpty())
    }

    @Test
    fun `result order follows the catalog, so results do not jump around`() {
        assertEquals(listOf("Tokyo", "Osaka"), filterCities("a", cities).map { it.name }.take(2))
    }
}
