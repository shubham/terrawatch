package com.yugma.terrawatch.ads

import com.google.android.gms.ads.AdValue
import com.revenuecat.purchases.kmp.ExperimentalRevenueCatApi
import com.revenuecat.purchases.kmp.Purchases
import com.revenuecat.purchases.kmp.models.AdFormat
import com.revenuecat.purchases.kmp.models.AdMediatorName
import com.revenuecat.purchases.kmp.models.AdRevenueData
import com.revenuecat.purchases.kmp.models.AdRevenuePrecision

/** The `placement` this app reports for its single ad slot — the anchored banner above the nav bar.
 * TerraWatch has exactly one, so this is a constant rather than a parameter; a second slot would
 * make it one. */
private const val BANNER_PLACEMENT = "home-anchored-banner"

/** Reported as `networkName` when AdMob doesn't name a mediation adapter, which is the normal case
 * here: this app serves AdMob directly with no mediation configured. */
private const val DIRECT_ADMOB_NETWORK = "AdMob"

/**
 * Reports AdMob ad revenue into RevenueCat, so ad income and IAP income land in one dashboard.
 *
 * **Two corrections to what this file previously claimed**, both established by reading the
 * resolved 3.5.0 AAR rather than the SDK docs:
 *
 * 1. It said the integration needs `purchases-android` 8.0+. Not true for the version this app
 *    already ships — `Purchases.adTracker` is present in purchases-kmp 3.5.0.
 * 2. It said the surface is `@ExperimentalPreviewRevenueCatPurchasesAPI`-gated. It IS opt-in gated,
 *    but under [ExperimentalRevenueCatApi] — a different annotation, which is why guessing the name
 *    would not have compiled. Shipping an opt-in API is a deliberate choice here: the whole call is
 *    defensive (see below), and losing ad-revenue reporting if RevenueCat changes the surface costs
 *    this app analytics, never a user-visible feature.
 *
 * The real obstacle was neither of those, and it is why the call site had to move. [AdRevenueData]
 * requires the revenue amount, currency and precision, and `AdListener.onAdImpression()` — which
 * this object used to be called from — carries none of them. Those values exist only on AdMob's
 * `OnPaidEventListener`, so that is what drives this now.
 *
 * Guarded by [Purchases.isConfigured] and then wrapped in [runCatching]. The guard matters more than
 * it looks: without a RevenueCat key the SDK is never configured and `sharedInstance` throws, and
 * this runs on every paid ad event — so the unguarded version would throw and catch continuously
 * through every local and CI build. The `runCatching` then covers everything the guard cannot,
 * because an analytics report is never worth crashing a user's session over.
 */
@OptIn(ExperimentalRevenueCatApi::class)
object AdRevenueTracker {

    /**
     * @param impressionId AdMob's own response id for this impression, when available. Passed
     *   through rather than synthesised: RevenueCat uses it to de-duplicate, and a made-up value
     *   would defeat that silently.
     * @param networkName the mediation adapter that actually filled the ad, or `null` for a direct
     *   AdMob fill.
     */
    fun onAdPaid(
        adUnitId: String,
        valueMicros: Long,
        currencyCode: String,
        precisionType: Int,
        impressionId: String?,
        networkName: String?,
    ) {
        if (!Purchases.isConfigured) return
        runCatching {
            Purchases.sharedInstance.adTracker.trackAdRevenue(
                AdRevenueData(
                    networkName = networkName ?: DIRECT_ADMOB_NETWORK,
                    mediatorName = AdMediatorName.AD_MOB,
                    adFormat = AdFormat.BANNER,
                    placement = BANNER_PLACEMENT,
                    adUnitId = adUnitId,
                    impressionId = impressionId.orEmpty(),
                    revenueMicros = valueMicros,
                    currency = currencyCode,
                    precision = precisionFrom(precisionType),
                ),
            )
        }
    }
}

/**
 * Maps AdMob's precision int onto RevenueCat's own precision enum.
 *
 * The constants live on the `AdValue.PrecisionType` ANNOTATION interface, not on `AdValue` itself
 * (`AdValue` exposes only `getPrecisionType(): Int`), and AdMob's own published documentation lists
 * the four names without their numeric values. Both facts were established from the resolved
 * play-services-ads-api 25.4.0 bytecode: UNKNOWN=0, ESTIMATED=1, PUBLISHER_PROVIDED=2, PRECISE=3.
 * Referencing the named constants rather than those literals is what keeps this correct if Google
 * ever renumbers them.
 *
 * Unrecognised values fall through to [AdRevenuePrecision.UNKNOWN] rather than throwing: this is an
 * `int` from another SDK that may add constants, and over-claiming precision on revenue data is
 * worse than admitting we do not know.
 */
@OptIn(ExperimentalRevenueCatApi::class)
private fun precisionFrom(precisionType: Int): AdRevenuePrecision = when (precisionType) {
    AdValue.PrecisionType.PRECISE -> AdRevenuePrecision.EXACT
    AdValue.PrecisionType.PUBLISHER_PROVIDED -> AdRevenuePrecision.PUBLISHER_DEFINED
    AdValue.PrecisionType.ESTIMATED -> AdRevenuePrecision.ESTIMATED
    else -> AdRevenuePrecision.UNKNOWN
}
