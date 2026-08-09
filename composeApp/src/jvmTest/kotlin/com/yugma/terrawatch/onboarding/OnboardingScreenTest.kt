package com.yugma.terrawatch.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 8 (Plan 3): TDD for the two pure, non-Compose pieces of the onboarding screen — written RED
 * first (neither [defaultRuleSummary] nor [ONBOARDING_STEPS] existed yet), same
 * "so a test can pin it without a device" convention `StatusShieldTest`/`SettingsScreenTest`
 * already established for their own screens' pure helpers.
 */
class OnboardingScreenTest {
    @Test fun `defaultRuleSummary at the shipped AlertRuleStore defaults matches the controller's Task 8 dispatch verbatim`() {
        // The exact string the controller's Task 8 dispatch quotes: "M >= 4.5 within 100 km * M >= 6
        // worldwide -- change anytime in Settings" (ASCII-ized here in this comment only -- the
        // real assertion below uses the literal '>=' and '*' glyphs the app actually renders).
        assertEquals(
            "M ≥ 4.5 within 100 km · M ≥ 6 worldwide — change anytime in Settings",
            defaultRuleSummary(minMag = 4.5, radiusKm = 100.0),
        )
    }

    @Test fun `defaultRuleSummary thousands-groups a wide radius`() {
        assertEquals(
            "M ≥ 4.5 within 1,000 km · M ≥ 6 worldwide — change anytime in Settings",
            defaultRuleSummary(minMag = 4.5, radiusKm = 1000.0),
        )
    }

    @Test fun `defaultRuleSummary formats a non-default min magnitude to one decimal`() {
        assertEquals(
            "M ≥ 5.0 within 250 km · M ≥ 6 worldwide — change anytime in Settings",
            defaultRuleSummary(minMag = 5.0, radiusKm = 250.0),
        )
    }

    @Test fun `defaultRuleSummary always names the fixed world rule as a bare 6, not 6point0`() {
        // The "world" rule's minMag (AlertRuleEngine.DEFAULT_RULES, id="world") is a compile-time
        // 6.0 Double, but the controller's Task 8 dispatch's quoted copy reads "M >= 6 worldwide", not
        // "M >= 6.0 worldwide" -- confirms this is a literal string segment, not formatMagnitude(6.0).
        assertTrue(defaultRuleSummary(minMag = 4.5, radiusKm = 100.0).contains("M ≥ 6 worldwide"))
    }

    @Test fun `there are exactly three onboarding steps, each with non-blank title and body`() {
        assertEquals(3, ONBOARDING_STEPS.size)
        ONBOARDING_STEPS.forEach { step ->
            assertTrue(step.title.isNotBlank())
            assertTrue(step.body.isNotBlank())
        }
    }
}
