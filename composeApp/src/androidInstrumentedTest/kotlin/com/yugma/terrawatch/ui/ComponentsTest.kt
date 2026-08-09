package com.yugma.terrawatch.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.yugma.terrawatch.data.PillStatus
import com.yugma.terrawatch.detail.DetailSheet
import com.yugma.terrawatch.home.FeedList
import com.yugma.terrawatch.model.MagRevision
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import com.yugma.terrawatch.model.magnitudeBand
import com.yugma.terrawatch.ui.components.BadgeSize
import com.yugma.terrawatch.ui.components.MagnitudeBadge
import com.yugma.terrawatch.ui.components.QuakeCard
import com.yugma.terrawatch.ui.components.StatusShield
import com.yugma.terrawatch.ui.theme.TerraColors
import com.yugma.terrawatch.ui.theme.TerraTheme
import kotlin.math.abs
import org.junit.Rule
import org.junit.Test

/**
 * Task 13: instrumented component tests, on-device. These use [createComposeRule] with direct
 * composable content only — no [com.yugma.terrawatch.MainActivity], no Koin, no DI wiring — pinning
 * the same behaviors [ComponentsTest] class name promises per the plan brief: MagnitudeBadge's
 * number+band-color pairing, QuakeCard's formatted fields, StatusShield's three faces, DetailSheet's
 * full render (revision badge + tsunami banner + share action), and FeedList rendering one card per
 * quake. `captureToImage()` (used for the one color assertion) needs a real, hardware-rendered
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
        composeTestRule.onNodeWithText("6.1").assertExists()

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

    private fun assertColorsClose(expected: Color, actual: Color, tolerance: Float = 0.06f) {
        val closeEnough = abs(expected.red - actual.red) <= tolerance &&
            abs(expected.green - actual.green) <= tolerance &&
            abs(expected.blue - actual.blue) <= tolerance
        assert(closeEnough) { "expected color close to $expected but was $actual (tolerance=$tolerance)" }
    }
}
