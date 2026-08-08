package com.yugma.terrawatch.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.database.TerraWatchDb
import com.yugma.terrawatch.model.GeoPoint
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeLocationTest {
    private lateinit var dao: QuakeDao
    private lateinit var store: HomeLocationStore

    @BeforeTest fun setup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TerraWatchDb.Schema.create(driver)
        dao = QuakeDao(TerraWatchDb(driver))
        store = HomeLocationStore(dao)
    }

    @Test fun `get on empty store returns null`() {
        assertNull(store.get())
    }

    @Test fun `set then get round-trips`() {
        store.set(GeoPoint(12.34, 56.78))
        assertEquals(GeoPoint(12.34, 56.78), store.get())
    }

    @Test fun `set overwrites a previously stored point`() {
        store.set(GeoPoint(12.34, 56.78))
        store.set(GeoPoint(-1.0, -2.0))
        assertEquals(GeoPoint(-1.0, -2.0), store.get())
    }

    // dao.metaGet/metaPut store plain strings — a hand-corrupted or partially-written value must
    // not crash the pill's dependency chain (HomeLocationStore.get() ?: LocationProvider.current()
    // in Task 9); toDoubleOrNull() turns a bad parse into a clean null instead of a thrown
    // NumberFormatException.
    @Test fun `corrupt stored lat returns null instead of throwing`() {
        dao.metaPut("home_lat", "not-a-number")
        dao.metaPut("home_lon", "56.78")
        assertNull(store.get())
    }
}
