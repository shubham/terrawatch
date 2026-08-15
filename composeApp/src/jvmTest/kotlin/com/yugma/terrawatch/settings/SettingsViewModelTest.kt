package com.yugma.terrawatch.settings

// Same jvmTest-not-commonTest rationale as HomeViewModelTest/HomeLocationTest: a real QuakeDao over
// app.cash.sqldelight's JDBC in-memory driver, a JVM-only artifact.
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.yugma.terrawatch.data.AlertRuleStore
import com.yugma.terrawatch.data.HomeLocationStore
import com.yugma.terrawatch.data.ThemeSetting
import com.yugma.terrawatch.data.ThemeStore
import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.database.TerraWatchDb
import com.yugma.terrawatch.model.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsViewModelTest {
    // Same leaked-coroutine teardown discipline as HomeViewModelTest.createVm/tearDown — every
    // SettingsViewModel this suite builds launches several viewModelScope collectors in init{},
    // none of which are children of a test's own runTest{} coroutine.
    private val createdViewModels = mutableListOf<SettingsViewModel>()

    private fun createVm(
        alertRuleStore: AlertRuleStore = AlertRuleStore(freshDao()),
        themeStore: ThemeStore = ThemeStore(freshDao()),
        homeLocationStore: HomeLocationStore = HomeLocationStore(freshDao()),
    ): SettingsViewModel =
        SettingsViewModel(alertRuleStore, themeStore, homeLocationStore).also { createdViewModels += it }

    private fun freshDao(): QuakeDao {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TerraWatchDb.Schema.create(driver)
        return QuakeDao(TerraWatchDb(driver))
    }

    @AfterTest fun tearDown() {
        Dispatchers.resetMain()
        runBlocking { createdViewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        createdViewModels.clear()
    }

    @Test fun `nearbyRadiusKm reflects the store's default`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = createVm()
        vm.nearbyRadiusKm.test {
            assertEquals(AlertRuleStore.DEFAULT_RADIUS_KM, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `setNearbyRadius round-trips through the VM's own nearbyRadiusKm`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = createVm()
        vm.nearbyRadiusKm.test {
            assertEquals(100.0, awaitItem())
            vm.setNearbyRadius(250.0)
            assertEquals(250.0, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `minMag reflects the store's default`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = createVm()
        vm.minMag.test {
            assertEquals(AlertRuleStore.DEFAULT_MIN_MAG, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `setMinMag round-trips through the VM's own minMag`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = createVm()
        vm.minMag.test {
            assertEquals(4.5, awaitItem())
            vm.setMinMag(6.0)
            assertEquals(6.0, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `theme reflects SYSTEM by default`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = createVm()
        vm.theme.test {
            assertEquals(ThemeSetting.SYSTEM, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `setTheme round-trips through the VM's own theme`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = createVm()
        vm.theme.test {
            assertEquals(ThemeSetting.SYSTEM, awaitItem())
            vm.setTheme(ThemeSetting.DUSK)
            assertEquals(ThemeSetting.DUSK, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `homeLocation loads the previously stored point`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val homeLocationStore = HomeLocationStore(freshDao()).apply { set(GeoPoint(12.34, 56.78)) }
        val vm = createVm(homeLocationStore = homeLocationStore)
        vm.homeLocation.test {
            var v = awaitItem()
            while (v == null) v = awaitItem()
            assertEquals(GeoPoint(12.34, 56.78), v)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `homeLocation reacts to a store update landing mid-session`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val homeLocationStore = HomeLocationStore(freshDao())
        val vm = createVm(homeLocationStore = homeLocationStore)
        vm.homeLocation.test {
            assertEquals(null, awaitItem())
            homeLocationStore.set(GeoPoint(9.9, 8.8))
            assertEquals(GeoPoint(9.9, 8.8), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `sequential setNearbyRadius calls persist last-call-wins through the store`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = createVm()
        vm.nearbyRadiusKm.test {
            assertEquals(100.0, awaitItem())
            vm.setNearbyRadius(250.0)
            assertEquals(250.0, awaitItem())
            vm.setNearbyRadius(500.0)
            assertEquals(500.0, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
