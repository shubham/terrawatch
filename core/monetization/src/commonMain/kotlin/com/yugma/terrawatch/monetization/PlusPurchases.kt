package com.yugma.terrawatch.monetization

/**
 * The purchase FLOW for TerraWatch Plus — deliberately a separate interface from
 * [EntitlementsProvider], which stays read-only ("is Plus active right now?") exactly as its own
 * kdoc always intended.
 *
 * Two interfaces because they have two different shapes. Entitlement state is observed continuously
 * by three unrelated consumers (`adSlotVisible`, Settings' Plus row, [canAddFavorite]); purchasing
 * is a one-shot user action reached only from the paywall. Folding them together would put a
 * `purchase()` on an interface that three read-only call sites inject, and force every no-op
 * implementation to grow a billing surface it will never use.
 *
 * Every function suspends and returns an outcome rather than throwing. The android implementation
 * wraps an SDK whose calls all throw, and this codebase's standing convention is that store and
 * network calls degrade gracefully rather than crash the app — see `GdeltClient`, `UsgsApi`, and
 * [RevenueCatEntitlements]' own defensive `runCatching`.
 */
interface PlusPurchases {
    /**
     * The current purchasable offer, or `null` when nothing is purchasable — no configured
     * RevenueCat key, no offering on the dashboard, or the store is unreachable.
     *
     * All three collapse to `null` on purpose: the paywall's honest response to every one of them is
     * identical — show a disabled button, never a price this app cannot actually charge.
     */
    suspend fun loadOffer(): PlusOffer?

    /** Launches the store's purchase sheet and waits for the user to either finish or back out. */
    suspend fun purchase(): PurchaseOutcome

    /**
     * Re-attaches an already-owned purchase to this install.
     *
     * This is the reinstall path, and the reason it matters: a Play one-time purchase belongs to the
     * user's Google account, but RevenueCat's anonymous App User ID lives in app data and dies with
     * an uninstall. Restore is what reconnects the two. It only ever finds the purchase while
     * `terrawatch_plus` is configured NON-CONSUMABLE — consumed one-time purchases cannot be queried
     * back at all since Play Billing Client 8, and this app has no account system to recover from.
     */
    suspend fun restore(): RestoreOutcome
}

/**
 * [formattedPrice] is the STORE's own localized string ("₹299", "$3.99") — never assembled here.
 * The store knows the user's country, currency and formatting conventions; this app knows none of
 * them, and a guessed price that disagrees with what Play actually charges at the sheet is both a
 * broken promise and a policy problem.
 */
data class PlusOffer(val formattedPrice: String)

sealed interface PurchaseOutcome {
    data object Success : PurchaseOutcome

    /**
     * The user dismissed the store sheet.
     *
     * Deliberately NOT a [Failed]: backing out of a purchase is a completely normal choice, and
     * telling someone something went wrong when they simply changed their mind is untrue and
     * faintly hostile. The paywall renders nothing at all for this outcome.
     */
    data object Cancelled : PurchaseOutcome

    /** [reason] is user-facing copy, not a stack trace — it is shown on the paywall verbatim. */
    data class Failed(val reason: String) : PurchaseOutcome
}

sealed interface RestoreOutcome {
    data object Restored : RestoreOutcome

    /**
     * The store reported no owned purchase to restore. An ordinary answer rather than an error: the
     * common case is someone tapping Restore who never bought Plus in the first place.
     */
    data object NothingToRestore : RestoreOutcome

    data class Failed(val reason: String) : RestoreOutcome
}

/**
 * The [PlusPurchases] for every build with no configured RevenueCat integration — which is most of
 * them: all jvm/wasmJs builds (Android-only runtime scope directive), plus Android itself whenever
 * `composeApp/monetization.properties`' `REVENUECAT_API_KEY` is absent or blank. That covers every
 * local build and every CI build, so this object's behaviour is the app's normal state, not a
 * fallback nobody sees.
 *
 * Mirrors [AlwaysFreeEntitlements] exactly, and is selected by the SAME [revenueCatKeyIsConfigured]
 * gate — one key, one decision, two providers. Gating them independently would let them disagree:
 * a build that reads entitlements from RevenueCat but refuses to sell, or a paywall offering a
 * purchase the entitlement side would never observe.
 *
 * [restore] returns [RestoreOutcome.NothingToRestore] rather than a failure on purpose. The silent
 * first-launch restore attempt runs on every cold start including these builds, and with no store
 * integration there is genuinely nothing owned to restore — reporting a failure would manufacture
 * an error out of an ordinary, expected state.
 */
object UnavailablePlusPurchases : PlusPurchases {
    override suspend fun loadOffer(): PlusOffer? = null

    override suspend fun purchase(): PurchaseOutcome =
        PurchaseOutcome.Failed("Purchases aren't available in this build.")

    override suspend fun restore(): RestoreOutcome = RestoreOutcome.NothingToRestore
}
