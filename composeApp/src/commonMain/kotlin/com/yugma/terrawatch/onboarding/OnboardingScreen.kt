package com.yugma.terrawatch.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yugma.terrawatch.data.AlertRuleStore
import com.yugma.terrawatch.data.PillStatus
import com.yugma.terrawatch.location.CityPickerDialog
import com.yugma.terrawatch.location.LocationRequester
import com.yugma.terrawatch.location.canRequestLocation
import com.yugma.terrawatch.ui.components.StatusShield
import com.yugma.terrawatch.ui.format.formatCount
import com.yugma.terrawatch.ui.format.formatMagnitude
import com.yugma.terrawatch.ui.theme.TerraRadii
import kotlin.math.roundToLong
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private const val STEP_COUNT = 3

/**
 * One onboarding step's fixed heading copy (Task 8, Plan 3 — design spec §3.6: "Three lightweight
 * steps, skippable: (1) what the app does... (2) location ask... (3) notification permission ask
 * *with the default rule shown*"). Only the ALWAYS-static half of a step's content lives here —
 * step 2's button labels and step 3's live [defaultRuleSummary] line aren't reusable across steps
 * the way a plain title/body pair is, so they stay in their own composables below rather than
 * being forced into this shape for its own sake.
 *
 * `internal`, not `private` — so a jvmTest can pin this copy exists and is non-blank without
 * spinning up Compose (`OnboardingScreenTest`), the same "so a test can pin it" convention
 * `StatusShield.calmSubtitle`/`SettingsScreen.closestRadiusStepIndex`/
 * `InsightsScreen.dayCountLabels` already established for their own screens' pure pieces. This is
 * the "step-content data (titles/bodies) as a list" the plan Task 8 brief calls "trivial" TDD —
 * genuinely trivial (no branching logic to get wrong), still worth a pinned test per that brief's
 * own instruction rather than skipped as "too simple to bother."
 */
internal data class OnboardingStepContent(val title: String, val body: String)

internal val ONBOARDING_STEPS = listOf(
    OnboardingStepContent(
        title = "Know the ground beneath you",
        body = "Live earthquakes from around the world, straight from USGS and EMSC — with " +
            "honest alerts only when one is actually close enough to matter.",
    ),
    OnboardingStepContent(
        title = "Where should we watch?",
        body = "TerraWatch checks your location against nearby earthquakes so the status pill " +
            "can warn you when one's close. It stays on this device — never uploaded or shared.",
    ),
    OnboardingStepContent(
        title = "Alerts, coming soon",
        body = "Here's the default rule that will apply the moment notifications ship:",
    ),
)

/**
 * The rule-summary line step 3 renders — plan Task 8's own brief, quoted verbatim: `"M ≥ 4.5
 * within 100 km · M ≥ 6 worldwide — change anytime in Settings"`. Pure and `internal` for the same
 * "TDD what's pure" reason [ONBOARDING_STEPS] is — `OnboardingScreenTest` pins the exact shipped
 * string, including the mid-range/wide-radius/non-default-magnitude shapes.
 *
 * Only [minMag]/[radiusKm] (the user-settable "near" rule — [AlertRuleStore]'s own two stored
 * fields) are parameters. The "world" rule's `M ≥ 6` is deliberately a literal, not a third
 * parameter: `AlertRuleEngine.DEFAULT_RULES` hardcodes that exact threshold too (`id="world"`,
 * `minMag=6.0`), and unlike the near rule it is not user-settable anywhere in this app, so there
 * is no live value to thread through here. Bare `"6"` (not [formatMagnitude]'s `"6.0"`) matches the
 * plan brief's own quoted copy exactly — confirmed by `OnboardingScreenTest`, which would fail if
 * this were built from `formatMagnitude(6.0)` instead.
 *
 * Call sites pass [AlertRuleStore.DEFAULT_MIN_MAG]/[AlertRuleStore.DEFAULT_RADIUS_KM] — the
 * store's OWN compile-time defaults, read directly rather than duplicated as a second pair of
 * literals here, matching this codebase's established "one source of truth" convention
 * (`PillStatus.kt`'s own `pillStatus` default parameter does the same, for the identical reason).
 * This screen only ever shows during first-run onboarding, before any Settings change is even
 * possible, so "the store's defaults" and "the store's current values" are the same thing at this
 * exact point in the app's lifecycle — but referencing the constants directly (rather than
 * injecting the store and collecting its `Flow`) keeps this step honestly `static`, per the
 * brief's own description of it, with no DB round-trip needed to render a first-run screen.
 */
internal fun defaultRuleSummary(minMag: Double, radiusKm: Double): String =
    "M ≥ ${formatMagnitude(minMag)} within ${formatCount(radiusKm.roundToLong())} km · " +
        "M ≥ 6 worldwide — change anytime in Settings"

/**
 * Task 8 (Plan 3): the real first-run onboarding flow — replaces `AppNav.kt`'s
 * `OnboardingPlaceholder`. Three [HorizontalPager] steps (spec §3.6): what the app does (a big,
 * static [StatusShield] CALM preview), a location ask ([LocationStep] — Task 2's two-path flow
 * reused as INLINE step content rather than a dialog stacked over the pager, per this task's own
 * brief), and a notifications preview ([NotificationsPreviewStep] — the default alert rule,
 * honestly captioned; real permission handling is Plan 4's). A top-right "Skip" (every step) and a
 * bottom "Next"/"Done" button ([OnboardingBottomBar]) both eventually call [onFinish].
 *
 * This composable owns no persistence decision itself — same "screen doesn't own the store write"
 * split every other screen/store pairing in this codebase draws. `AppNav.kt`'s real call site is
 * what actually calls `OnboardingStore.setOnboarded()` before navigating home, exactly like the
 * placeholder it replaces did for its own `onGetStarted` callback.
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { STEP_COUNT })
    val scope = rememberCoroutineScope()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) { page ->
                    when (page) {
                        0 -> WhatItDoesStep(modifier = Modifier.fillMaxSize())
                        1 -> LocationStep(
                            onAdvance = { scope.launch { pagerState.animateScrollToPage(2) } },
                            modifier = Modifier.fillMaxSize(),
                        )
                        else -> NotificationsPreviewStep(modifier = Modifier.fillMaxSize())
                    }
                }
                OnboardingBottomBar(
                    currentPage = pagerState.currentPage,
                    onPrimaryClick = {
                        if (pagerState.currentPage == STEP_COUNT - 1) {
                            onFinish()
                        } else {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        }
                    },
                )
            }
            // Task 8 brief: "Skip (top-right, all steps)" -- unconditional, not gated on
            // currentPage, same windowInsetsPadding(statusBars)+margin convention HomeScreen's own
            // SettingsGearChip/StatusShield use to clear the status bar/notch.
            TextButton(
                onClick = onFinish,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(8.dp),
            ) {
                Text("Skip")
            }
        }
    }
}

/**
 * Step 1 — "what it does," one screen per spec §3.6 ("no carousel-of-five" — this whole step IS
 * that one screen). The [StatusShield] preview reuses the REAL component (not a hand-drawn
 * illustration) scaled up ~1.6x to read as a hero image rather than a functional pill — onboarding's
 * "here's what it looks like" promise is then literally true from day one, not a mockup that can
 * drift from the real UI. `onClick = {}`: [StatusShield]'s own signature requires a click handler,
 * and a static preview has nothing to do when tapped. `radiusKm` is
 * [AlertRuleStore.DEFAULT_RADIUS_KM] for the same "read the store's own constant, not a second
 * independent literal" reason [defaultRuleSummary]'s call site uses.
 *
 * [Modifier.scale] is a `graphicsLayer` visual transform — it does NOT enlarge the space the
 * unscaled [StatusShield] reserves in this [Column]'s layout, only what gets painted on top of
 * that space. The [Spacer]s immediately before/after (not padding baked into the same modifier
 * chain as the scale) are what actually reserve enough real layout room for the visually-larger
 * pill to avoid clipping into the headline text below it.
 */
@Composable
private fun WhatItDoesStep(modifier: Modifier = Modifier) {
    val step = ONBOARDING_STEPS[0]
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(32.dp))
        StatusShield(
            status = PillStatus(kind = PillStatus.Kind.CALM, quake = null),
            nowMillis = 0L,
            onClick = {},
            radiusKm = AlertRuleStore.DEFAULT_RADIUS_KM,
            modifier = Modifier.scale(1.6f),
        )
        Spacer(Modifier.height(40.dp))
        Text(
            text = step.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = step.body,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Step 2 (location ask) — [com.yugma.terrawatch.location.LocationAskDialog]'s "two equal buttons"
 * ask, rendered as INLINE step content instead of a second dialog stacked over the onboarding
 * pager (this task's own brief: "not dialog-over-onboarding if awkward — inline buttons fine"; a
 * modal-over-modal here would be exactly that awkward, since a pager step is already the
 * "screen" — a dialog on top of it would read as two competing surfaces). "Choose city" still
 * opens [CityPickerDialog] itself as a real dialog — that one is a picker sub-flow, not "the ask,"
 * the same distinction Settings' own `PlaceRow` already draws for its identical "Change" button.
 *
 * [onAdvance] moves the pager forward one page without finishing onboarding — wired to BOTH "Use
 * my location" (fired immediately after [LocationRequester.request], not after any grant result:
 * the request is async and this screen has no way to wait on it, matching
 * `LocationAskDialog.kt`'s own dismissButton, which does the identical "request(); onDismiss()"
 * pair) and the explicit "Not now" link the brief calls for ("skippable ('Not now')"). Deliberately
 * NOT wired to "Choose city": [CityPickerDialog]'s own `onDismiss` fires for BOTH a picked city and
 * a cancelled dialog, and auto-advancing on a cancel would skip the step out from under a user who
 * changed their mind.
 */
@Composable
private fun LocationStep(onAdvance: () -> Unit, modifier: Modifier = Modifier) {
    val step = ONBOARDING_STEPS[1]
    var showCityPicker by remember { mutableStateOf(false) }
    val locationRequester = koinInject<LocationRequester>()

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = step.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = step.body,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        // canRequestLocation() is android-only (LocationRequester.kt's own kdoc) -- jvm/wasmJs
        // onboarding offers ONLY "Choose city", same gating LocationAskDialog/Settings' PlaceRow
        // already apply for the identical reason.
        if (canRequestLocation()) {
            Button(
                onClick = { locationRequester.request(); onAdvance() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Use my location")
            }
            Spacer(Modifier.height(12.dp))
        }
        OutlinedButton(onClick = { showCityPicker = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Choose city")
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onAdvance) {
            Text("Not now")
        }
    }
    if (showCityPicker) {
        CityPickerDialog(onDismiss = { showCityPicker = false })
    }
}

/**
 * Step 3 (notifications preview) — spec §3.6's "notification permission ask *with the default rule
 * shown*", honestly narrowed to a PREVIEW per this task's own brief: this app does not ask for a
 * real notification permission yet (Plan 4 owns that), so this step only shows what the default
 * rule WILL be and says so plainly, rather than asking for a permission it cannot yet back with
 * real delivery. [defaultRuleSummary] is computed from [AlertRuleStore]'s own compile-time
 * defaults (see that function's own kdoc for why that's the right source here) and `remember`-ed
 * since it's a pure function of two constants — nothing this composable observes can ever change
 * it mid-composition.
 */
@Composable
private fun NotificationsPreviewStep(modifier: Modifier = Modifier) {
    val step = ONBOARDING_STEPS[2]
    val ruleSummary = remember {
        defaultRuleSummary(minMag = AlertRuleStore.DEFAULT_MIN_MAG, radiusKm = AlertRuleStore.DEFAULT_RADIUS_KM)
    }
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = step.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = step.body,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        // Same card shape as SettingsScreen's own SettingsCard (TerraRadii.card, flat `surface`
        // color, 1.dp tonal elevation) -- one shared "content card" look across every screen in
        // this app, not a bespoke treatment invented just for this step.
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(TerraRadii.card),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
        ) {
            Text(
                text = ruleSummary,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "You'll be asked for permission when alerts arrive in a future update.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Page dots + the shared "Next"/"Done" primary action — [currentPage] alone decides the label
 * (`"Done"` only on the final page), the same contextual-label shape [SettingsScreen]'s "Change"/
 * "Use my location" pair doesn't need but plenty of standard onboarding pagers do. */
@Composable
private fun OnboardingBottomBar(currentPage: Int, onPrimaryClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PageDots(currentPage = currentPage, modifier = Modifier.weight(1f))
        Button(onClick = onPrimaryClick) {
            Text(if (currentPage == STEP_COUNT - 1) "Done" else "Next")
        }
    }
}

/** A plain row of [STEP_COUNT] circles, the current page's drawn larger and in
 * [MaterialTheme.colorScheme.primary] — hand-drawn with a [Box]+[CircleShape] background rather
 * than a dependency, same "no icon/indicator-library dependency in this project" posture every
 * other hand-rolled glyph in this codebase already takes (`StatusShield`'s `CheckGlyph`,
 * `HomeScreen`'s `SettingsGlyph`, `nav/NavIcons.kt`'s tab icons). */
@Composable
private fun PageDots(currentPage: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(STEP_COUNT) { index ->
            val active = index == currentPage
            Box(
                modifier = Modifier
                    .size(if (active) 10.dp else 8.dp)
                    .background(
                        color = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        },
                        shape = CircleShape,
                    ),
            )
        }
    }
}
