package com.yugma.terrawatch.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.database.TerraWatchDb
import com.yugma.terrawatch.model.GeoPoint
import kotlinx.coroutines.test.runTest
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

    // Task 2 (Plan 3): [HomeLocationStore.updates] is the other half of "closing the location
    // loop" — HomeViewModel.homeLocation (Task 2) collects this so a grant/city-pick landing mid-
    // session actually reaches the already-composed pill instead of requiring a restart. Subscribes
    // BEFORE calling set() (Turbine's test{} only returns control to this lambda once its
    // collection has actually started), so this pins the emission is real push, not something a
    // late subscriber merely happens to observe.
    @Test fun `set emits the point on updates`() = runTest {
        store.updates.test {
            store.set(GeoPoint(12.34, 56.78))
            assertEquals(GeoPoint(12.34, 56.78), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `two sets emit two updates in order`() = runTest {
        store.updates.test {
            store.set(GeoPoint(1.0, 2.0))
            assertEquals(GeoPoint(1.0, 2.0), awaitItem())
            store.set(GeoPoint(3.0, 4.0))
            assertEquals(GeoPoint(3.0, 4.0), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Task 3 (Plan 3) carry-in — the Task 2 ledger minor: [HomeLocationStore.updates]'s old kdoc
    // claimed extraBufferCapacity alone meant a racing collector "is not silently lost", but that
    // claim only actually held for a collector that was ALREADY subscribed (or mid-subscribe) when
    // set() fired — extraBufferCapacity governs backpressure for an already-subscribed-but-slow
    // collector, not replay for one that subscribes strictly AFTER the emission. A genuinely late
    // subscriber (this test: set() completes fully before .test{} ever subscribes) got nothing at
    // all under the old replay = 0 config and would have to wait for a NEXT set() call that might
    // never come. replay = 1 is what actually closes that gap.
    //
    // Red (pre-fix): times out waiting for awaitItem() — replay = 0 has nothing to hand a
    // subscriber that joins after the only set() call already completed.
    @Test fun `a subscriber that joins after set still receives the latest point`() = runTest {
        store.set(GeoPoint(11.0, 22.0))
        store.updates.test {
            assertEquals(GeoPoint(11.0, 22.0), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
