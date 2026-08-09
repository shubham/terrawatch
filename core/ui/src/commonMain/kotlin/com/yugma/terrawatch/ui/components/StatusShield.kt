package com.yugma.terrawatch.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yugma.terrawatch.data.PillStatus
import com.yugma.terrawatch.model.magnitudeBand
import com.yugma.terrawatch.ui.format.formatCount
import com.yugma.terrawatch.ui.format.formatMagnitude
import com.yugma.terrawatch.ui.format.formatRelativeTime
import com.yugma.terrawatch.ui.theme.TerraColors
import com.yugma.terrawatch.ui.theme.TerraRadii
import kotlin.math.roundToLong

private val ALERT_CORNER = 20.dp
private val GLYPH_SIZE = 26.dp

/**
 * The "am I safe?" answer — a glass pill meant to float over the live map (HomeScreen owns the
 * actual top-center placement/status-bar inset; this composable only draws the pill itself).
 * "Glass" here means translucent surface + tonal elevation (matching HomeScreen's staleness
 * banner treatment) rather than a real blur, which isn't available uniformly across this
 * project's Compose Multiplatform targets.
 *
 * Three faces, per [PillStatus.Kind]:
 * - [PillStatus.Kind.CALM]: green check, "All calm near you".
 * - [PillStatus.Kind.ALERT]: the nearest significant quake's own [MagnitudeBadge] + "M X.X · place
 *   · time".
 * - [PillStatus.Kind.ASK_LOCATION]: a neutral location pin, "Where are you?" — shown until
 *   [com.yugma.terrawatch.data.pillStatus] has a home point to answer its own question with.
 *
 * Corner shape differs slightly by kind (CALM/ASK_LOCATION use the full pill radius, ALERT a
 * slightly squarer corner) per the Task 9 brief's shape-morph note — this is the static version;
 * animating the corner between the two is explicitly out of scope this task (Task 10 owns
 * motion).
 *
 * Task 7 (Plan 3) fix, device-caught (USER REQUIREMENT — found live on 98bc1cd8 while verifying
 * the radius slider, not from reading the diff): the CALM subtitle used to hardcode "Nothing within
 * 500 km · 24 h" regardless of [radiusKm] — a caller could set the stored radius to 100 (the new
 * default) and this pill would keep claiming "500 km" forever, directly contradicting the very
 * feature this task exists to ship. [radiusKm] now drives that text for real.
 */
@Composable
fun StatusShield(
    status: PillStatus,
    nowMillis: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    radiusKm: Double = 100.0,
) {
    val shape = if (status.kind == PillStatus.Kind.ALERT) {
        RoundedCornerShape(ALERT_CORNER)
    } else {
        RoundedCornerShape(TerraRadii.pill)
    }
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
        tonalElevation = 4.dp,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (status.kind) {
                PillStatus.Kind.CALM -> CalmContent(radiusKm)
                PillStatus.Kind.ALERT -> AlertContent(status, nowMillis)
                PillStatus.Kind.ASK_LOCATION -> AskLocationContent()
            }
        }
    }
}

@Composable
private fun CalmContent(radiusKm: Double) {
    CheckGlyph(tint = TerraColors.Safe, modifier = Modifier.size(GLYPH_SIZE))
    Column {
        Text(
            text = "All calm near you",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Nothing within ${formatCount(radiusKm.roundToLong())} km · 24 h",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AlertContent(status: PillStatus, nowMillis: Long) {
    val quake = status.quake
    MagnitudeBadge(mag = quake?.mag, band = magnitudeBand(quake?.mag), size = BadgeSize.Small)
    val text = if (quake == null) {
        "Alert"
    } else {
        "M ${formatMagnitude(quake.mag)} · ${quake.place} · " +
            formatRelativeTime(quake.timeMillis, nowMillis)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun AskLocationContent() {
    LocationPinGlyph(tint = TerraColors.InfoBlue, modifier = Modifier.size(GLYPH_SIZE))
    Column {
        Text(
            text = "Where are you?",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Set location for nearby alerts",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A green circle with a white checkmark, drawn on a [Canvas] rather than pulled from an
 * icon-font/material-icons dependency — this project has no icon library dependency anywhere yet,
 * and one shape doesn't justify adding a new artifact (and its own cross-target availability
 * risk) to core:ui's three-target (android/jvm/wasmJs) build.
 */
@Composable
private fun CheckGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        drawCircle(color = tint)
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.28f, h * 0.52f)
            lineTo(w * 0.44f, h * 0.68f)
            lineTo(w * 0.74f, h * 0.32f)
        }
        drawPath(
            path = path,
            color = Color.White,
            style = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/**
 * A simple map-pin silhouette: a filled triangle "point" with a filled circle placed over its top
 * half, plus a small white circle to read as the classic pin "hole" — two plain fills of the same
 * color rather than one path with an arc, so there's no dependence on exactly how any one
 * target's [Path.arcTo]-equivalent behaves. Same "draw it, don't depend on an icon font" reasoning
 * as [CheckGlyph].
 */
@Composable
private fun LocationPinGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val topRadius = w * 0.34f
        val topCenterY = h * 0.36f
        val point = Path().apply {
            moveTo(cx - topRadius * 0.85f, topCenterY + topRadius * 0.35f)
            lineTo(cx + topRadius * 0.85f, topCenterY + topRadius * 0.35f)
            lineTo(cx, h * 0.92f)
            close()
        }
        drawPath(path = point, color = tint)
        drawCircle(color = tint, radius = topRadius, center = Offset(cx, topCenterY))
        drawCircle(color = Color.White, radius = topRadius * 0.4f, center = Offset(cx, topCenterY))
    }
}
