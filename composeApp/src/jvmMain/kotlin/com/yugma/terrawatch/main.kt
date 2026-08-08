package com.yugma.terrawatch

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "TerraWatch") { App() }
}
