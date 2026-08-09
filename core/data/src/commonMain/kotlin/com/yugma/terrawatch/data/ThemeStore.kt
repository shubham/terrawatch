package com.yugma.terrawatch.data

import com.yugma.terrawatch.database.QuakeStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** The Settings screen's theme radio's three options — System defers to the platform's own light/
 * dark signal, Light/Dusk override it outright regardless of what the platform reports. */
enum class ThemeSetting { SYSTEM, LIGHT, DUSK }

/**
 * Task 7 (Plan 3): the Settings theme radio's persistence — same plain-key/value-row-in-meta
 * pattern as [HomeLocationStore]/[OnboardingStore]/[AlertRuleStore] (one row, "theme", no schema
 * change). [theme] is a `Flow`, not a one-shot accessor, for the same reason [AlertRuleStore]'s own
 * `nearbyRadiusKm`/`minMag` are: `App()` collects it directly (see that composable's own kdoc) and
 * every recomposition of the ENTIRE app tree depends on its resolved value, so "the current value,
 * and every value it changes to" is what the one real caller actually needs — there's no second
 * concern here for a separate `updates` side-channel to serve.
 */
// Task 9 (Plan 3): dao widened from QuakeDao to QuakeStore — see that interface's kdoc. Only
// metaGet/metaPut are used here, both on the interface; zero behavior change.
class ThemeStore(private val dao: QuakeStore) {
    private val _updates = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 4)

    val theme: Flow<ThemeSetting> = _updates
        .map { readTheme() }
        .onStart { emit(readTheme()) }
        .distinctUntilChanged()

    fun setTheme(setting: ThemeSetting) {
        dao.metaPut(THEME_KEY, setting.name)
        _updates.tryEmit(Unit)
    }

    // enum name strings ("SYSTEM"/"LIGHT"/"DUSK"), not a smaller custom code - readable directly
    // from a debug DB query (this task's own device-verification step), and firstOrNull{} degrades
    // a corrupt/unrecognized value to SYSTEM instead of throwing, same defensive posture
    // AlertRuleStore's toDoubleOrNull()-then-default takes for its own meta rows.
    private fun readTheme(): ThemeSetting =
        dao.metaGet(THEME_KEY)?.let { stored -> ThemeSetting.entries.firstOrNull { it.name == stored } }
            ?: ThemeSetting.SYSTEM

    private companion object {
        const val THEME_KEY = "theme"
    }
}

/**
 * Resolves [setting] against the platform's own `isSystemInDarkTheme()` signal — pure (not a
 * `@Composable`) so it's unit-testable without a Compose runtime. [ThemeSetting.SYSTEM] defers to
 * [systemInDarkTheme] entirely; [ThemeSetting.LIGHT]/[ThemeSetting.DUSK] override it outright in
 * either direction. `App()` is the one real call site, feeding it `isSystemInDarkTheme()`'s live
 * composable value.
 */
fun resolveDarkTheme(setting: ThemeSetting, systemInDarkTheme: Boolean): Boolean = when (setting) {
    ThemeSetting.SYSTEM -> systemInDarkTheme
    ThemeSetting.LIGHT -> false
    ThemeSetting.DUSK -> true
}
