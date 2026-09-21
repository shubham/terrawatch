package com.yugma.terrawatch.location

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CityCatalogTest {

    @Test
    fun `the catalog holds at least a hundred cities`() {
        assertTrue(PRESET_CITIES.size >= 100, "only ${PRESET_CITIES.size} cities")
    }

    @Test
    fun `no city appears twice`() {
        val keys = PRESET_CITIES.map { "${it.name}, ${it.country}" }
        val dupes = keys.groupBy { it }.filterValues { it.size > 1 }.keys
        assertTrue(dupes.isEmpty(), "duplicates: $dupes")
    }

    @Test
    fun `every coordinate is within valid earth bounds`() {
        PRESET_CITIES.forEach { c ->
            assertTrue(c.point.lat in -90.0..90.0, "${c.name} latitude ${c.point.lat}")
            assertTrue(c.point.lon in -180.0..180.0, "${c.name} longitude ${c.point.lon}")
        }
    }

    @Test
    fun `no city sits at null island - a common placeholder mistake`() {
        val atZero = PRESET_CITIES.filter { abs(it.point.lat) < 0.01 && abs(it.point.lon) < 0.01 }
        assertTrue(atZero.isEmpty(), "cities at 0,0: ${atZero.map { it.name }}")
    }

    @Test
    fun `no city has a blank name or country`() {
        PRESET_CITIES.forEach {
            assertTrue(it.name.isNotBlank(), "blank name")
            assertTrue(it.country.isNotBlank(), "blank country for ${it.name}")
        }
    }

    @Test
    fun `known cities sit where they belong`() {
        // A spot-check against independently known coordinates, tight enough (0.5 degrees, roughly
        // 55 km of latitude) to catch a transposed or fabricated pair but loose enough to tolerate
        // differing city-centre conventions. This catches gross errors only - Task 7's independent
        // verification pass is what actually checks all of them.
        val expected = mapOf(
            "Tokyo" to (35.6762 to 139.6503),
            "Jakarta" to (-6.2088 to 106.8456),
            "Istanbul" to (41.0082 to 28.9784),
            "Santiago" to (-33.4489 to -70.6693),
            "Mexico City" to (19.4326 to -99.1332),
            "Kathmandu" to (27.7172 to 85.3240),
            "Reykjavik" to (64.1466 to -21.9426),
            "Wellington" to (-41.2866 to 174.7756),
            "Lima" to (-12.0464 to -77.0428),
            "Manila" to (14.5995 to 120.9842),
            "Taipei" to (25.0330 to 121.5654),
            "Tehran" to (35.6892 to 51.3890),
            "Naples" to (40.8518 to 14.2681),
            "Anchorage" to (61.2181 to -149.9003),
            "San Francisco" to (37.7749 to -122.4194),
        )
        expected.forEach { (name, latLon) ->
            val city = PRESET_CITIES.firstOrNull { it.name == name }
            assertTrue(city != null, "$name missing from the catalog")
            assertTrue(
                abs(city.point.lat - latLon.first) < 0.5 && abs(city.point.lon - latLon.second) < 0.5,
                "$name is at ${city.point}, expected about $latLon",
            )
        }
    }

    @Test
    fun `seismically active regions are well represented`() {
        // The catalog's whole point: an earthquake app's users cluster on plate boundaries.
        val seismicCountries = setOf(
            "Japan", "Indonesia", "Philippines", "Taiwan", "New Zealand", "Chile", "Peru",
            "Mexico", "Turkey", "Greece", "Italy", "Iran", "Nepal", "India", "Pakistan",
            "United States", "Papua New Guinea", "Iceland", "Ecuador", "Colombia", "Afghanistan",
        )
        val seismic = PRESET_CITIES.count { it.country in seismicCountries }
        assertTrue(seismic >= 60, "only $seismic cities in seismically active countries")
    }

    @Test
    fun `every inhabited continent is represented`() {
        listOf("Japan", "India", "Turkey", "Italy", "United States", "Chile", "Nigeria", "Australia")
            .forEach { country ->
                assertTrue(
                    PRESET_CITIES.any { it.country == country },
                    "no city in $country",
                )
            }
    }
}
