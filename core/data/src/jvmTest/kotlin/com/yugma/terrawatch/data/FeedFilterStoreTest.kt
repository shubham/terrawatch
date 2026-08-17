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

/**
 * User review items 3+4 (history search + magnitude filters): [FeedFilterStore] persists the
 * dashboard feed sheet's own list-scoped magnitude filter — same "plain QuakeDao meta-table row,
 * real SQLite over app.cash.sqldelight's JVM in-memory driver" shape [AlertRuleStoreTest] already
 * establishes for the sibling [AlertRuleStore]. The one real shape difference this suite pins:
 * [FeedFilterStore.minMag] is `Flow<Double?>` (nullable — `null` means the "All" chip), and its
 * unset-default is 4.0 (the user's own explicit "first-run default 4.0+" instruction), not
 * [AlertRuleStore]'s own unrelated 4.5.
 */
class FeedFilterStoreTest {
    private lateinit var dao: QuakeDao
    private lateinit var store: FeedFilterStore

    @BeforeTest fun setup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TerraWatchDb.Schema.create(driver)
        dao = QuakeDao(TerraWatchDb(driver))
        store = FeedFilterStore(dao)
    }

    // --- default-when-unset: the user's explicit "first-run default 4.0+" instruction -----------

    @Test fun `minMag defaults to 4_0 when never set`() = runTest {
        assertEquals(4.0, store.minMag.first())
        assertEquals(FeedFilterStore.DEFAULT_MIN_MAG, store.minMag.first())
    }

    // --- round-trips, including the nullable "All" case -------------------------------------------

    @Test fun `setMinMag then minMag round-trips a real threshold`() = runTest {
        store.setMinMag(6.0)
        assertEquals(6.0, store.minMag.first())
    }

    @Test fun `setMinMag(null) then minMag round-trips All, not the 4_0 default`() = runTest {
        store.setMinMag(null)
        assertEquals(null, store.minMag.first())
    }

    @Test fun `minMag reflects each subsequent setMinMag call in order, including a later null`() = runTest {
        store.minMag.test {
            assertEquals(4.0, awaitItem()) // default, seen on first subscribe
            store.setMinMag(6.0)
            assertEquals(6.0, awaitItem())
            store.setMinMag(null) // back to "All"
            assertEquals(null, awaitItem())
            store.setMinMag(5.0)
            assertEquals(5.0, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- corrupt stored value -> default, not a throw ---------------------------------------------

    @Test fun `corrupt stored value yields the 4_0 default instead of throwing`() = runTest {
        dao.metaPut("feed_filter_minmag", "not-a-number")
        assertEquals(4.0, store.minMag.first())
    }

    // --- updates SharedFlow -------------------------------------------------------------------------

    @Test fun `setMinMag emits on updates`() = runTest {
        store.updates.test {
            store.setMinMag(6.0)
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- persistence across instances (same property OnboardingStoreTest/AlertRuleStoreTest pin) ---

    @Test fun `a new store instance over the same dao still reads a previously-set value`() = runTest {
        store.setMinMag(6.0)
        val secondInstance = FeedFilterStore(dao)
        assertEquals(6.0, secondInstance.minMag.first())
    }

    @Test fun `a new store instance over the same dao still reads a previously-set All`() = runTest {
        store.setMinMag(null)
        val secondInstance = FeedFilterStore(dao)
        assertEquals(null, secondInstance.minMag.first())
    }

    // --- synchronous escape hatch, mirrors AlertRuleStore.currentMinMag's own shape ---------------

    @Test fun `currentMinMag synchronous escape hatch reads the same value as the Flow`() {
        store.setMinMag(5.0)
        assertEquals(5.0, store.currentMinMag())
    }

    @Test fun `currentMinMag defaults to 4_0 when unset`() {
        assertEquals(4.0, store.currentMinMag())
    }
}
