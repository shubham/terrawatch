package com.yugma.terrawatch.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.yugma.terrawatch.data.PillStatus
import com.yugma.terrawatch.detail.DetailSheet
import com.yugma.terrawatch.home.FeedList
import com.yugma.terrawatch.home.FeedSheet
import com.yugma.terrawatch.home.LiveStatusRow
import com.yugma.terrawatch.model.MagRevision
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import com.yugma.terrawatch.model.magnitudeBand
import com.yugma.terrawatch.ui.components.BadgeSize
import com.yugma.terrawatch.ui.components.MagnitudeBadge
import com.yugma.terrawatch.ui.components.QuakeCard
import com.yugma.terrawatch.ui.components.StatusShield
import com.yugma.terrawatch.ui.components.TsunamiBanner
import com.yugma.terrawatch.ui.theme.TerraColors
import com.yugma.terrawatch.ui.theme.TerraTheme
import kotlin.math.abs
import org.junit.Rule
import org.junit.Test

/**
 * Task 13: instrumented component tests, on-device. These use [createComposeRule] with direct
 * composable content only — no [com.yugma.terrawatch.MainActivity], no Koin, no DI wiring — pinning
 * the same behaviors [ComponentsTest] class name promises per the plan brief: MagnitudeBadge's
 * number+band-color pairing, QuakeCard's formatted fields, StatusShield's three faces,
 * TsunamiBanner's advisory face, DetailSheet's full render (revision badge + tsunami banner +
 * share action), and FeedList rendering one card per quake. `captureToImage()` (used for the one
 * color assertion) needs a real, hardware-rendered
 * window — the reason this whole class is an `androidInstrumentedTest`, not a Robolectric/jvmTest
 * double: a color assertion using ONLY semantics (no pixel read) can't tell "the badge is the wrong
 * color" from "the badge is missing entirely", the two failure modes this test exists to
 * distinguish.
 */
class ComponentsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    // MagnitudeBadge -----------------------------------------------------------------------------

    @Test
    fun magnitudeBadge_showsTheNumberAndTheBandColor() {
        // 6.1 -> MAJOR (magnitudeBand: mag < 6.0 is STRONG, else MAJOR — grepped
        // core/model/src/commonMain/kotlin/com/yugma/terrawatch/model/MagnitudeBand.kt before
        // picking this value so the expected band below is provably right, not assumed).
        val band = magnitudeBand(6.1)
        composeTestRule.setContent {
            TerraTheme {
                Box(Modifier.testTag("badge-under-test")) {
                    MagnitudeBadge(mag = 6.1, band = band, size = BadgeSize.Large)
                }
            }
        }
        // The number half of the "color never appears without the number" pairing (spec Global
        // Constraints, enforced structurally by MagnitudeBadge — see its own kdoc).
        //
        // Task 10 (item g) fix: MagnitudeBadge now wraps its content in `clearAndSetSemantics`
        // (contentDescription = "Magnitude 6.1") so TalkBack reads one clean sentence instead of a
        // bare "6.1" - which removes the raw "6.1" Text node from the MERGED semantics tree this
        // query used to find it in (confirmed via the real device failure this fix responds to:
        // "Expected exactly '1' node... However, the unmerged tree contains '1' node that matches").
        // `useUnmergedTree = true` reaches the actual rendered Text glyph directly, unaffected by
        // the parent's semantics override - still proving the number is genuinely ON SCREEN (not
        // just present in some contentDescription string), which is this assertion's whole point.
        composeTestRule.onNodeWithText("6.1", useUnmergedTree = true).assertExists()

        // The color half: sample the rendered badge's own bounds at dead center (inside the rounded
        // rect's flat fill, away from the corner radius's anti-aliased edge) and compare against
        // TerraColors.MagMajor (com.yugma.terrawatch.ui.theme.magnitudeColor's MAJOR mapping).
        val pixelMap = composeTestRule.onNodeWithTag("badge-under-test").captureToImage().toPixelMap()
        val centerPixel = pixelMap[pixelMap.width / 2, pixelMap.height / 2]
        assertColorsClose(expected = TerraColors.MagMajor, actual = centerPixel)
    }

    // QuakeCard ------------------------------------------------------------------------------------

    @Test
    fun quakeCard_rendersFormattedDepthRelativeTimeAndDistance() {
        // depthKm deliberately the brief's own float-garbage example (31.1599998474121) — proves
        // the card renders through formatDepthKm(), not a raw Double.toString(). timeMillis ==
        // nowMillis below so formatRelativeTime's boundary case ("just now", diff < 60s) is exact,
        // not approximate, and distanceKm (4102.3) exercises formatDistanceKm's thousands-grouping —
        // all three land in QuakeCard's one metaLine() Text node (see QuakeCard.kt), so one exact
        // match on that whole line pins depth+time+distance formatting together.
        val quake = Quake(
            id = "us1234",
            timeMillis = 1_000_000L,
            lat = 7.1,
            lon = 126.5,
            depthKm = 31.1599998474121,
            mag = 5.5,
            magType = "mw",
            place = "10km SE of Testville",
            tsunami = false,
            felt = null,
            status = QuakeStatus.AUTOMATIC,
            sources = mapOf(Source.USGS to "us1234"),
            revisions = listOf(MagRevision(5.5, "mw", 1_000_000L, Source.USGS)),
            updatedAtMillis = 1_000_000L,
        )
        composeTestRule.setContent {
            TerraTheme {
                QuakeCard(quake = quake, distanceKm = 4102.3, nowMillis = 1_000_000L, onClick = {})
            }
        }
        composeTestRule.onNodeWithText("10km SE of Testville").assertExists()
        composeTestRule.onNodeWithText("just now · 31.2 km · 4,102 km away").assertExists()
    }

    // StatusShield's three faces ---------------------------------------------------------------

    @Test
    fun statusShield_calmVariant_showsTheAllCalmText() {
        composeTestRule.setContent {
            TerraTheme {
                StatusShield(status = PillStatus(PillStatus.Kind.CALM, null), nowMillis = 0L, onClick = {})
            }
        }
        // Exact string grepped from core/ui's StatusShield.kt CalmContent() — not paraphrased.
        composeTestRule.onNodeWithText("All calm near you").assertExists()
    }

    @Test
    fun statusShield_alertVariant_showsTheQuakesPlace() {
        val quake = Quake(
            id = "q1",
            timeMillis = 1_000_000L,
            lat = 1.0,
            lon = 2.0,
            depthKm = 10.0,
            mag = 5.0,
            magType = "mw",
            place = "Alert Springs",
            tsunami = false,
            felt = null,
            status = QuakeStatus.AUTOMATIC,
            sources = mapOf(Source.USGS to "q1"),
            revisions = listOf(MagRevision(5.0, "mw", 1_000_000L, Source.USGS)),
            updatedAtMillis = 1_000_000L,
        )
        composeTestRule.setContent {
            TerraTheme {
                StatusShield(
                    status = PillStatus(PillStatus.Kind.ALERT, quake),
                    nowMillis = 1_000_000L,
                    onClick = {},
                )
            }
        }
        // AlertContent renders "M {mag} · {place} · {relative time}" as one Text node — substring
        // match on the place segment, since this test cares about place appearing, not the whole
        // composed string (that composition is QuakeCard/AlertContent's own concern, not this test's).
        composeTestRule.onNodeWithText("Alert Springs", substring = true).assertExists()
    }

    @Test
    fun statusShield_askLocationVariant_showsTheWhereAreYouText() {
        composeTestRule.setContent {
            TerraTheme {
                StatusShield(
                    status = PillStatus(PillStatus.Kind.ASK_LOCATION, null),
                    nowMillis = 0L,
                    onClick = {},
                )
            }
        }
        // Exact string grepped from StatusShield.kt AskLocationContent().
        composeTestRule.onNodeWithText("Where are you?").assertExists()
    }

    // TsunamiBanner's advisory face -------------------------------------------------------------

    @Test
    fun tsunamiBanner_advisoryState_showsWarningText() {
        // The red/advisory face (tsunami=true) had zero render coverage anywhere before this test:
        // detailSheet_rendersRevisionBadgeTsunamiBannerAndShareAction below only ever exercises the
        // calm face (tsunami=false), and HomeViewModel.injectDebugQuake — the one hand-triggerable
        // path to a live DetailSheet on device — hardcodes tsunami = false too. Exact string grepped
        // from TsunamiBanner.kt, not paraphrased.
        composeTestRule.setContent {
            TerraTheme {
                TsunamiBanner(tsunami = true)
            }
        }
        composeTestRule.onNodeWithText("Tsunami advisory issued").assertExists()
    }

    // DetailSheet full render ------------------------------------------------------------------

    @Test
    fun detailSheet_rendersRevisionBadgeTsunamiBannerAndShareAction() {
        // Two DISTINCT magnitudes (5.9 then 6.1) so revisionNote() (core/ui/.../Formats.kt) returns
        // non-null — grepped its own kdoc/impl before picking these: fewer than 2 distinct values
        // collapses to null and the badge would never compose at all.
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
            revisions = listOf(
                MagRevision(5.9, "mw", 900_000L, Source.USGS),
                MagRevision(6.1, "mw", 1_000_000L, Source.USGS),
            ),
            updatedAtMillis = 1_000_000L,
        )
        composeTestRule.setContent {
            TerraTheme {
                DetailSheet(
                    quake = quake,
                    distanceKm = 4102.3,
                    nowMillis = 1_000_000L, // == latest revision's atMillis -> "just now"
                    onShare = {},
                    onDismiss = {},
                )
            }
        }
        // revisionNote(revisions, now) = "revised from M 5.9 · just now" (previous distinct mag,
        // then formatRelativeTime of the LATEST revision's own timestamp — see Formats.kt kdoc for
        // why it's the latest's age, not the previous one's).
        composeTestRule.onNodeWithText("revised from M 5.9 · just now").assertExists()
        // tsunami = false -> the calm face's exact text (TsunamiBanner.kt).
        composeTestRule.onNodeWithText("Tsunami not expected").assertExists()
        // Share button exists (existence only, per brief — clicking it fires a platform intent that
        // ActionRow.kt's own onShare callback already covers via buildShareText, unit-tested in
        // DetailSheetTest.kt).
        composeTestRule.onNodeWithText("Share").assertExists()
    }

    // FeedList ----------------------------------------------------------------------------------

    @Test
    fun feedList_rendersOneCardPerQuake() {
        val quakes = (1..3).map { i ->
            Quake(
                id = "feed-$i",
                timeMillis = 1_000_000L,
                lat = 1.0,
                lon = 2.0,
                depthKm = 10.0,
                mag = 4.0 + i,
                magType = "mw",
                place = "Feed Place $i",
                tsunami = false,
                felt = null,
                status = QuakeStatus.AUTOMATIC,
                sources = mapOf(Source.USGS to "feed-$i"),
                revisions = listOf(MagRevision(4.0 + i, "mw", 1_000_000L, Source.USGS)),
                updatedAtMillis = 1_000_000L,
            )
        }
        composeTestRule.setContent {
            TerraTheme {
                FeedList(quakes = quakes, nowMillis = 1_000_000L, distanceKm = { null }, onQuakeClick = {})
            }
        }
        // Each quake's own place text existing separately is the honest proxy for "N cards, keyed
        // by id" available from the semantics tree — LazyColumn's `key = { it.id }` (FeedSheet.kt)
        // itself isn't a property the accessibility/semantics tree exposes to a test; if the three
        // cards weren't each composed as their own distinct row, at least one of these three
        // lookups would fail to find its text (LazyColumn does not merge sibling items' semantics).
        quakes.forEach { quake ->
            composeTestRule.onNodeWithText(quake.place).assertExists()
        }
        val allPlaceNodes = composeTestRule.onAllNodesWithText("Feed Place", substring = true)
            .fetchSemanticsNodes()
        assert(allPlaceNodes.size == 3) { "expected 3 distinct feed cards, found ${allPlaceNodes.size}" }
    }

    // Task 10 (item g, a11y) --------------------------------------------------------------------

    @Test
    fun magnitudeBadge_exposesAContentDescriptionInsteadOfABareNumber() {
        composeTestRule.setContent {
            TerraTheme {
                MagnitudeBadge(mag = 6.1, band = magnitudeBand(6.1), size = BadgeSize.Large)
            }
        }
        // clearAndSetSemantics (MagnitudeBadge.kt) replaces the bare "6.1" node text with this -
        // querying by content description (not text) proves the override actually took effect,
        // not just that the visible number still happens to be findable some other way.
        composeTestRule.onNodeWithContentDescription("Magnitude 6.1").assertExists()
    }

    @Test
    fun magnitudeBadge_nullMagnitudeReadsAsUnknownNotAsAnEmDash() {
        composeTestRule.setContent {
            TerraTheme {
                MagnitudeBadge(mag = null, band = magnitudeBand(null), size = BadgeSize.Small)
            }
        }
        composeTestRule.onNodeWithContentDescription("Magnitude unknown").assertExists()
    }

    @Test
    fun statusShield_calmVariant_hasTheDynamicPillContentDescription() {
        composeTestRule.setContent {
            TerraTheme {
                StatusShield(status = PillStatus(PillStatus.Kind.CALM, null), nowMillis = 0L, onClick = {}, radiusKm = 100.0)
            }
        }
        // Exact string from StatusShield.kt's pillContentDescription() - also pinned without
        // Compose in StatusShieldTest (core:ui jvmTest); this proves the semantics wiring itself
        // (mergeDescendants + contentDescription) actually reaches a real accessibility node.
        composeTestRule.onNodeWithContentDescription("All calm near you, nothing within 100 kilometers").assertExists()
    }

    @Test
    fun statusShield_pillMeetsThe48dpMinimumTouchTarget() {
        composeTestRule.setContent {
            TerraTheme {
                Box(Modifier.testTag("pill-under-test")) {
                    // ALERT's content (badge + one text line) is this pill's SHORTEST face - the
                    // one most at risk of measuring under 48dp before defaultMinSize was added.
                    val quake = Quake(
                        id = "q1", timeMillis = 0L, lat = 0.0, lon = 0.0, depthKm = 1.0, mag = 5.0,
                        magType = "mw", place = "Testville", tsunami = false, felt = null,
                        status = QuakeStatus.AUTOMATIC, sources = mapOf(Source.USGS to "q1"),
                        revisions = listOf(MagRevision(5.0, "mw", 0L, Source.USGS)), updatedAtMillis = 0L,
                    )
                    StatusShield(status = PillStatus(PillStatus.Kind.ALERT, quake), nowMillis = 0L, onClick = {})
                }
            }
        }
        composeTestRule.onNodeWithTag("pill-under-test").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun statusShield_alertVariant_contentDescriptionIsExactlyOneSentence_noDuplicateMagnitudeFromTheNestedBadge() {
        // Fix round 1 (code review, Important): the ALERT pill nests a MagnitudeBadge, and
        // StatusShield's own Surface merges descendant semantics (mergeDescendants = true) - before
        // this fix, the badge's OWN "Magnitude 6.1" contentDescription rode along into the pill's
        // merged list ON TOP OF pillContentDescription's already-complete sentence, producing a
        // double-read TalkBack would announce as two magnitudes back to back. A presence-style
        // `onNodeWithContentDescription(...).assertExists()` query (as used by the CALM variant test
        // above) CANNOT catch this: it only checks the intended string is somewhere in the merged
        // list, not that the list has no other entries. `assertContentDescriptionEquals` (exact-list)
        // is the query shape that catches it - run on-device (98bc1cd8) against the unfixed code
        // first: RED, actual list `[..."10.0 km deep", "Magnitude 6.1"]` (the exact duplicate
        // predicted, verbatim from the device failure - see task-10-report.md's Fix Round 1), then
        // GREEN again after restoring `Modifier.clearAndSetSemantics {}` on AlertContent's
        // MagnitudeBadge call site.
        //
        // `testTag` goes directly on StatusShield's own `modifier` param (NOT a wrapping Box, unlike
        // the touch-target test above): `assertHeightIsAtLeast` reads layout bounds, which a tightly-
        // wrapping Box happens to share with its one child, but `assertContentDescriptionEquals`
        // reads a semantics PROPERTY, which does NOT propagate from a merging child up to a
        // non-merging parent Box - tagging the wrapper would silently match an empty-semantics node
        // instead of the pill's own merged one (caught turning this test on for the first time).
        val quake = Quake(
            id = "q1", timeMillis = 1_000_000L, lat = 7.1, lon = 126.5, depthKm = 10.0, mag = 6.1,
            magType = "mw", place = "Mindanao, Philippines", tsunami = false, felt = null,
            status = QuakeStatus.AUTOMATIC, sources = mapOf(Source.USGS to "q1"),
            revisions = listOf(MagRevision(6.1, "mw", 1_000_000L, Source.USGS)), updatedAtMillis = 1_000_000L,
        )
        composeTestRule.setContent {
            TerraTheme {
                StatusShield(
                    status = PillStatus(PillStatus.Kind.ALERT, quake),
                    nowMillis = 1_000_000L,
                    onClick = {},
                    modifier = Modifier.testTag("alert-pill-under-test"),
                )
            }
        }
        // Same exact string StatusShieldTest (core:ui jvmTest) pins for pillContentDescription's
        // ALERT branch against this identical fixture - one source of truth for what the sentence
        // should say, this test's own job is proving it's the ONLY thing announced.
        composeTestRule.onNodeWithTag("alert-pill-under-test")
            .assertContentDescriptionEquals(
                "Alert. Magnitude 6.1, Mindanao, Philippines, just now, 10.0 km deep",
            )
    }

    @Test
    fun liveStatusRow_exposesOneCleanConnectionSentence() {
        composeTestRule.setContent {
            TerraTheme {
                LiveStatusRow(isLive = true)
            }
        }
        composeTestRule.onNodeWithContentDescription("Live connection active").assertExists()
        // The bare "LIVE" text must NOT also be independently reachable - clearAndSetSemantics
        // should have replaced it, not merely added to it (a double-read regression).
        composeTestRule.onAllNodesWithText("LIVE").assertCountEquals(0)
    }

    // FeedSheet empty state (Task 10, item c) ----------------------------------------------------

    @Test
    fun feedSheet_emptyContentShowsTheQuietCopy() {
        // The LIVE feed realistically never reaches zero quakes worldwide in a 24h window - this
        // is the honest way to exercise the exact shipped empty-state code path at all (a
        // synthetic empty list, not a claim that the world is quiet). Deliberately NOT a
        // screenshot/evidence capture: per this task's own dispatch instructions the feed-empty
        // device screenshot is only ever taken if the world is genuinely quiet (never, in
        // practice) and must otherwise be SKIPPED rather than manufactured here — this test's job
        // is behavioral coverage of FeedEmptyState only.
        composeTestRule.setContent {
            TerraTheme {
                Box(Modifier.testTag("feed-empty-under-test")) {
                    FeedSheet(
                        quakes = emptyList(),
                        isLive = true,
                        newCount = 0,
                        nowMillis = 0L,
                        distanceKm = { null },
                        onQuakeClick = {},
                        isLoading = false,
                    )
                }
            }
        }
        composeTestRule.onNodeWithText("Quiet right now — no quakes in the last 24 h").assertExists()
        composeTestRule.onNodeWithText("Data updates every minute").assertExists()
    }

    private fun assertColorsClose(expected: Color, actual: Color, tolerance: Float = 0.06f) {
        val closeEnough = abs(expected.red - actual.red) <= tolerance &&
            abs(expected.green - actual.green) <= tolerance &&
            abs(expected.blue - actual.blue) <= tolerance
        assert(closeEnough) { "expected color close to $expected but was $actual (tolerance=$tolerance)" }
    }
}
