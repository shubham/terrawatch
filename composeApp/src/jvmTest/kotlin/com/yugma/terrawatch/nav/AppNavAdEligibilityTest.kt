package com.yugma.terrawatch.nav

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Settings-nav follow-up (not labeled "Task 3b" — see AppNav.kt's [AD_ELIGIBLE_ROUTES] kdoc for
 * why: that label is already the feed-sheet live-reveal fix — the Settings-navigation ad-reload gap
 * Task 3's own report named as a deliberate,
 * documented out-of-scope gap — see `task-3-report.md` section 4/Concerns): [isAdEligibleRoute]'s
 * route -> ad-eligibility truth table, TDD'd RED (`Unresolved reference 'isAdEligibleRoute'`) before
 * the function existed, GREEN after — same "extract the pure fn, pin it in jvmTest with no Compose
 * runtime involved" convention [com.yugma.terrawatch.home.LayoutModeTest]/
 * [com.yugma.terrawatch.home.HomeScreenBannerTest] already established for this codebase.
 *
 * This is a DIFFERENT axis from [com.yugma.terrawatch.ads.adSlotVisible] (`core:ads`, untouched by
 * this task — signature and its own 8-case truth table both still exactly as Task 3 left them) —
 * that function answers "given Plus/detail/onboarding state, should an already-eligible ad surface
 * be visually showing right now"; this one answers "is the CURRENT ROUTE the kind of place an ad is
 * ever allowed to appear at all," independent of those three inputs. [AppNav]'s real call site ANDs
 * both together into [com.yugma.terrawatch.ads.BannerAdSlot]'s `visible` parameter — see that call
 * site's own comment for why the two stay separate rather than folding a 4th input into
 * `adSlotVisible` itself.
 */
class AppNavAdEligibilityTest {
    @Test
    fun `home is ad-eligible`() {
        assertTrue(isAdEligibleRoute(Routes.HOME))
    }

    @Test
    fun `history is ad-eligible`() {
        assertTrue(isAdEligibleRoute(Routes.HISTORY))
    }

    @Test
    fun `insights is ad-eligible`() {
        assertTrue(isAdEligibleRoute(Routes.INSIGHTS))
    }

    @Test
    fun `settings is NOT ad-eligible - the exact gap this task fixes`() {
        // Before this fix, Settings had no ad-eligibility concept at all: BannerAdSlot's call site
        // was nested inside the same `if (showTabChrome)` block as the bottom bar, so navigating to
        // Settings unmounted (destroyed) the AdView entirely rather than merely hiding it. This case
        // is the one this whole task exists to pin — must stay false, but via hide/collapse at the
        // call site below, never by no longer calling BannerAdSlot at all.
        assertFalse(isAdEligibleRoute(Routes.SETTINGS))
    }

    @Test
    fun `onboarding is NOT ad-eligible`() {
        assertFalse(isAdEligibleRoute(Routes.ONBOARDING))
    }

    @Test
    fun `paywall is NOT ad-eligible`() {
        // Showing an ad on the screen whose whole purpose is selling ad-removal would be an odd
        // product call, and this was already true before this task (Paywall was never a TAB_ROUTES
        // member either) -- this case pins that this fix's refactor doesn't change that, even though
        // Paywall now ALSO keeps its AdView mounted-but-hidden rather than destroyed (a side effect
        // of this fix, not something newly requested — see the report's own notes).
        assertFalse(isAdEligibleRoute(Routes.PAYWALL))
    }

    @Test
    fun `null route (back stack not yet settled) is NOT ad-eligible`() {
        assertFalse(isAdEligibleRoute(null))
    }

    @Test
    fun `an unrecognized route string is NOT ad-eligible`() {
        // Defensive: a future route added to the nav graph is ad-INeligible by default until someone
        // deliberately adds it to AD_ELIGIBLE_ROUTES, never accidentally ad-eligible by omission.
        assertFalse(isAdEligibleRoute("some-future-route"))
    }
}
