package com.yugma.terrawatch.ads

import android.content.Context
import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

/**
 * Google's own official AdMob TEST banner unit id (developers.google.com/admob/android/test-ads) —
 * used whenever `composeApp/monetization.properties`'s `ADMOB_BANNER_UNIT` is absent/blank (this
 * repo's real state throughout Task 6: no AdMob account exists yet, a USER-GATED prerequisite named
 * in the plan's own Global Constraints). Real ids swap in at Task 8 — see this file's own kdoc.
 * `internal` — only [readBannerUnitId] below needs it.
 */
internal const val TEST_BANNER_AD_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"

private const val ADMOB_BANNER_UNIT_METADATA_KEY = "com.yugma.terrawatch.ADMOB_BANNER_UNIT"

// Plan 5 Task 3: the fade-in-on-first-fill duration — no existing "content appears after loading"
// precedent in this codebase to match exactly (SkeletonCard's shimmer/FeedSheet's live-dot pulse are
// both *looping* animations, a different shape); 300ms is a plain, ordinary one-shot fade-in
// duration, a deliberate pick rather than a copied convention.
private const val AD_FADE_IN_DURATION_MS = 300

/**
 * Task 6 (Plan 4): the real android actual — a `play-services-ads` anchored ADAPTIVE banner (spec
 * §3.2/§8: "anchored adaptive banner directly above the nav bar"), sized to the device's actual
 * current width via `AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize` — Google's own
 * documented recipe for this exact ad format, fed `LocalConfiguration.current.screenWidthDp`
 * directly (this app is 100% Compose, so there's no legacy `DisplayMetrics`/density dance needed to
 * get a dp width the way a View-based caller would).
 *
 * Ad unit id resolution: reads [ADMOB_BANNER_UNIT_METADATA_KEY] off this app's OWN merged manifest
 * meta-data (`composeApp/build.gradle.kts` writes it from `composeApp/monetization.properties` via
 * a manifest placeholder — see that file's own kdoc) rather than taking it as a parameter, so this
 * composable's signature stays the plain shape the `expect` declares — no composeApp-shaped config
 * plumbing leaks into this module's public API. Blank/absent (this repo's real state throughout
 * Task 6) falls back to [TEST_BANNER_AD_UNIT_ID].
 *
 * [AdRevenueTracker]'s `onAdImpression` hook below is a documented no-op stub, not yet wired to
 * RevenueCat's own `purchases-android` `AdTracker`/`loadAndTrack` ad-monetization surface — that
 * integration needs a configured RevenueCat account (Task 8); see that object's own kdoc.
 *
 * **Plan 5 Task 3 rewrite (user dogfooding: "ads appearing causes glitchy experience") — replaces
 * BOTH the Task 6 fix round's pause/resume wiring AND its own "accepted v1 simplification" note.**
 * See [BannerAdSlot][com.yugma.terrawatch.ads.BannerAdSlot]'s (`expect`) own kdoc for the two named
 * glitches and the high-level split of responsibility between this file and `AppNav.kt`. The
 * mechanics, in order of appearance below:
 *
 * 1. **`hasLoadedOnce`, declared BEFORE `adView`'s own `remember` block** so that block's
 *    `AdListener.onAdLoaded()` closure captures THIS (correctly-scoped) instance — keyed on the SAME
 *    `context` `adView` itself is keyed on (not keyless), so a context change (this file's only
 *    AdView-recreation trigger, unchanged from Task 6) resets this alongside a genuinely fresh,
 *    not-yet-loaded `AdView` instead of leaking a stale "already loaded" reading onto it.
 * 2. **`adView`'s own construction is otherwise UNCHANGED from Task 6** — one instance per distinct
 *    `context`, same adaptive-banner sizing call, same TEST-unit-id fallback, `loadAd()` still fired
 *    immediately. The one addition is `onAdLoaded` flipping `hasLoadedOnce` — the fade-in's trigger.
 * 3. **`adaptiveHeightDp`** is read back off the just-configured `AdView` itself
 *    (`BaseAdView.getAdSize()`, confirmed present against the real resolved
 *    `play-services-ads-api:25.4.0` artifact via `javap`) rather than recomputed independently, so
 *    the reserved height below can never disagree with whatever size THIS `AdView` was actually
 *    built with. Fed into [adSlotReservedHeightDp] (`core:ads` commonMain, TDD'd) alongside
 *    [visible] — that function is a plain `if (eligible) adaptiveHeightDp else 0`, so the box below
 *    is already at its FINAL size before `loadAd()`'s result ever arrives. THE fix for glitch 1
 *    (layout jump on fill).
 * 4. **The fade-in `animateFloatAsState`** targets `1f` exactly once `hasLoadedOnce` ever flips true
 *    and stays there for the rest of this `AdView`'s life — no re-fade on a later detail-close
 *    reveal. [reducedMotion] swaps the [tween] for a [snap] (same "instant instead of animated,
 *    never skip the state change itself" shape [StatusShield]'s own `reducedMotion` parameter
 *    already establishes in `core:ui`). `visible` deliberately does NOT gate this target: the
 *    reserved `Box` below is 0dp tall and `clipToBounds()` whenever `visible` is false, which
 *    already hides this content regardless of its alpha — letting the alpha resolve in the
 *    background means the very first thing a user sees when the slot reopens is already fully
 *    settled, not a second fade replaying. `Modifier.graphicsLayer { alpha = ... }` (not
 *    `Modifier.alpha(state.value)`) reads the animated `State<Float>` at the DRAW phase only, same
 *    reasoning `FeedSheet.kt`'s `LiveDot` pulse already established: this composable itself never
 *    needs to recompose for the animation to play.
 * 5. **Pause/resume now reacts to TWO independent signals ANDed together**, not just the host
 *    Activity's own lifecycle: `visible` false (the detail sheet open, per `AppNav.kt`'s call site)
 *    must ALSO pause this `AdView` even while the Activity stays fully resumed, so a hidden banner
 *    never keeps refreshing/counting impressions behind the sheet — the same AdMob "pause while
 *    off-screen" guidance Task 6 already cited, now covering a second reason to be off-screen.
 *    `lifecycleResumed` is tracked as plain `State` (the observer only ever sets it, never calls
 *    `pause`/`resume` directly) specifically so both signals combine in ONE place: two independent
 *    effects each calling `resume()`/`pause()` off their own signal could otherwise race — e.g.
 *    backgrounding the app while the detail sheet happens to be open, then returning to the
 *    foreground with that sheet still open, would have the Activity's own `ON_RESUME` incorrectly
 *    call `resume()` on an `AdView` that's still supposed to be hidden.
 * 6. **The reserved-height `Box` is now the ONLY thing `visible` collapses** — the
 *    `AndroidView`/`AdView` inside is composed continuously for as long as this composable itself
 *    stays mounted (`AppNav.kt` decides that, gated on Plus/onboarding only — never on `visible`
 *    itself). `onRelease = { it.destroy() }` therefore now only fires on a REAL end-of-life (Plus
 *    purchased, onboarding not finished — both unmount this composable structurally from `AppNav.kt`
 *    's call site), not on every detail-sheet open. THE fix for glitch 2 (reload jank on hide -> show).
 *    `clipToBounds()` is defensive belt-and-suspenders, not load-bearing: Compose's own constraint
 *    propagation already forces the `AndroidView`'s native measure pass to 0 height whenever this
 *    `Box` resolves to 0dp (an entirely ordinary Android measure/layout case), kept in case a future
 *    AdMob SDK version's own internal drawing ever tries to paint past its measured bounds.
 *    **Device-verification-pending** (98bc1cd8 not connected this session, per this task's own
 *    Global Constraints): a 0-height squeeze of a REAL native `AdView` — as opposed to a plain
 *    Compose-only child — has not been watched happen on this specific OEM build; nothing in
 *    `play-services-ads`'s own public contract suggests it should misbehave, but this is the one
 *    genuinely new runtime shape this task introduces that the existing device matrix never
 *    exercised.
 */
@Composable
actual fun BannerAdSlot(visible: Boolean, reducedMotion: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val adWidthDp = LocalConfiguration.current.screenWidthDp

    var hasLoadedOnce by remember(context) { mutableStateOf(false) }

    val adView = remember(context) {
        val bannerUnitId = readBannerUnitId(context)
        AdView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, adWidthDp))
            adUnitId = bannerUnitId
            adListener = object : AdListener() {
                override fun onAdImpression() {
                    AdRevenueTracker.onAdImpression(bannerUnitId)
                }
                override fun onAdLoaded() {
                    hasLoadedOnce = true
                }
            }
            loadAd(AdRequest.Builder().build())
        }
    }

    // `BaseAdView.getAdSize()` resolves as nullable in Kotlin (this exact resolved artifact
    // annotates it `@Nullable` — the SDK models "no size set yet," a real state for a bare
    // `AdView()` an XML-inflating caller could leave unconfigured). `adView` above always calls
    // `setAdSize(...)` immediately at construction, so the `?: 0` fallback should never actually
    // trigger in practice — kept as a graceful "reserve nothing" degradation rather than a `!!`
    // that would crash on the one path the type system can't rule out from here.
    val adaptiveHeightDp = remember(adView) { adView.adSize?.height ?: 0 }
    val reservedHeightDp = adSlotReservedHeightDp(eligible = visible, adaptiveHeightDp = adaptiveHeightDp)

    val animatedAlpha = animateFloatAsState(
        targetValue = if (hasLoadedOnce) 1f else 0f,
        animationSpec = if (reducedMotion) snap() else tween(durationMillis = AD_FADE_IN_DURATION_MS),
        label = "bannerAdFadeIn",
    )

    val lifecycleOwner = LocalLifecycleOwner.current
    var lifecycleResumed by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> lifecycleResumed = false
                Lifecycle.Event.ON_RESUME -> lifecycleResumed = true
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(visible, lifecycleResumed, adView) {
        if (visible && lifecycleResumed) adView.resume() else adView.pause()
    }

    Box(
        modifier = modifier
            .height(reservedHeightDp.dp)
            .clipToBounds(),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = animatedAlpha.value },
            factory = { adView },
            onRelease = { it.destroy() },
        )
    }
}

private fun readBannerUnitId(context: Context): String {
    val metaData = context.packageManager
        .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        .metaData
    return metaData?.getString(ADMOB_BANNER_UNIT_METADATA_KEY)?.takeIf { it.isNotBlank() } ?: TEST_BANNER_AD_UNIT_ID
}
