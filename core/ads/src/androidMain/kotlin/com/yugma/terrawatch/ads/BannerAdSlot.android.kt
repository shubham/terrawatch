package com.yugma.terrawatch.ads

import android.content.Context
import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
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
 */
@Composable
actual fun BannerAdSlot(visible: Boolean, modifier: Modifier) {
    if (!visible) return
    val adWidthDp = LocalConfiguration.current.screenWidthDp
    AndroidView(
        modifier = modifier.wrapContentHeight(),
        factory = { context ->
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
        },
    )
}

private fun readBannerUnitId(context: Context): String {
    val metaData = context.packageManager
        .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        .metaData
    return metaData?.getString(ADMOB_BANNER_UNIT_METADATA_KEY)?.takeIf { it.isNotBlank() } ?: TEST_BANNER_AD_UNIT_ID
}
