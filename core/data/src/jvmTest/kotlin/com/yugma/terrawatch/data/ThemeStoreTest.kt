package com.yugma.terrawatch.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.database.TerraWatchDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Task 7 (Plan 3): same in-memory-JDBC-driver setup as HomeLocationTest/OnboardingStoreTest/
// AlertRuleStoreTest - a real QuakeDao over app.cash.sqldelight's JVM-only driver.
class ThemeStoreTest {
    private lateinit var dao: QuakeDao
    private lateinit var store: ThemeStore

    @BeforeTest fun setup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TerraWatchDb.Schema.create(driver)
        dao = QuakeDao(TerraWatchDb(driver))
        store = ThemeStore(dao)
    }

    @Test fun `defaults to SYSTEM when unset`() = runTest {
        assertEquals(ThemeSetting.SYSTEM, store.theme.first())
    }

    @Test fun `setTheme LIGHT then theme round-trips`() = runTest {
        store.setTheme(ThemeSetting.LIGHT)
        assertEquals(ThemeSetting.LIGHT, store.theme.first())
    }

    @Test fun `setTheme DUSK then theme round-trips`() = runTest {
        store.setTheme(ThemeSetting.DUSK)
        assertEquals(ThemeSetting.DUSK, store.theme.first())
    }

    @Test fun `theme reflects each subsequent setTheme call in order`() = runTest {
        store.theme.test {
            assertEquals(ThemeSetting.SYSTEM, awaitItem())
            store.setTheme(ThemeSetting.DUSK)
            assertEquals(ThemeSetting.DUSK, awaitItem())
            store.setTheme(ThemeSetting.LIGHT)
            assertEquals(ThemeSetting.LIGHT, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `corrupt stored theme yields SYSTEM instead of throwing`() = runTest {
        dao.metaPut("theme", "not-a-real-theme")
        assertEquals(ThemeSetting.SYSTEM, store.theme.first())
    }

    @Test fun `a new store instance over the same dao still reads a previously-set theme`() = runTest {
        store.setTheme(ThemeSetting.DUSK)
        val secondInstance = ThemeStore(dao)
        assertEquals(ThemeSetting.DUSK, secondInstance.theme.first())
    }

    @Test fun `re-setting the same theme does not emit a spurious duplicate`() = runTest {
        store.setTheme(ThemeSetting.LIGHT)
        store.theme.test {
            assertEquals(ThemeSetting.LIGHT, awaitItem())
            store.setTheme(ThemeSetting.LIGHT) // same value again
            store.setTheme(ThemeSetting.DUSK) // the only genuine change
            assertEquals(ThemeSetting.DUSK, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
