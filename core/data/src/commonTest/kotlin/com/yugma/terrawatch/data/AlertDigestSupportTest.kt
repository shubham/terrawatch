package com.yugma.terrawatch.data

import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import kotlin.test.Test
import kotlin.test.assertEquals

private fun q(
    id: String, mag: Double? = 5.0, timeMillis: Long = 900,
    sources: Map<Source, String> = mapOf(Source.USGS to id),
) = Quake(id, timeMillis, 7.1, 126.5, 10.0, mag, "mb", "Somewhere", false, null,
        QuakeStatus.AUTOMATIC, sources, emptyList(), timeMillis)

private fun event(
    id: String, mag: Double? = 5.0, timeMillis: Long = 900, ruleId: String = "near",
    sources: Map<Source, String> = mapOf(Source.USGS to id),
) = AlertEvent(q(id, mag, timeMillis, sources), ruleId)

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

    // Round 2 (ring-buffer adequacy, review finding): 100 -> 1000 -- see appendNotifiedIds' own
    // kdoc for the worst-case-identifiers-per-run math this reflects. RED before the fix (this
    // test asserted 100/"us2"/"us101" against the OLD default), GREEN after -- a genuine eviction
    // proof at the new production cap, not just a value-literal change.
    @Test fun `appendNotifiedIds default cap is 1000`() {
        val existing = (1..1000).joinToString(",") { "us$it" }
        val result = appendNotifiedIds(existing, listOf("us1001"))
        assertEquals(1000, result.split(",").size)
        assertEquals("us2", result.split(",").first()) // us1 fell off the front
        assertEquals("us1001", result.split(",").last())
    }

    // --- notifiedIdentifiers / filterFreshAlertEvents (Fix Round 1, I1) -------------------------

    @Test fun `notifiedIdentifiers includes only the canonical id for a single-source quake`() {
        assertEquals(setOf("us1"), notifiedIdentifiers(event("us1")))
    }

    @Test fun `notifiedIdentifiers includes the canonical id and every per-agency source id`() {
        val e = event("usgs-456", sources = mapOf(Source.USGS to "usgs-456", Source.EMSC to "emsc-123"))
        assertEquals(setOf("usgs-456", "emsc-123"), notifiedIdentifiers(e))
    }

    @Test fun `filterFreshAlertEvents keeps an event whose identifiers were never notified`() {
        val e = event("us1")
        assertEquals(listOf(e), filterFreshAlertEvents(listOf(e), alreadyNotifiedIds = emptySet()))
    }

    @Test fun `filterFreshAlertEvents drops an event whose own current id was already notified`() {
        val e = event("us1")
        assertEquals(emptyList(), filterFreshAlertEvents(listOf(e), alreadyNotifiedIds = setOf("us1")))
    }

    @Test fun `filterFreshAlertEvents absorbs a canonical-id swap -- an old source id already in the buffer suppresses re-notification`() {
        // A same-event merge later prefers USGS's id as canonical (DedupeEngine.merge can pick
        // either side) -- "emsc-123" was the row's OWN id back when it was first notified, so
        // that's what the worker's own ring buffer recorded at the time. The merged row's `id` has
        // since moved to "usgs-456", but its `sources` map still carries BOTH agency ids -- this
        // must NOT read as a brand-new, never-notified event.
        val swapped = event("usgs-456", sources = mapOf(Source.USGS to "usgs-456", Source.EMSC to "emsc-123"))
        assertEquals(emptyList(), filterFreshAlertEvents(listOf(swapped), alreadyNotifiedIds = setOf("emsc-123")))
    }

    @Test fun `filterFreshAlertEvents keeps fresh events and drops stale ones, order preserved`() {
        val fresh = event("us1")
        val stale = event("us2")
        assertEquals(listOf(fresh), filterFreshAlertEvents(listOf(fresh, stale), alreadyNotifiedIds = setOf("us2")))
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
