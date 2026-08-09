package com.yugma.terrawatch.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.database.TerraWatchDb
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Task 4 (Plan 3): TDD for AppNav's onboarding gate ("first-run flag in meta... if absent, nav
// starts at onboarding route") -- written RED first (OnboardingStore didn't exist), same
// in-memory-JDBC-driver setup as HomeLocationTest for the identical reason (a real QuakeDao over
// app.cash.sqldelight's JVM-only driver).
class OnboardingStoreTest {
    private lateinit var dao: QuakeDao
    private lateinit var store: OnboardingStore

    @BeforeTest fun setup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TerraWatchDb.Schema.create(driver)
        dao = QuakeDao(TerraWatchDb(driver))
        store = OnboardingStore(dao)
    }

    @Test fun `a fresh store (fresh install) is not onboarded`() {
        assertFalse(store.isOnboarded())
    }

    @Test fun `setOnboarded flips isOnboarded to true`() {
        store.setOnboarded()
        assertTrue(store.isOnboarded())
    }

    @Test fun `isOnboarded stays true across repeated reads and a second setOnboarded call`() {
        store.setOnboarded()
        assertTrue(store.isOnboarded())
        store.setOnboarded()
        assertTrue(store.isOnboarded())
    }

    // A fresh OnboardingStore instance over the SAME underlying dao/db must see the flag a prior
    // instance wrote -- this is what actually lets the flag "survive" a process restart (a new
    // OnboardingStore is constructed from scratch each app launch; nothing about this class itself
    // is what persists, the meta table row is).
    @Test fun `a new store instance over the same dao still reads a previously-set flag`() {
        store.setOnboarded()
        val secondInstance = OnboardingStore(dao)
        assertTrue(secondInstance.isOnboarded())
    }
}
