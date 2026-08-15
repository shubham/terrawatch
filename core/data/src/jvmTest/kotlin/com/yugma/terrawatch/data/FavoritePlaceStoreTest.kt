package com.yugma.terrawatch.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.database.TerraWatchDb
import com.yugma.terrawatch.model.FavoriteAlertType
import com.yugma.terrawatch.model.GeoPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 2 (Plan 5): [FavoritePlaceStore] — a thin CRUD wrapper over [com.yugma.terrawatch.database.
 * QuakeStore]'s new favorite-place methods, same "store wraps dao, dao does the real work" shape
 * [HomeLocationStore]/[AlertRuleStore] already establish for this module. Same "real QuakeDao over
 * app.cash.sqldelight's JVM-only in-memory driver" jvmTest pattern those two files use (see
 * AlertRuleStoreTest/HomeLocationTest's own setup) — this class needs a real relational table
 * (favoritePlace), not just the meta key/value rows those two stores lean on, but the test seam is
 * identical either way.
 */
class FavoritePlaceStoreTest {
    private lateinit var dao: QuakeDao
    private lateinit var store: FavoritePlaceStore

    @BeforeTest fun setup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TerraWatchDb.Schema.create(driver)
        dao = QuakeDao(TerraWatchDb(driver))
        store = FavoritePlaceStore(dao)
    }

    @Test fun `favorites on an empty store is empty`() = runTest {
        assertEquals(emptyList(), store.favorites.first())
    }

    @Test fun `add then favorites reads the new place back`() = runTest {
        store.add("Tokyo", GeoPoint(35.6762, 139.6503))
        val place = store.favorites.first().single()
        assertEquals("Tokyo", place.label)
        assertEquals(GeoPoint(35.6762, 139.6503), place.point)
        assertEquals(FavoriteAlertType.ALL, place.alertType, "add() defaults to ALL when no alertType is given")
    }

    @Test fun `add honors an explicit alertType`() = runTest {
        store.add("Tokyo", GeoPoint(35.6762, 139.6503), FavoriteAlertType.MAJOR_ONLY)
        assertEquals(FavoriteAlertType.MAJOR_ONLY, store.favorites.first().single().alertType)
    }

    @Test fun `remove deletes only the targeted favorite`() = runTest {
        store.add("Keep", GeoPoint(1.0, 1.0))
        store.add("Remove", GeoPoint(2.0, 2.0))
        val toRemove = store.favorites.first().single { it.label == "Remove" }
        store.remove(toRemove.id)
        assertEquals(listOf("Keep"), store.favorites.first().map { it.label })
    }

    @Test fun `setAlertType changes only that favorite's alertType`() = runTest {
        store.add("Mumbai", GeoPoint(19.0760, 72.8777))
        val id = store.favorites.first().single().id
        store.setAlertType(id, FavoriteAlertType.OFF)
        assertEquals(FavoriteAlertType.OFF, store.favorites.first().single().alertType)
    }

    @Test fun `favorites is reactive -- a live collector sees each add and remove`() = runTest {
        store.favorites.test {
            assertEquals(emptyList(), awaitItem())
            store.add("Delhi", GeoPoint(28.6139, 77.2090))
            val afterAdd = awaitItem()
            assertEquals(listOf("Delhi"), afterAdd.map { it.label })
            store.remove(afterAdd.single().id)
            assertEquals(emptyList(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `a new store instance over the same dao still reads previously-added favorites`() = runTest {
        store.add("Persisted", GeoPoint(1.0, 1.0))
        val secondInstance = FavoritePlaceStore(dao)
        assertEquals(listOf("Persisted"), secondInstance.favorites.first().map { it.label })
    }

    @Test fun `favorites preserves insertion order across multiple adds`() = runTest {
        store.add("First", GeoPoint(1.0, 1.0))
        store.add("Second", GeoPoint(2.0, 2.0))
        val labels = store.favorites.first().map { it.label }
        assertEquals(listOf("First", "Second"), labels)
        assertTrue(labels.size == 2)
    }
}
