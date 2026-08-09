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

// Task 7 (Plan 3): mirrors HomeLocationTest/OnboardingStoreTest's setup - a real QuakeDao over
// app.cash.sqldelight's JVM-only in-memory driver, same reason both of those live in jvmTest rather
// than commonTest.
class AlertRuleStoreTest {
    private lateinit var dao: QuakeDao
    private lateinit var store: AlertRuleStore

    @BeforeTest fun setup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TerraWatchDb.Schema.create(driver)
        dao = QuakeDao(TerraWatchDb(driver))
        store = AlertRuleStore(dao)
    }

    // --- defaults-when-unset ---

    @Test fun `nearbyRadiusKm defaults to 100 km when unset`() = runTest {
        assertEquals(100.0, store.nearbyRadiusKm.first())
        assertEquals(AlertRuleStore.DEFAULT_RADIUS_KM, store.nearbyRadiusKm.first())
    }

    @Test fun `minMag defaults to 4_5 when unset`() = runTest {
        assertEquals(4.5, store.minMag.first())
        assertEquals(AlertRuleStore.DEFAULT_MIN_MAG, store.minMag.first())
    }

    // --- round-trips ---

    @Test fun `setNearbyRadius then nearbyRadiusKm round-trips`() = runTest {
        store.setNearbyRadius(250.0)
        assertEquals(250.0, store.nearbyRadiusKm.first())
    }

    @Test fun `setMinMag then minMag round-trips`() = runTest {
        store.setMinMag(6.0)
        assertEquals(6.0, store.minMag.first())
    }

    @Test fun `nearbyRadiusKm reflects each subsequent setNearbyRadius call in order`() = runTest {
        store.nearbyRadiusKm.test {
            assertEquals(100.0, awaitItem()) // default, seen on first subscribe
            store.setNearbyRadius(250.0)
            assertEquals(250.0, awaitItem())
            store.setNearbyRadius(500.0)
            assertEquals(500.0, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- corrupt -> default ---

    @Test fun `corrupt stored radius yields the default instead of throwing`() = runTest {
        dao.metaPut("rule_radiuskm", "not-a-number")
        assertEquals(100.0, store.nearbyRadiusKm.first())
    }

    @Test fun `corrupt stored minMag yields the default instead of throwing`() = runTest {
        dao.metaPut("rule_minmag", "garbage")
        assertEquals(4.5, store.minMag.first())
    }

    // --- updates SharedFlow ---

    @Test fun `setNearbyRadius emits on updates`() = runTest {
        store.updates.test {
            store.setNearbyRadius(250.0)
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `setMinMag emits on updates`() = runTest {
        store.updates.test {
            store.setMinMag(6.0)
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- the shared-signal design's one real correctness risk: cross-field re-emission ---

    // Red without distinctUntilChanged: setNearbyRadius's unrelated write still fires `updates`,
    // which would re-map minMag's flow to its own (unchanged) 4.5 and hand Turbine a spurious
    // duplicate item before the real 6.0 - awaitItem() below would then return 4.5, not 6.0, and
    // this assertion would fail. Green with it: the unrelated change is filtered out entirely.
    @Test fun `minMag does not re-emit when only the radius changes`() = runTest {
        store.minMag.test {
            assertEquals(4.5, awaitItem())
            store.setNearbyRadius(999.0)
            store.setMinMag(6.0)
            assertEquals(6.0, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `nearbyRadiusKm does not re-emit when only minMag changes`() = runTest {
        store.nearbyRadiusKm.test {
            assertEquals(100.0, awaitItem())
            store.setMinMag(6.0)
            store.setNearbyRadius(500.0)
            assertEquals(500.0, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- persistence across instances (same "a fresh store over the same dao still reads what a
    // prior instance wrote" property OnboardingStoreTest pins) ---

    @Test fun `a new store instance over the same dao still reads a previously-set radius`() = runTest {
        store.setNearbyRadius(700.0)
        val secondInstance = AlertRuleStore(dao)
        assertEquals(700.0, secondInstance.nearbyRadiusKm.first())
    }
}
