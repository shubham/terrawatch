package com.yugma.terrawatch.detail

import com.yugma.terrawatch.model.MagRevision
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [buildShareText] is the one piece of DetailSheet.kt that isn't already covered indirectly by
 * FormatsTest (it just concatenates already-tested formatters) - pinned here against the brief's
 * own literal example string so the exact wording ("M 6.1 — Mindanao, Philippines. Depth 10.0 km.
 * 2 h ago. via TerraWatch") can't silently drift.
 */
class DetailSheetTest {
    @Test
    fun `buildShareText matches the brief's exact wording`() {
        val quake = Quake(
            id = "us1234",
            timeMillis = 1_000_000L,
            lat = 7.1,
            lon = 126.5,
            depthKm = 10.0,
            mag = 6.1,
            magType = "mw",
            place = "Mindanao, Philippines",
            tsunami = false,
            felt = null,
            status = QuakeStatus.AUTOMATIC,
            sources = mapOf(Source.USGS to "us1234"),
            revisions = listOf(MagRevision(6.1, "mw", 1_000_000L, Source.USGS)),
            updatedAtMillis = 1_000_000L,
        )
        val nowMillis = 1_000_000L + 2 * 3_600_000L // exactly 2h later -> formatRelativeTime = "2 h ago"
        assertEquals(
            "M 6.1 — Mindanao, Philippines. Depth 10.0 km. 2 h ago. via TerraWatch",
            buildShareText(quake, nowMillis),
        )
    }
}
