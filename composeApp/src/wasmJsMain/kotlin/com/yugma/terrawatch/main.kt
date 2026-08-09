package com.yugma.terrawatch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import com.yugma.terrawatch.ui.theme.TerraColors
import com.yugma.terrawatch.ui.theme.TerraTheme

// Task 12 (spike decision, docs/superpowers/plans/2026-08-08-terrawatch-plan-2-ui-shell.md's Task
// 12 section): maplibre-compose publishes no wasmJs artifact at all, so web would share jvm's
// FallbackMapPane rather than a live tile map even once it has data to show — but web's own data
// layer (DriverFactory.wasmJs.kt's SqlDriver, and therefore Koin) is a SEPARATE, still-unpaid Plan
// 3 debt this task does not pay down: wiring FallbackMapPane here would need real pins from a real
// QuakeRepository, which needs a real Koin-provided QuakeDao, which needs a working wasmJs
// SqlDriver — none of which exists yet (DriverFactory.wasmJs.kt's createDriver() still throws, by
// design, exactly as before this task). This upgrade only replaces the placeholder's plain,
// unthemed text with an honest, on-brand page: real TerraTheme colors, the app's name, the same
// message as before reworded to name Plan 3 explicitly, and a Water-tinted Canvas hinting at the
// map that's coming — zero new data plumbing.
@Composable
private fun WebPlaceholder() {
    TerraTheme {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Canvas(Modifier.fillMaxSize()) { drawRect(color = TerraColors.Water) }
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "TerraWatch",
                        style = MaterialTheme.typography.headlineMedium, // TerraTypography already bolds this role.
                        color = TerraColors.Ink,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "Earthquake monitor — Android app live; web version arrives with Plan 3",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TerraColors.Ink,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(viewportContainerId = "app") { WebPlaceholder() }
}
