package com.yugma.terrawatch.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Task 2 (Plan 5): [FavoriteAlertType.fromStored] is the safe-parse the plan's own dispatch calls
 * for ("unknown → ALL") — the enum is persisted as a plain TEXT column
 * ([com.yugma.terrawatch.database.QuakeDao]'s own `favorite_place.alertType`), so a hand-edited row,
 * a value written by a future app version with a since-removed enum entry, or any other corrupt/
 * unrecognized string must degrade quietly to the least-surprising default (ALL — the same
 * "everything, no extra filtering" semantics a brand-new favorite gets) rather than throwing, same
 * posture [com.yugma.terrawatch.database.bandFromLabel]/[AlertRuleStore]'s corrupt-value fallbacks
 * already take elsewhere in this codebase.
 */
class FavoritePlaceTest {
    @Test fun `fromStored parses each valid name`() {
        assertEquals(FavoriteAlertType.ALL, FavoriteAlertType.fromStored("ALL"))
        assertEquals(FavoriteAlertType.MAJOR_ONLY, FavoriteAlertType.fromStored("MAJOR_ONLY"))
        assertEquals(FavoriteAlertType.OFF, FavoriteAlertType.fromStored("OFF"))
    }

    @Test fun `fromStored on null degrades to ALL`() {
        assertEquals(FavoriteAlertType.ALL, FavoriteAlertType.fromStored(null))
    }

    @Test fun `fromStored on an unrecognized string degrades to ALL`() {
        assertEquals(FavoriteAlertType.ALL, FavoriteAlertType.fromStored("SOMETHING_FUTURE"))
    }

    @Test fun `fromStored is case-sensitive -- a lowercase variant is unrecognized, degrades to ALL`() {
        assertEquals(FavoriteAlertType.ALL, FavoriteAlertType.fromStored("all"))
    }

    @Test fun `FavoritePlace carries its id, label, point and alertType verbatim`() {
        val place = FavoritePlace(id = 7L, label = "Tokyo", point = GeoPoint(35.6762, 139.6503), alertType = FavoriteAlertType.MAJOR_ONLY)
        assertEquals(7L, place.id)
        assertEquals("Tokyo", place.label)
        assertEquals(GeoPoint(35.6762, 139.6503), place.point)
        assertEquals(FavoriteAlertType.MAJOR_ONLY, place.alertType)
    }
}
