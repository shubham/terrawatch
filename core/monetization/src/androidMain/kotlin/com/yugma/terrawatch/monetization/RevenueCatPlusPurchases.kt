package com.yugma.terrawatch.monetization

import com.revenuecat.purchases.kmp.Purchases
import com.revenuecat.purchases.kmp.ktx.awaitOfferings
import com.revenuecat.purchases.kmp.ktx.awaitPurchase
import com.revenuecat.purchases.kmp.ktx.awaitRestore
import com.revenuecat.purchases.kmp.models.Package
import com.revenuecat.purchases.kmp.models.PurchasesException
import com.revenuecat.purchases.kmp.models.PurchasesTransactionException

/**
 * The real, android-only [PlusPurchases], over purchases-kmp 3.5.0.
 *
 * Only ever constructed once [revenueCatKeyIsConfigured] has already passed (see
 * `KoinBootstrap.android.kt`), which also guarantees [RevenueCatEntitlements] has already run
 * `Purchases.configure` — so [Purchases.sharedInstance] is safe to touch here without configuring a
 * second time. Configuring twice would reset the SDK's state mid-session.
 *
 * **Every signature below was verified with `javap` against the resolved
 * `purchases-kmp-core-android` / `purchases-kmp-models-android` 3.5.0 AARs, not taken from the
 * docs.** This repo has been caught out once already on exactly this SDK — `awaitCustomerInfo`
 * turned out to be a `ktx` extension rather than a member of `Purchases`, which the documentation
 * never hinted at (see [RevenueCatEntitlements]' own kdoc). The real chain confirmed for this file:
 * `Offerings.current: Offering?` -> `Offering.availablePackages: List<Package>` ->
 * `Package.storeProduct: StoreProduct` -> `StoreProduct.price: Price` -> `Price.formatted: String`.
 *
 * **One inheritance detail here is load-bearing, and is the reason the catch blocks are ordered the
 * way they are:** `PurchasesTransactionException` *extends* `PurchasesException`. A
 * `catch (PurchasesException)` placed first would silently swallow every transaction exception too,
 * taking `userCancelled` with it — and the user who simply backed out of the store sheet would be
 * shown a purchase failure. The narrow catch must come first.
 */
class RevenueCatPlusPurchases : PlusPurchases {

    /**
     * The one thing TerraWatch sells, or `null` if the store has nothing to offer right now.
     *
     * Takes the first package of the current offering rather than looking one up by identifier.
     * This app has exactly one product, so "the first package of the current offering" *is* the
     * whole catalogue — and a package-identifier lookup would add a second string that has to match
     * the RevenueCat dashboard exactly (alongside the entitlement id) for zero benefit, with a
     * silent empty paywall as the failure mode when it drifts. `terrawatch_plus` is expected to sit
     * in the `lifetime` package slot, being a one-time non-consumable, but reading the list rather
     * than that specific accessor means the dashboard can use any package type without this
     * breaking.
     */
    private suspend fun currentPackage(): Package? = try {
        Purchases.sharedInstance.awaitOfferings().current?.availablePackages?.firstOrNull()
    } catch (e: PurchasesException) {
        null
    }

    /**
     * `null` on every unhappy path — no offering configured, an empty offering, or an unreachable
     * store. They collapse deliberately: the paywall's honest answer to all of them is the same
     * disabled button, and distinguishing them would only let it display a price it cannot charge.
     */
    override suspend fun loadOffer(): PlusOffer? =
        currentPackage()?.let { PlusOffer(formattedPrice = it.storeProduct.price.formatted) }

    /**
     * Note what this deliberately does NOT do: it never writes entitlement state on success.
     * [RevenueCatEntitlements]' `PurchasesDelegate` observes `onCustomerInfoUpdated` and flips
     * `isPlusActive` for the whole app, so exactly one place decides whether Plus is active.
     * Setting it here as well would create a second source of truth that could disagree with the
     * first — and the two would diverge precisely in the case that matters, a purchase that the
     * store later voids.
     */
    override suspend fun purchase(): PurchaseOutcome {
        val pkg = currentPackage()
            ?: return PurchaseOutcome.Failed("Couldn't reach the store. Please try again.")

        return try {
            Purchases.sharedInstance.awaitPurchase(pkg)
            PurchaseOutcome.Success
        } catch (e: PurchasesTransactionException) {
            // MUST stay above the PurchasesException branch — it is a subclass of it. See this
            // class's own kdoc: reversing these tells someone who cancelled that they failed.
            if (e.userCancelled) PurchaseOutcome.Cancelled
            else PurchaseOutcome.Failed("That purchase didn't go through. You haven't been charged.")
        } catch (e: PurchasesException) {
            // `awaitPurchase` declares only the transaction subtype, so reaching here means the SDK
            // threw something its own signature did not promise. Degrade rather than crash, per this
            // codebase's standing convention for store and network calls.
            PurchaseOutcome.Failed("That purchase didn't go through. You haven't been charged.")
        }
    }

    /**
     * The reinstall path. `awaitRestore` asks the store what this Google account owns and re-attaches
     * it to the current App User ID — which, after a reinstall, is a freshly generated anonymous id
     * that has never seen the purchase.
     *
     * This only ever finds anything while `terrawatch_plus` is configured NON-CONSUMABLE in the
     * RevenueCat dashboard. Configured consumable, RevenueCat consumes the purchase, and since Play
     * Billing Client 8 removed querying of consumed one-time purchases it is then gone permanently,
     * with no account system in this app to recover it from. That configuration is verified by an
     * actual uninstall/reinstall on a device before release, never assumed.
     */
    override suspend fun restore(): RestoreOutcome = try {
        val info = Purchases.sharedInstance.awaitRestore()
        if (info.entitlements[PLUS_ENTITLEMENT_IDENTIFIER]?.isActive == true) RestoreOutcome.Restored
        else RestoreOutcome.NothingToRestore
    } catch (e: PurchasesException) {
        RestoreOutcome.Failed("Couldn't reach the store. Please try again.")
    }
}
