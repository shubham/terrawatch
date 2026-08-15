package com.yugma.terrawatch.detail

import com.yugma.terrawatch.model.MagRevision
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [buildShareText] is the one piece of DetailSheet.kt that isn't already covered indirectly by
 * FormatsTest (it just concatenates already-tested formatters) - pinned here against the
 * *documented, shipped* format ("M 6.1 — Mindanao, Philippines. Depth 10.0 km. 2 h ago. via
 * TerraWatch") so it can't silently drift.
 *
 * Fix Round 1 (review finding): this test's name and kdoc used to claim that string was "the
 * brief's exact wording" - it wasn't. The brief's own dictated example was "M 6.1 — Mindanao,
 * Philippines. Depth 10 km. via TerraWatch", with no relative-time clause at all; the shipped
 * format deliberately adds one (see [buildShareText]'s own kdoc for why). Renamed and reworded so
 * this test documents what the format actually is, instead of misattributing that enrichment to
 * the brief.
 */
class DetailSheetTest {
    @Test
    fun `buildShareText produces the documented format`() {
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

/**
 * Plan 4 Task 4b: the pure half of the quick-share row - [ShareTarget]'s own fixed package list,
 * and [visibleShareTargets]'s installed-app filter (see that function's own kdoc for why it takes
 * a plain `(String) -> Boolean` rather than calling the real platform check directly).
 */
class ShareTargetTest {
    @Test fun `pins the 3 target packages exactly as specified`() {
        assertEquals(
            listOf("com.whatsapp", "com.twitter.android", "com.instagram.barcelona"),
            ShareTarget.entries.map { it.packageName },
        )
    }

    @Test fun `visibleShareTargets keeps every target when all are installed`() {
        assertEquals(ShareTarget.entries.toList(), visibleShareTargets { true })
    }

    @Test fun `visibleShareTargets returns nothing when none are installed`() {
        assertEquals(emptyList(), visibleShareTargets { false })
    }

    @Test fun `visibleShareTargets omits an absent app entirely rather than flagging it`() {
        val installed = setOf(ShareTarget.WHATSAPP.packageName, ShareTarget.THREADS.packageName)
        assertEquals(
            listOf(ShareTarget.WHATSAPP, ShareTarget.THREADS),
            visibleShareTargets { pkg -> pkg in installed },
        )
    }

    @Test fun `visibleShareTargets preserves ShareTarget's own declared order`() {
        // Only X installed - the filtered result is a strict subsequence of ShareTarget.entries,
        // never re-ordered by which predicate happened to match.
        assertEquals(listOf(ShareTarget.X), visibleShareTargets { it == ShareTarget.X.packageName })
    }
}
