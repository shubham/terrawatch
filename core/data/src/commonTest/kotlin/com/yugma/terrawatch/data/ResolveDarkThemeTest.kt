package com.yugma.terrawatch.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Task 7 (Plan 3): resolveDarkTheme is a pure function (no QuakeDao) - commonTest, same "pure logic
// lives in commonTest" placement PillStatusTest/AlertRuleEngineTest already use in this module.
class ResolveDarkThemeTest {
    @Test fun `system setting defers to the platform signal when it reports dark`() {
        assertTrue(resolveDarkTheme(ThemeSetting.SYSTEM, systemInDarkTheme = true))
    }

    @Test fun `system setting defers to the platform signal when it reports light`() {
        assertFalse(resolveDarkTheme(ThemeSetting.SYSTEM, systemInDarkTheme = false))
    }

    @Test fun `light setting overrides the platform signal even when it reports dark`() {
        assertFalse(resolveDarkTheme(ThemeSetting.LIGHT, systemInDarkTheme = true))
    }

    @Test fun `light setting stays light when the platform also reports light`() {
        assertFalse(resolveDarkTheme(ThemeSetting.LIGHT, systemInDarkTheme = false))
    }

    @Test fun `dusk setting overrides the platform signal even when it reports light`() {
        assertTrue(resolveDarkTheme(ThemeSetting.DUSK, systemInDarkTheme = false))
    }

    @Test fun `dusk setting stays dark when the platform also reports dark`() {
        assertTrue(resolveDarkTheme(ThemeSetting.DUSK, systemInDarkTheme = true))
    }
}
