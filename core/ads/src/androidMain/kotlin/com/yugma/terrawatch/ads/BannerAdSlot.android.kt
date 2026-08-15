package com.yugma.terrawatch.ads

import android.content.Context
import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
 * composable's signature stays the plain 2-param shape the `expect` declares — no composeApp-shaped
 * config plumbing leaks into this module's public API. Blank/absent (this repo's real state
 * throughout Task 6) falls back to [TEST_BANNER_AD_UNIT_ID].
 *
 * `visible = false`: renders nothing at all (see the `expect` declaration's own kdoc for why a
 * fully-collapsed slot, not a reserved-but-blank one, is the deliberate reading of spec §8's
 * "hidden" rule) — the `AndroidView`/`AdView` below is only ever composed while `visible = true`,
 * which also means re-showing the slot (Plus lapsing, detail sheet closing, onboarding finishing)
 * always issues a FRESH `loadAd()` rather than keeping one `AdView` alive and only toggling its
 * Android `View` visibility. Accepted v1 simplification, documented rather than silently shipped:
 * harmless for TEST ads (no real inventory/rate cost), worth revisiting once Task 8's real ad
 * inventory makes a reload-per-reveal a genuine cost concern.
 *
 * [AdRevenueTracker]'s `onAdImpression` hook below is a documented no-op stub, not yet wired to
 * RevenueCat's own `purchases-android` `AdTracker`/`loadAndTrack` ad-monetization surface — that
 * integration needs a configured RevenueCat account (Task 8); see that object's own kdoc.
 *
 * **Fix round (Plan 4 Task 6 review): AdView lifecycle now wired, not left to GC.** The original
 * version handed `AndroidView` a bare `factory` and nothing else — the created [AdView] was never
 * paused/resumed with the host `Activity`/screen (AdMob's own documented guidance: pause the ad
 * while its screen isn't visible, resume it when it is again, to stop background ad
 * refresh/animation work and its battery/network cost) and never `destroy()`-ed when this composable
 * left the tree either (`visible` flipping back to `false` unmounts this whole `AndroidView`, per
 * this file's own kdoc above — every one of THOSE moments used to just abandon the native `AdView`
 * for the garbage collector instead of releasing its native ad resources deterministically).
 *
 * Two independent mechanisms, each covering the piece the other doesn't:
 * - **`onRelease` (not a `DisposableEffect`-only fallback)**: confirmed present on this exact
 *   resolved `androidx.compose.ui:ui-android:1.9.0` artifact's real `AndroidView` overload (checked
 *   directly against that artifact's own sources jar, matching this codebase's established
 *   "verify against the real resolved dependency" discipline — e.g. `QuakeMap.android.kt`'s own
 *   `javap`-against-bytecode precedent) — well past the 1.7.0 release that first added it, so no
 *   `DisposableEffect`-based substitute is needed here. Compose invokes it exactly when this
 *   `AndroidView` is released from composition for good, which is the correct "call `destroy()`
 *   now" signal.
 * - **`DisposableEffect` + [LifecycleEventObserver]** for the SEPARATE pause/resume concern
 *   `onRelease` doesn't cover: mirrors `LocationPermissionCompose.kt`/
 *   `NotificationPermissionCompose.kt`'s own established `LocalLifecycleOwner` + `ON_RESUME`-observer
 *   idiom (same `androidx-lifecycle-runtime-compose` KMP artifact, now also a `core:ads` androidMain
 *   dependency — see this module's own `build.gradle.kts`), extended here to react to `ON_PAUSE` too.
 *
 * The [AdView] itself moves out of `AndroidView`'s `factory` lambda into a `remember(context)` above
 * it, so the SAME instance is reachable from both the `DisposableEffect` (to call `pause`/`resume`
 * on) and `AndroidView`'s own `factory = { adView }` — `factory` still only ever runs once per
 * composable-instance lifetime either way, so this is not a behavior change versus the original
 * one-time construction, just a hoist that makes the instance nameable.
 */
@Composable
actual fun BannerAdSlot(visible: Boolean, modifier: Modifier) {
    if (!visible) return
    val context = LocalContext.current
    val adWidthDp = LocalConfiguration.current.screenWidthDp
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
            }
            loadAd(AdRequest.Builder().build())
        }
    }

    // Fix round: pause/resume the SAME AdView instance alongside the host screen's own
    // lifecycle — standard AdMob guidance, not this app's own invention (see this function's
    // own kdoc above).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, adView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> adView.pause()
                Lifecycle.Event.ON_RESUME -> adView.resume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(
        modifier = modifier.wrapContentHeight(),
        factory = { adView },
        onRelease = { it.destroy() },
    )
}

private fun readBannerUnitId(context: Context): String {
    val metaData = context.packageManager
        .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        .metaData
    return metaData?.getString(ADMOB_BANNER_UNIT_METADATA_KEY)?.takeIf { it.isNotBlank() } ?: TEST_BANNER_AD_UNIT_ID
}
