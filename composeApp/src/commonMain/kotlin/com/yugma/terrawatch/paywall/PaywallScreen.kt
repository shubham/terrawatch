package com.yugma.terrawatch.paywall

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yugma.terrawatch.ui.theme.TerraColors
import com.yugma.terrawatch.ui.theme.TerraRadii
import org.koin.compose.viewmodel.koinViewModel

/**
 * The "TerraWatch Plus" paywall, reached from Settings' Plus row and from the favorite-places gate
 * when a free-tier user hits the one-favorite limit.
 *
 * No longer a stub: [PaywallViewModel] backs it with a real offer, a real purchase, and a real
 * restore. What it renders still depends entirely on whether RevenueCat is configured, and the
 * unconfigured case is the normal one for local and CI builds — `loadOffer()` returns `null` there
 * and [paywallButtonLabel]/[paywallButtonEnabled] resolve to a disabled "Purchases unavailable"
 * button. That is the honest rendering of "there is nothing to sell you right now", and it is
 * deliberately the same code path as a store outage rather than a special case.
 *
 * Both Plus benefits this screen lists are enforced in code today — `adSlotVisible` (core:ads) and
 * [com.yugma.terrawatch.monetization.canAddFavorite] — which is why the third, unbuilt one was
 * removed when the button started charging money. See [PLUS_BENEFITS].
 *
 * `isPlusActive` is a live passthrough of [com.yugma.terrawatch.monetization.EntitlementsProvider]'s
 * StateFlow, so the status line and the button both flip the instant
 * [com.yugma.terrawatch.monetization.RevenueCatEntitlements]' delegate sees a completed purchase —
 * no restart, and no second notion of Plus state living on this screen.
 */
@Composable
fun PaywallScreen(
    onBack: () -> Unit = {},
    viewModel: PaywallViewModel = koinViewModel(),
) {
    val isPlusActive by viewModel.isPlusActive.collectAsState()
    val ui by viewModel.uiState.collectAsState()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 16.dp),
        ) {
            PaywallHeader(onBack = onBack)
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (isPlusActive) "Active" else "You're on the Free tier",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(16.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(TerraRadii.card),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PLUS_BENEFITS.forEach { benefit -> BenefitRow(benefit) }
                }
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = viewModel::buy,
                enabled = paywallButtonEnabled(ui.offer, ui.inFlight, isPlusActive),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(paywallButtonLabel(ui.offer, ui.inFlight, isPlusActive))
            }
            Spacer(Modifier.height(4.dp))
            // Mandatory rather than polish: this is the only way a user recovers Plus when the
            // silent cold-start attempt did not. The purchase belongs to their Google account, but
            // the anonymous App User ID that knew about it dies with an uninstall.
            TextButton(
                onClick = viewModel::restore,
                enabled = !ui.inFlight,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Restore purchases")
            }
            // `null` renders nothing at all, which is exactly what a cancelled purchase produces.
            ui.message?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PaywallHeader(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            BackChevronGlyph(
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.width(24.dp).height(24.dp),
            )
        }
        Text(
            text = "TerraWatch Plus",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/** A plain "‹" chevron, hand-drawn on [Canvas] — duplicated from `SettingsScreen.kt`'s own private
 * `BackChevronGlyph` rather than exported across files for one small glyph, same "duplicate a
 * trivial UI atom rather than force a cross-file export" convention `DetailSheet.kt`'s own
 * `SectionEyebrow` kdoc already establishes for an identically small case. */
@Composable
private fun BackChevronGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.62f, size.height * 0.2f)
            lineTo(size.width * 0.32f, size.height * 0.5f)
            lineTo(size.width * 0.62f, size.height * 0.8f)
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = size.width * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** The Plus benefits list, reduced to the two that are REAL. Ad removal is enforced by
 * `adSlotVisible` (core:ads); unlimited favorite places by
 * [com.yugma.terrawatch.monetization.canAddFavorite] (core:monetization), the free tier's
 * one-favourite limit. `internal` so
 * [PaywallScreenTest][com.yugma.terrawatch.paywall.PaywallScreenTest] can pin the exact copy
 * without a Compose runtime.
 *
 * Plus purchase flow (2026-09-05): "Custom alert rules (coming soon)" was DROPPED. It was honest
 * while this screen's button was permanently disabled — a roadmap note under an offer nobody could
 * accept. Once the button charges real money the same line is a paid promise for a feature that
 * does not exist. See [PaywallScreenTest]'s own kdoc for the full reasoning.
 */
internal val PLUS_BENEFITS = listOf(
    "Remove ads",
    "Unlimited favorite places",
)

@Composable
private fun BenefitRow(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "✓",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = TerraColors.Safe,
            modifier = Modifier.width(24.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            letterSpacing = 0.sp,
        )
    }
}
