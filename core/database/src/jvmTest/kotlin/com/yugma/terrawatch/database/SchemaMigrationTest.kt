package com.yugma.terrawatch.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Review 1, BLOCKER-1 (`.superpowers/sdd/2026-08-15-terrawatch-plan-5-polish/review-1-findings.md`):
 * an already-installed (upgrade-install, NOT fresh-install) device's `terrawatch.db` predates
 * `favoritePlace` -- its stored schema is exactly the Plan 1-4 shape (`quake` + `meta` only), because
 * `AndroidSqliteDriver`'s own `Callback.onCreate` only ever ran the OLD `.sq` definitions back when
 * that device first installed the app. Opening that same physical db file with a newer build that
 * assumes `favoritePlace` already exists crashed instantly on `HomeViewModel.init`'s very first
 * collector (`SELECT * FROM favoritePlace` -> `no such table: favoritePlace`, fatal -- no
 * `CoroutineExceptionHandler` installed) -- see that finding's own writeup for the full
 * bytecode-verified reasoning (`Schema.version` stuck at `1` forever, zero `.sqm` files ever existed,
 * `Schema.migrate` a literal no-op).
 *
 * This test reproduces the EXACT physical shape of that pre-existing device: the `quake`+`meta`
 * CREATE TABLE/INDEX statements below are copied byte-for-byte from `Quake.sq` at commit `aaab323`
 * (`git show aaab323:core/database/src/commonMain/sqldelight/com/yugma/terrawatch/database/Quake.sq`)
 * -- the last commit before `FavoritePlace.sq` existed at all -- confirmed identical to HEAD's own
 * `Quake.sq` via `git diff aaab323..HEAD -- Quake.sq` (empty diff), rather than a hand-transcribed
 * guess at what "the old schema" looked like.
 *
 * RED before the fix (this is Review 1's own reproduction, run against the pre-fix tree): with
 * `Schema.version` stuck at `1` and zero `.sqm` files, `TerraWatchDb.Schema.migrate(driver, 1,
 * Schema.version)` is a no-op and `favoritePlaceQueries.selectAllFavoritePlaces()` throws
 * `SQLiteException: no such table: favoritePlace`. GREEN after the fix: `1.sqm` bumps
 * `Schema.version` to 2 and adds the missing `CREATE TABLE IF NOT EXISTS favoritePlace`, so the same
 * call now actually creates it. On a real device this exact mechanism runs via `AndroidSqliteDriver`'s
 * own `Callback.onUpgrade` -- confirmed directly against that class's bytecode in the gradle cache
 * (`android-driver-2.1.0-release.aar`): its constructor stamps `schema.version.toInt()` as the
 * `SupportSQLiteOpenHelper.Callback`'s own version, and `onUpgrade` calls exactly
 * `schema.migrate(driver, oldVersion, newVersion, *callbacks)` -- so `DriverFactory.android.kt`
 * itself needs ZERO code changes; the driver-level wiring already existed, only the schema/migration
 * files were missing.
 */
class SchemaMigrationTest {
    // Verbatim DDL from `git show aaab323:.../Quake.sq` (comments stripped -- only the shape matters
    // for this fixture) -- the last commit before FavoritePlace.sq existed, i.e. exactly what an
    // already-installed Plan 1-4 device's db file looks like on disk today.
    private val v1QuakeAndMetaDdl = listOf(
        """
        CREATE TABLE quake (
          id TEXT NOT NULL PRIMARY KEY,
          timeMillis INTEGER NOT NULL,
          lat REAL NOT NULL,
          lon REAL NOT NULL,
          depthKm REAL,
          mag REAL,
          magType TEXT,
          place TEXT NOT NULL,
          tsunami INTEGER NOT NULL DEFAULT 0,
          felt INTEGER,
          status TEXT NOT NULL,
          sourcesJson TEXT NOT NULL,
          revisionsJson TEXT NOT NULL,
          updatedAtMillis INTEGER NOT NULL,
          fetchedAtMillis INTEGER NOT NULL,
          origin TEXT NOT NULL DEFAULT 'feed'
        )
        """.trimIndent(),
        """
        CREATE TABLE meta (
          key TEXT NOT NULL PRIMARY KEY,
          value TEXT NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX quake_time ON quake(timeMillis DESC)",
        "CREATE INDEX quake_mag ON quake(mag)",
        "CREATE INDEX quake_fetchedAt ON quake(fetchedAtMillis)",
    )

    /** A fresh in-memory driver with ONLY the pre-Plan-5 tables created -- simulates opening an
     * already-installed device's existing db file, before any migration has run against it. */
    private fun openSimulatedV1Driver(): JdbcSqliteDriver {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        v1QuakeAndMetaDdl.forEach { driver.execute(null, it, 0) }
        return driver
    }

    @Test fun `migrating a pre-favorites v1 database creates favoritePlace, queryable and empty`() {
        val driver = openSimulatedV1Driver()
        TerraWatchDb.Schema.migrate(driver, oldVersion = 1L, newVersion = TerraWatchDb.Schema.version)
        val db = TerraWatchDb(driver)
        assertEquals(emptyList(), db.favoritePlaceQueries.selectAllFavoritePlaces().executeAsList())
    }

    @Test fun `migration preserves pre-existing quake rows untouched`() {
        val driver = openSimulatedV1Driver()
        val dao = QuakeDao(TerraWatchDb(driver))
        dao.upsert(
            Quake(
                id = "us1", timeMillis = 900, lat = 7.1, lon = 126.5, depthKm = 10.0, mag = 5.0,
                magType = "mb", place = "Somewhere", tsunami = false, felt = null,
                status = QuakeStatus.AUTOMATIC, sources = mapOf(Source.USGS to "us1"),
                revisions = emptyList(), updatedAtMillis = 1000,
            ),
        )
        TerraWatchDb.Schema.migrate(driver, oldVersion = 1L, newVersion = TerraWatchDb.Schema.version)
        assertEquals(5.0, dao.byId("us1")?.mag, "pre-existing quake data must survive the migration untouched")
    }

    @Test fun `schema version is 2, reflecting the one migration this project has ever needed`() {
        assertEquals(2L, TerraWatchDb.Schema.version)
    }

    @Test fun `a fresh v2 create (brand-new install) still includes favoritePlace, unaffected by the migration path`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TerraWatchDb.Schema.create(driver)
        val db = TerraWatchDb(driver)
        assertTrue(db.favoritePlaceQueries.selectAllFavoritePlaces().executeAsList().isEmpty())
    }
}
