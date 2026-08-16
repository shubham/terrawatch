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

    // --- Task 2 (Plan 4), M2 ruling: radius READ clamps to [50, 1000] -----------------------------
    // (plan-3-exit-conditions.md carried item — "a corrupted row or any future non-slider caller
    // round-trips an out-of-range value straight through to every reader ... with no validation").
    // TDD per the task brief: corrupt/out-of-range -> clamped.

    @Test fun `corrupt stored radius above the max clamps to 1000`() = runTest {
        dao.metaPut("rule_radiuskm", "50000")
        assertEquals(1000.0, store.nearbyRadiusKm.first())
        assertEquals(AlertRuleStore.MAX_RADIUS_KM, store.nearbyRadiusKm.first())
    }

    @Test fun `corrupt stored radius below the min clamps to 50`() = runTest {
        dao.metaPut("rule_radiuskm", "-5")
        assertEquals(50.0, store.nearbyRadiusKm.first())
        assertEquals(AlertRuleStore.MIN_RADIUS_KM, store.nearbyRadiusKm.first())
    }

    // Not just a parse-failure case: a value that parses FINE as a Double but sits outside the
    // slider's own 50-1000 range (e.g. a future non-slider caller, or a stale value carried over
    // from before this range existed) must be clamped too, not merely defaulted.
    @Test fun `a well-formed but out-of-range radius written by a non-slider caller is clamped on read`() = runTest {
        store.setNearbyRadius(2000.0) // bypasses the UI's own 50-1000 snap entirely
        assertEquals(1000.0, store.nearbyRadiusKm.first())
    }

    @Test fun `currentRadiusKm sync escape hatch also clamps out-of-range values`() {
        dao.metaPut("rule_radiuskm", "9999")
        assertEquals(1000.0, store.currentRadiusKm())
    }

    @Test fun `a radius already within range round-trips unchanged, clamping is not lossy in the normal case`() = runTest {
        store.setNearbyRadius(250.0)
        assertEquals(250.0, store.nearbyRadiusKm.first())
    }

    // --- USER REQUIREMENT (2026-08-16, binding), M4.0 magnitude-floor ruling: minMag READ clamps
    // to [4.0, 6.0], mirroring the M2 radius clamp tests above exactly (same file, same shape, same
    // reasoning — a corrupted row or any future non-slider caller must not round-trip an
    // out-of-range value straight through to every reader with zero validation). ------------------

    @Test fun `corrupt stored minMag below the M4 floor clamps to 4_0`() = runTest {
        dao.metaPut("rule_minmag", "2.0")
        assertEquals(4.0, store.minMag.first())
        assertEquals(AlertRuleEngine.MIN_NOTIFIABLE_MAGNITUDE, store.minMag.first())
    }

    @Test fun `corrupt stored minMag above the max clamps to 6_0`() = runTest {
        dao.metaPut("rule_minmag", "9.0")
        assertEquals(6.0, store.minMag.first())
        assertEquals(AlertRuleStore.MAX_MIN_MAG, store.minMag.first())
    }

    // Not just a parse-failure case: a value that parses FINE as a Double but sits outside the
    // slider's own 4.0-6.0 range (e.g. a future non-slider caller, or a stale value carried over
    // from before this range moved up from 3.0) must be clamped too, not merely defaulted.
    @Test fun `a well-formed but out-of-range minMag written by a non-slider caller is clamped on read`() = runTest {
        store.setMinMag(1.0) // bypasses the UI's own 4.0-6.0 snap entirely
        assertEquals(4.0, store.minMag.first())
    }

    @Test fun `currentMinMag sync escape hatch also clamps out-of-range values`() {
        dao.metaPut("rule_minmag", "0.5")
        assertEquals(4.0, store.currentMinMag())
    }

    @Test fun `a minMag already within range round-trips unchanged, clamping is not lossy in the normal case`() = runTest {
        store.setMinMag(5.0)
        assertEquals(5.0, store.minMag.first())
    }
}
