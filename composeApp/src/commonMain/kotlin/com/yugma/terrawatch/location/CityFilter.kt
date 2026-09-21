package com.yugma.terrawatch.location

/**
 * Filters [cities] to those matching [query], case-insensitively, against name and country. A blank
 * query returns everything. Catalog order is preserved, so results never reorder themselves as the
 * user types.
 *
 * Pure and UI-free precisely so the picker's one piece of real logic is covered by fast unit tests
 * rather than a Compose UI test.
 */
fun filterCities(query: String, cities: List<PresetCity> = PRESET_CITIES): List<PresetCity> {
    val needle = query.trim()
    if (needle.isEmpty()) return cities
    return cities.filter {
        it.name.contains(needle, ignoreCase = true) || it.country.contains(needle, ignoreCase = true)
    }
}
