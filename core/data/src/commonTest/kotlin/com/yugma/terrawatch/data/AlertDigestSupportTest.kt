package com.yugma.terrawatch.data

import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import kotlin.test.Test
import kotlin.test.assertEquals

private fun q(id: String, mag: Double? = 5.0, timeMillis: Long = 900) =
    Quake(id, timeMillis, 7.1, 126.5, 10.0, mag, "mb", "Somewhere", false, null,
        QuakeStatus.AUTOMATIC, mapOf(Source.USGS to id), emptyList(), timeMillis)

private fun event(id: String, mag: Double? = 5.0, timeMillis: Long = 900, ruleId: String = "near") =
    AlertEvent(q(id, mag, timeMillis), ruleId)

/**
 * Task 3 (Plan 4): TDD for `AlertDigestWorker`'s (androidMain) two pure pieces — pulled into
 * `core:data` (not composeApp/androidMain) so both are testable without any Android/WorkManager
 * dependency, same "thin platform wiring over a tested common core" split this codebase's other
 * androidMain-only callers (QuakeRepository's own suspend pass-throughs, etc.) already establish.
 */
class AlertDigestSupportTest {
    // --- parseNotifiedIds ---------------------------------------------------------------------

    @Test fun `parseNotifiedIds on null returns empty`() {
        assertEquals(emptyList(), parseNotifiedIds(null))
    }

    @Test fun `parseNotifiedIds on blank string returns empty`() {
        assertEquals(emptyList(), parseNotifiedIds(""))
    }

    @Test fun `parseNotifiedIds splits a single id`() {
        assertEquals(listOf("us1"), parseNotifiedIds("us1"))
    }

    @Test fun `parseNotifiedIds splits multiple ids preserving order`() {
        assertEquals(listOf("us1", "us2", "us3"), parseNotifiedIds("us1,us2,us3"))
    }

    @Test fun `parseNotifiedIds tolerates stray whitespace around commas`() {
        assertEquals(listOf("us1", "us2"), parseNotifiedIds(" us1 , us2 "))
    }

    // --- appendNotifiedIds ---------------------------------------------------------------------

    @Test fun `appendNotifiedIds on null existing returns just the new ids`() {
        assertEquals("us1,us2", appendNotifiedIds(null, listOf("us1", "us2")))
    }

    @Test fun `appendNotifiedIds appends new ids after existing ones`() {
        assertEquals("us1,us2,us3", appendNotifiedIds("us1", listOf("us2", "us3")))
    }

    @Test fun `appendNotifiedIds with no new ids returns existing unchanged`() {
        assertEquals("us1,us2", appendNotifiedIds("us1,us2", emptyList()))
    }

    @Test fun `appendNotifiedIds dedupes an id appearing in both existing and new`() {
        assertEquals("us1,us2", appendNotifiedIds("us1", listOf("us1", "us2")))
    }

    @Test fun `appendNotifiedIds under the cap keeps everything`() {
        assertEquals("us1,us2,us3", appendNotifiedIds("us1,us2", listOf("us3"), cap = 100))
    }

    @Test fun `appendNotifiedIds exactly at the cap trims nothing`() {
        val existing = (1..5).joinToString(",") { "us$it" }
        assertEquals(existing, appendNotifiedIds(existing, emptyList(), cap = 5))
    }

    @Test fun `appendNotifiedIds over the cap drops the OLDEST entries first -- ring buffer`() {
        // existing us1..us5 at cap 5; adding us6 must drop us1 (the oldest), keeping the newest 5.
        val existing = (1..5).joinToString(",") { "us$it" }
        assertEquals("us2,us3,us4,us5,us6", appendNotifiedIds(existing, listOf("us6"), cap = 5))
    }

    @Test fun `appendNotifiedIds over the cap by several drops that many oldest entries`() {
        val existing = (1..5).joinToString(",") { "us$it" }
        assertEquals("us4,us5,us6,us7,us8", appendNotifiedIds(existing, listOf("us6", "us7", "us8"), cap = 5))
    }

    @Test fun `appendNotifiedIds default cap is 100`() {
        val existing = (1..100).joinToString(",") { "us$it" }
        val result = appendNotifiedIds(existing, listOf("us101"))
        assertEquals(100, result.split(",").size)
        assertEquals("us2", result.split(",").first()) // us1 fell off the front
        assertEquals("us101", result.split(",").last())
    }

    // --- planDigestNotifications -----------------------------------------------------------------

    @Test fun `planDigestNotifications on no events plans nothing`() {
        val plan = planDigestNotifications(emptyList())
        assertEquals(emptyList(), plan.individual)
        assertEquals(0, plan.summaryExtraCount)
    }

    @Test fun `planDigestNotifications under the individual cap shows every event, no summary`() {
        val events = listOf(event("us1"), event("us2"))
        val plan = planDigestNotifications(events)
        assertEquals(events, plan.individual)
        assertEquals(0, plan.summaryExtraCount)
    }

    @Test fun `planDigestNotifications at exactly the individual cap shows all, no summary`() {
        val events = listOf(event("us1"), event("us2"), event("us3"))
        val plan = planDigestNotifications(events, maxIndividual = 3)
        assertEquals(events, plan.individual)
        assertEquals(0, plan.summaryExtraCount)
    }

    @Test fun `planDigestNotifications over the cap shows the first N and summarizes the rest`() {
        val events = listOf(event("us1"), event("us2"), event("us3"), event("us4"))
        val plan = planDigestNotifications(events, maxIndividual = 3)
        assertEquals(listOf(event("us1"), event("us2"), event("us3")), plan.individual)
        assertEquals(1, plan.summaryExtraCount)
    }

    @Test fun `planDigestNotifications well over the cap counts every extra`() {
        val events = (1..10).map { event("us$it") }
        val plan = planDigestNotifications(events, maxIndividual = 3)
        assertEquals(3, plan.individual.size)
        assertEquals(7, plan.summaryExtraCount)
    }

    @Test fun `planDigestNotifications honors a custom maxIndividual`() {
        val events = listOf(event("us1"), event("us2"), event("us3"))
        val plan = planDigestNotifications(events, maxIndividual = 1)
        assertEquals(listOf(event("us1")), plan.individual)
        assertEquals(2, plan.summaryExtraCount)
    }

    @Test fun `planDigestNotifications preserves caller-given order rather than re-sorting`() {
        // Ordering (e.g. by magnitude) is the CALLER's job -- this function just splits whatever
        // order it's handed.
        val events = listOf(event("weakest", mag = 2.0), event("strongest", mag = 7.0))
        val plan = planDigestNotifications(events)
        assertEquals(listOf("weakest", "strongest"), plan.individual.map { it.quake.id })
    }
}
