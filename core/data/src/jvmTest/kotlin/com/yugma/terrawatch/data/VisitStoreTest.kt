package com.yugma.terrawatch.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.database.TerraWatchDb
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Same shape as [HomeLocationTest] — a thin meta-table-backed store, tested against a real
 * SQLite-backed [QuakeDao] rather than [com.yugma.terrawatch.database.InMemoryQuakeStore], matching
 * every other meta-store test in this package (HomeLocationTest/ThemeStoreTest/OnboardingStoreTest/
 * AlertRuleStoreTest/FavoritePlaceStoreTest all do the same). */
class VisitStoreTest {
    private lateinit var dao: QuakeDao
    private lateinit var store: VisitStore

    @BeforeTest fun setup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TerraWatchDb.Schema.create(driver)
        dao = QuakeDao(TerraWatchDb(driver))
        store = VisitStore(dao)
    }

    @Test fun `get on a never-visited store returns null`() {
        assertNull(store.get())
    }

    @Test fun `set then get round-trips`() {
        store.set(123_456L)
        assertEquals(123_456L, store.get())
    }

    @Test fun `set overwrites a previously stored value`() {
        store.set(1_000L)
        store.set(2_000L)
        assertEquals(2_000L, store.get())
    }

    // dao.metaGet/metaPut store plain strings — a hand-corrupted value must degrade to null rather
    // than throw, same "toLongOrNull(), never a thrown NumberFormatException" posture
    // HomeLocationStore.get()'s own corrupt-value test already establishes for this meta table.
    @Test fun `corrupt stored value returns null instead of throwing`() {
        dao.metaPut("last_visit_millis", "not-a-number")
        assertNull(store.get())
    }
}
