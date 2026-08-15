package com.yugma.terrawatch.data

import com.yugma.terrawatch.model.FavoriteAlertType
import com.yugma.terrawatch.model.FavoritePlace
import com.yugma.terrawatch.model.GeoPoint
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    // --- Task 2 (Plan 5): favoriteRuleId / favoriteLabelFromRuleId -------------------------------

    @Test fun `favoriteRuleId round-trips through favoriteLabelFromRuleId`() {
        val ruleId = favoriteRuleId("Tokyo")
        assertEquals("Tokyo", favoriteLabelFromRuleId(ruleId))
    }

    @Test fun `favoriteLabelFromRuleId returns null for a non-favorite rule id`() {
        assertNull(favoriteLabelFromRuleId("near"))
        assertNull(favoriteLabelFromRuleId("world"))
    }

    @Test fun `favoriteRuleId never collides with the fixed near or world ids`() {
        assertTrue(favoriteRuleId("near") != "near")
        assertTrue(favoriteRuleId("world") != "world")
    }

    // --- Task 2 (Plan 5): buildDigestRules --------------------------------------------------------

    private val tokyo = FavoritePlace(id = 1L, label = "Tokyo", point = GeoPoint(35.6762, 139.6503), alertType = FavoriteAlertType.ALL)
    private val delhi = FavoritePlace(id = 2L, label = "Delhi", point = GeoPoint(28.6139, 77.2090), alertType = FavoriteAlertType.MAJOR_ONLY)
    private val mumbai = FavoritePlace(id = 3L, label = "Mumbai", point = GeoPoint(19.0760, 72.8777), alertType = FavoriteAlertType.OFF)

    @Test fun `buildDigestRules with no favorites returns exactly the home rules, unchanged`() {
        assertEquals(DEFAULT_RULES, buildDigestRules(DEFAULT_RULES, emptyList(), favoriteRadiusKm = 100.0, favoriteMinMag = 4.5))
    }

    @Test fun `buildDigestRules skips an OFF favorite entirely -- no rule at all`() {
        val rules = buildDigestRules(DEFAULT_RULES, listOf(mumbai), favoriteRadiusKm = 100.0, favoriteMinMag = 4.5)
        assertEquals(DEFAULT_RULES, rules, "an OFF favorite must contribute zero rules")
    }

    @Test fun `buildDigestRules ALL favorite gets a rule centered on it, using the current radius and minMag`() {
        val rules = buildDigestRules(DEFAULT_RULES, listOf(tokyo), favoriteRadiusKm = 250.0, favoriteMinMag = 5.0)
        // Fix Round 1 (Review 1, MAJOR-1): looked up by id, not `.last()` -- with the corrected
        // `near, <favorites>, world` ordering, a single favorite now sits in the MIDDLE, not last
        // (world moved there instead) -- see buildDigestRules' own kdoc for why.
        val favoriteRule = rules.first { it.id == favoriteRuleId("Tokyo") }
        assertEquals(GeoPoint(35.6762, 139.6503), favoriteRule.center)
        assertEquals(250.0, favoriteRule.radiusKm)
        assertEquals(5.0, favoriteRule.minMag)
    }

    @Test fun `buildDigestRules MAJOR_ONLY favorite always uses M6point0, regardless of favoriteMinMag`() {
        val rules = buildDigestRules(DEFAULT_RULES, listOf(delhi), favoriteRadiusKm = 100.0, favoriteMinMag = 3.0)
        // Fix Round 1 (Review 1, MAJOR-1): looked up by id, not `.last()` -- see the identical note
        // on the ALL-favorite test just above.
        val favoriteRule = rules.first { it.id == favoriteRuleId("Delhi") }
        assertEquals(6.0, favoriteRule.minMag)
        assertEquals(GeoPoint(28.6139, 77.2090), favoriteRule.center)
    }

    // Fix Round 1 (Review 1, MAJOR-1): re-pinned ordering -- home's OWN first rule ("near") still
    // comes first (untouched "prefer home" guarantee), but favorites now come BEFORE "world", not
    // after it. Before this fix, "world" (mag>=6.0, unbounded radius) sat between "near" and every
    // favorite, so it intercepted every quake a MAJOR_ONLY favorite could ever match (identical
    // mag>=6.0 threshold, home rules always first) -- see the dedicated "dedupe" tests below for the
    // end-to-end proof through the real AlertRuleEngine.
    @Test fun `buildDigestRules orders near first, then one rule per eligible favorite, then world last`() {
        val rules = buildDigestRules(DEFAULT_RULES, listOf(tokyo, delhi, mumbai), favoriteRadiusKm = 100.0, favoriteMinMag = 4.5)
        // near, Tokyo (ALL), Delhi (MAJOR_ONLY), world -- Mumbai (OFF) contributes nothing, so it's
        // absent entirely, not merely disabled.
        assertEquals(4, rules.size)
        assertEquals(DEFAULT_RULES[0].id, rules[0].id, "near stays first -- home is still preferred")
        assertEquals(favoriteRuleId("Tokyo"), rules[1].id)
        assertEquals(favoriteRuleId("Delhi"), rules[2].id)
        assertEquals(DEFAULT_RULES[1].id, rules[3].id, "world moves LAST -- it must never shadow a favorite")
    }

    // --- Task 2 (Plan 5): the dedupe ruling, proven end-to-end through the REAL AlertRuleEngine ---
    // "one notification per quake max across home+all favorites (first matching place wins, prefer
    // home)" falls out of buildDigestRules' own ordering (home rules first, favorites in list order)
    // PLUS AlertRuleEngine.evaluate's existing first-match-wins `for` loop -- zero changes needed to
    // that engine itself. These tests prove the COMBINATION actually produces that behavior, not just
    // that buildDigestRules assembles a list in the right order.

    @Test fun `dedupe -- a quake matching both home and a favorite fires only the home rule`() {
        // Home at Tokyo's own coordinates too (so both rules are radius-eligible for the same
        // quake) -- home's "near" rule (index 0 in DEFAULT_RULES) must win over the favorite rule
        // appended after it.
        val home = GeoPoint(35.6762, 139.6503)
        val rules = buildDigestRules(DEFAULT_RULES, listOf(tokyo.copy(point = home)), favoriteRadiusKm = 500.0, favoriteMinMag = 4.5)
        val quakeAtHome = Quake(
            "q1", 1000, home.lat, home.lon, 10.0, 5.0, "mb", "Somewhere", false, null,
            QuakeStatus.AUTOMATIC, mapOf(Source.USGS to "q1"), emptyList(), 1000,
        )
        val result = AlertRuleEngine().evaluate(previous = null, current = quakeAtHome, rules = rules, home = home)
        assertEquals("near", result?.matchedRuleId, "home is preferred over a favorite at an overlapping location")
    }

    @Test fun `dedupe -- a quake matching only a favorite (too far from home) fires that favorite's rule`() {
        val home = GeoPoint(0.0, 0.0) // far from Tokyo -- home's "near" rule (500km default) can't reach it
        val rules = buildDigestRules(DEFAULT_RULES, listOf(tokyo), favoriteRadiusKm = 100.0, favoriteMinMag = 4.5)
        val quakeNearTokyo = Quake(
            "q2", 1000, tokyo.point.lat, tokyo.point.lon, 10.0, 5.0, "mb", "Tokyo area", false, null,
            QuakeStatus.AUTOMATIC, mapOf(Source.USGS to "q2"), emptyList(), 1000,
        )
        val result = AlertRuleEngine().evaluate(previous = null, current = quakeNearTokyo, rules = rules, home = home)
        assertEquals(favoriteRuleId("Tokyo"), result?.matchedRuleId)
    }

    @Test fun `dedupe -- a quake matching two overlapping favorites fires only the FIRST one's rule`() {
        val rules = buildDigestRules(
            DEFAULT_RULES,
            listOf(tokyo, tokyo.copy(id = 99L, label = "Also Tokyo")),
            favoriteRadiusKm = 100.0,
            favoriteMinMag = 4.5,
        )
        val quakeNearTokyo = Quake(
            "q3", 1000, tokyo.point.lat, tokyo.point.lon, 10.0, 5.0, "mb", "Tokyo area", false, null,
            QuakeStatus.AUTOMATIC, mapOf(Source.USGS to "q3"), emptyList(), 1000,
        )
        val result = AlertRuleEngine().evaluate(previous = null, current = quakeNearTokyo, rules = rules, home = null)
        assertEquals(favoriteRuleId("Tokyo"), result?.matchedRuleId, "the first-listed overlapping favorite wins")
    }

    @Test fun `MAJOR_ONLY favorite does not fire for a sub-6point0 quake even within its radius`() {
        val rules = buildDigestRules(DEFAULT_RULES, listOf(delhi), favoriteRadiusKm = 500.0, favoriteMinMag = 3.0)
        val moderateQuake = Quake(
            "q4", 1000, delhi.point.lat, delhi.point.lon, 10.0, 5.5, "mb", "Delhi area", false, null,
            QuakeStatus.AUTOMATIC, mapOf(Source.USGS to "q4"), emptyList(), 1000,
        )
        // DEFAULT_RULES' own "world" rule also needs M6.0+, so a 5.5 matches NOTHING here.
        val result = AlertRuleEngine().evaluate(previous = null, current = moderateQuake, rules = rules, home = null)
        assertEquals(null, result)
    }

    // --- Fix Round 1 (Review 1, MAJOR-1): MAJOR_ONLY was permanently unreachable -- "world" (mag
    // >=6.0, unbounded) always sat ahead of every favorite in the old ordering, and shared MAJOR_
    // ONLY's own exact 6.0 threshold, so "world" intercepted 100% of the quakes that could ever
    // match a MAJOR_ONLY favorite's own rule, with the notification still firing but the favorite's
    // OWN attribution (its label in the copy) never actually winning. These three tests are this
    // fix's own TDD spec: a MAJOR_ONLY favorite must win for a matching M6+ quake near it, "world"
    // must remain the correct fallback when nothing more specific matches, and the favorite's own
    // floor must still gate correctly below 6.0 (no false-early fire).

    @Test fun `dedupe -- a mag 6point5 quake near a MAJOR_ONLY favorite is attributed to that favorite, not swallowed by world`() {
        val rules = buildDigestRules(DEFAULT_RULES, listOf(delhi), favoriteRadiusKm = 100.0, favoriteMinMag = 4.5)
        val majorQuakeNearDelhi = Quake(
            "q5", 1000, delhi.point.lat, delhi.point.lon, 10.0, 6.5, "mww", "Delhi area", false, null,
            QuakeStatus.AUTOMATIC, mapOf(Source.USGS to "q5"), emptyList(), 1000,
        )
        // home is far from Delhi, so "near" (500km default) can't reach it either -- isolates this
        // down to exactly the favorite-vs-world contest this fix is about.
        val result = AlertRuleEngine().evaluate(
            previous = null, current = majorQuakeNearDelhi, rules = rules, home = GeoPoint(0.0, 0.0),
        )
        assertEquals(favoriteRuleId("Delhi"), result?.matchedRuleId, "the MAJOR_ONLY favorite's own rule must win, not 'world'")
    }

    @Test fun `dedupe -- a mag 6point5 quake far from every configured place still fires world, unchanged`() {
        val rules = buildDigestRules(DEFAULT_RULES, listOf(delhi), favoriteRadiusKm = 100.0, favoriteMinMag = 4.5)
        val farAwayMajorQuake = Quake(
            "q6", 1000, -33.0, -70.0, 10.0, 6.5, "mww", "Somewhere far from every place", false, null,
            QuakeStatus.AUTOMATIC, mapOf(Source.USGS to "q6"), emptyList(), 1000,
        )
        val result = AlertRuleEngine().evaluate(
            previous = null, current = farAwayMajorQuake, rules = rules, home = GeoPoint(0.0, 0.0),
        )
        assertEquals("world", result?.matchedRuleId, "world must still be the correct fallback when nothing more specific matches")
    }

    @Test fun `dedupe -- a MAJOR_ONLY favorite plus a mag 5point5 quake near it fires neither the favorite nor world`() {
        val rules = buildDigestRules(DEFAULT_RULES, listOf(delhi), favoriteRadiusKm = 100.0, favoriteMinMag = 4.5)
        val moderateQuakeNearDelhi = Quake(
            "q7", 1000, delhi.point.lat, delhi.point.lon, 10.0, 5.5, "mb", "Delhi area", false, null,
            QuakeStatus.AUTOMATIC, mapOf(Source.USGS to "q7"), emptyList(), 1000,
        )
        val result = AlertRuleEngine().evaluate(
            previous = null, current = moderateQuakeNearDelhi, rules = rules, home = GeoPoint(0.0, 0.0),
        )
        assertEquals(null, result, "5.5 is below both the favorite's MAJOR_ONLY floor (6.0) and world's own floor (6.0)")
    }
}
