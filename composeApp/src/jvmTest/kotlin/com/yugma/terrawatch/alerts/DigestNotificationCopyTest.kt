package com.yugma.terrawatch.alerts

import com.yugma.terrawatch.data.AlertEvent
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun quake(mag: Double? = 6.2, place: String = "12 km NE of Somewhere", timeMillis: Long = 0L) =
    Quake("us1", timeMillis, 7.1, 126.5, 10.0, mag, "mb", place, false, null,
        QuakeStatus.AUTOMATIC, mapOf(Source.USGS to "us1"), emptyList(), timeMillis)

/**
 * Task 3 (Plan 4), digest honesty (spec §6.5): TDD for the notification copy `AlertDigestWorker`
 * (androidMain) builds its [NotificationCompat][androidx.core.app.NotificationCompat] text from.
 * Pulled into commonMain (composeApp, not core:data — see this file's own package) purely because
 * these need `core:ui`'s [com.yugma.terrawatch.ui.format.formatMagnitude]/[com.yugma.terrawatch.
 * ui.format.formatRelativeTime], and `core:data` has no dependency on `core:ui` (checked
 * core/data/build.gradle.kts — grepped, not assumed).
 *
 * The dispatch's own example copy ("M6.2 near you · 2h ago") is illustrative of the FRAMING
 * (magnitude + location honesty + relative time), not a byte-for-byte string to reproduce —
 * unlike `OnboardingScreen.defaultRuleSummary`'s controller-quoted string, nothing here marks this
 * particular phrasing as a verbatim quote. [formatRelativeTime] is reused as-is (its own
 * already-TDD'd "X h ago" spacing, not a second hand-rolled variant) for consistency with every
 * other "how long ago" label already shipping elsewhere in this app (DetailSheet's revision badge,
 * QuakeCard).
 */
class DigestNotificationCopyTest {
    private val nowMillis = 10_800_000L // exactly 3h after epoch 0

    // --- digestNotificationTitle -----------------------------------------------------------------

    @Test fun `near-rule title reads magnitude, near you, and relative time`() {
        val event = AlertEvent(quake(mag = 6.2, timeMillis = nowMillis - 7_200_000L), "near")
        assertEquals("M6.2 near you · 2 h ago", digestNotificationTitle(event, nowMillis))
    }

    @Test fun `world-rule title never claims 'near you' -- honesty for an unbounded-radius match`() {
        val event = AlertEvent(quake(mag = 6.5, place = "128 km SW of Tokyo, Japan", timeMillis = nowMillis - 7_200_000L), "world")
        val title = digestNotificationTitle(event, nowMillis)
        assertEquals("M6.5 · 128 km SW of Tokyo, Japan · 2 h ago", title)
        assertTrue("near you" !in title)
    }

    @Test fun `title formats magnitude to one decimal via formatMagnitude`() {
        val event = AlertEvent(quake(mag = 4.5, timeMillis = nowMillis), "near")
        assertTrue(digestNotificationTitle(event, nowMillis).startsWith("M4.5"))
    }

    @Test fun `title on a null-magnitude quake still renders (em-dash placeholder), never crashes`() {
        val event = AlertEvent(quake(mag = null, timeMillis = nowMillis), "near")
        assertEquals("M— near you · just now", digestNotificationTitle(event, nowMillis))
    }

    // --- digestNotificationBody -------------------------------------------------------------------

    @Test fun `near-rule body is the quake's place`() {
        val event = AlertEvent(quake(place = "5 km N of Home"), "near")
        assertEquals("5 km N of Home", digestNotificationBody(event))
    }

    @Test fun `world-rule body names the worldwide rule, not just the place (title already has place)`() {
        val event = AlertEvent(quake(place = "128 km SW of Tokyo, Japan"), "world")
        val body = digestNotificationBody(event)
        assertTrue(body.contains("128 km SW of Tokyo, Japan"))
        assertTrue(body.contains("worldwide", ignoreCase = true))
    }

    // --- summaryNotificationText -------------------------------------------------------------------

    @Test fun `summary text for a single extra uses singular wording`() {
        assertEquals("1 more earthquake matched your alerts", summaryNotificationText(1))
    }

    @Test fun `summary text for multiple extras uses plural wording`() {
        assertEquals("5 more earthquakes matched your alerts", summaryNotificationText(5))
    }
}
