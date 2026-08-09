package com.yugma.terrawatch.home

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.CreationExtras

// See QuakeSelectionExtras.kt's kdoc: Android's ComponentActivity already supplies a real
// SavedStateRegistryOwner via setContent {}'s own LocalViewModelStoreOwner wiring — null here
// means App() below leaves koinViewModel<QuakeSelectionViewModel>()'s `extras` at its own default,
// exactly as it has been for every prior task's device verification. Unchanged by Task 9.
@Composable
actual fun rememberQuakeSelectionExtras(): CreationExtras? = null
