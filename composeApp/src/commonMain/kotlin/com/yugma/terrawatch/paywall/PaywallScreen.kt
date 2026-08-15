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
import com.yugma.terrawatch.monetization.EntitlementsProvider
import com.yugma.terrawatch.ui.theme.TerraColors
import com.yugma.terrawatch.ui.theme.TerraRadii
import org.koin.compose.koinInject

/**
 * Task 6 (Plan 4) STUB — spec §8's "TerraWatch Plus" paywall, reached from Settings' new "TerraWatch
 * Plus" row. Real `purchases-kmp-ui` Compose Multiplatform paywall wiring is Task 8's job ("Until
 * accounts exist: full implementation against TEST ids... real ids = config swap task 8" — this
 * task's own dispatch): there is no RevenueCat account/product to power a real paywall against yet
 * (a USER-GATED prerequisite, plan's own Global Constraints), so this screen is a static benefits
 * list + an honestly-disabled buy button rather than a placeholder that pretends to work.
 *
 * [isPlusActive] (mirrored live from [EntitlementsProvider], same "direct StateFlow passthrough"
 * shape `SettingsViewModel`'s own identical field already uses) is the one thing that's genuinely
 * real here — always `false` throughout Task 6 ([com.yugma.terrawatch.monetization.AlwaysFreeEntitlements]
 * is what's actually live), but wired honestly rather than hardcoded, so Task 8's real purchase flow
 * flips this screen's own status line with zero change to this file.
 *
 * Plus-gates themselves (unlimited saved places, custom alert rules — the other two benefits listed
 * below) are NOT enforced anywhere in this app yet, regardless of [isPlusActive]'s value — the free
 * tier keeps everything until Task 8 makes Plus purchasable; this screen only ever describes what
 * Plus WILL unlock, per spec §8's own benefits list.
 */
@Composable
fun PaywallScreen(
    onBack: () -> Unit = {},
    entitlementsProvider: EntitlementsProvider = koinInject(),
) {
    val isPlusActive by entitlementsProvider.isPlusActive.collectAsState()

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
                fontWeight = FontWeight.Bold,
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
            // Task 6 STUB: real purchase state (price, product, purchase()/restorePurchases()
            // callbacks) is Task 8's job — see this file's own kdoc. Disabled per this task's own
            // dispatch ("disabled buy button when RC key absent") rather than omitted entirely, so
            // the screen's shape/layout is already final and Task 8 only needs to enable it.
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Text("Purchases available soon")
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
            fontWeight = FontWeight.Bold,
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

/** Spec §8's own 3-item Plus benefits list, verbatim: "removes ads, multiple saved places... custom
 * alert rules." `internal` so [PaywallScreenTest][com.yugma.terrawatch.paywall.PaywallScreenTest]
 * can pin the exact copy without a Compose runtime. */
internal val PLUS_BENEFITS = listOf(
    "Remove ads",
    "Unlimited saved places",
    "Custom alert rules",
)

@Composable
private fun BenefitRow(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "✓",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
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
