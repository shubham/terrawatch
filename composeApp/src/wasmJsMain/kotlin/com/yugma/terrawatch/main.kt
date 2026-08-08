package com.yugma.terrawatch

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeViewport

// Web persistence (and therefore Koin/DB wiring) lands in Plan 3 — DriverFactory.wasmJs.kt
// throws by design. Render a plain placeholder instead of App() until then.
@Composable
private fun WebPlaceholder() {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Text("TerraWatch web — data layer arrives in Plan 3", modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(viewportContainerId = "app") { WebPlaceholder() }
}
