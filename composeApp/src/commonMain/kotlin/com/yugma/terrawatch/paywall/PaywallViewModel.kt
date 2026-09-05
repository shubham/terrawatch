package com.yugma.terrawatch.paywall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugma.terrawatch.monetization.EntitlementsProvider
import com.yugma.terrawatch.monetization.PlusOffer
import com.yugma.terrawatch.monetization.PlusPurchases
import com.yugma.terrawatch.monetization.PurchaseOutcome
import com.yugma.terrawatch.monetization.RestoreOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * A `null` [offer] is the ordinary state, not a failure — it is what every build without a
 * configured RevenueCat key shows. [message] is transient user-facing feedback, and `null` means
 * render nothing at all, which is deliberately what a cancelled purchase produces.
 */
data class PaywallUiState(
    val offer: PlusOffer? = null,
    val inFlight: Boolean = false,
    val message: String? = null,
)

/**
 * The button's copy, extracted as a pure function so the rules are unit-tested without a Compose
 * runtime — the same "pure logic gets a unit test" split this codebase already applies to
 * `alertsRowStatusText` and `adSlotVisible`.
 *
 * Branch order is deliberate. [isPlus] is checked first so that someone who already paid never sees
 * a buy button, not even for the instant before an offer resolves — showing "Purchases unavailable"
 * to a paying user would read as though they had lost what they bought.
 */
fun paywallButtonLabel(offer: PlusOffer?, inFlight: Boolean, isPlus: Boolean): String = when {
    isPlus -> "You have Plus"
    inFlight -> "Working…"
    offer == null -> "Purchases unavailable"
    else -> "Unlock Plus — ${offer.formattedPrice}"
}

/** Enabled only when there is something real to buy and nothing already in progress — the
 * `inFlight` term is what stops a double tap opening two store sheets. */
fun paywallButtonEnabled(offer: PlusOffer?, inFlight: Boolean, isPlus: Boolean): Boolean =
    !isPlus && !inFlight && offer != null

/**
 * Backs the paywall. [isPlusActive] is a direct passthrough of [EntitlementsProvider]'s own
 * StateFlow — the same "mirror it live rather than snapshot it" shape `SettingsViewModel` already
 * uses for its identical field — so the screen reacts the moment
 * [com.yugma.terrawatch.monetization.RevenueCatEntitlements]' delegate observes a completed
 * purchase.
 */
class PaywallViewModel(
    private val purchases: PlusPurchases,
    entitlements: EntitlementsProvider,
) : ViewModel() {
    val isPlusActive: StateFlow<Boolean> = entitlements.isPlusActive

    private val _uiState = MutableStateFlow(PaywallUiState())
    val uiState: StateFlow<PaywallUiState> = _uiState

    init {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(offer = purchases.loadOffer())
        }
    }

    fun buy() {
        if (_uiState.value.inFlight) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(inFlight = true, message = null)
            val outcome = purchases.purchase()
            _uiState.value = _uiState.value.copy(
                inFlight = false,
                message = when (outcome) {
                    PurchaseOutcome.Success -> "Thanks — TerraWatch Plus is active."
                    // Renders NOTHING on purpose. Someone who backed out of the store sheet knows
                    // exactly what they did and does not need to be told about it — see
                    // PurchaseOutcome.Cancelled's own kdoc.
                    PurchaseOutcome.Cancelled -> null
                    is PurchaseOutcome.Failed -> outcome.reason
                },
            )
        }
    }

    /**
     * The manual half of the reinstall story. The silent attempt at cold start covers most cases;
     * this is what a user reaches for when it did not, so its "nothing found" answer has to be
     * plain rather than alarming — most people who tap it never bought Plus at all.
     */
    fun restore() {
        if (_uiState.value.inFlight) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(inFlight = true, message = null)
            val outcome = purchases.restore()
            _uiState.value = _uiState.value.copy(
                inFlight = false,
                message = when (outcome) {
                    RestoreOutcome.Restored -> "Your purchase is back."
                    RestoreOutcome.NothingToRestore -> "No previous purchase found on this account."
                    is RestoreOutcome.Failed -> outcome.reason
                },
            )
        }
    }
}
