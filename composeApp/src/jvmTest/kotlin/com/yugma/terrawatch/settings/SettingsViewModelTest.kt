package com.yugma.terrawatch.settings

// Same jvmTest-not-commonTest rationale as HomeViewModelTest/HomeLocationTest: a real QuakeDao over
// app.cash.sqldelight's JDBC in-memory driver, a JVM-only artifact.
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.yugma.terrawatch.data.AlertRuleStore
import com.yugma.terrawatch.data.FavoritePlaceStore
import com.yugma.terrawatch.data.HomeLocationStore
import com.yugma.terrawatch.data.ThemeSetting
import com.yugma.terrawatch.data.ThemeStore
import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.database.TerraWatchDb
import com.yugma.terrawatch.model.FavoriteAlertType
import com.yugma.terrawatch.model.GeoPoint
import com.yugma.terrawatch.monetization.AlwaysFreeEntitlements
import com.yugma.terrawatch.monetization.EntitlementsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import kotlin.time.Duration.Companion.seconds

class SettingsViewModelTest {
    // Same leaked-coroutine teardown discipline as HomeViewModelTest.createVm/tearDown — every
    // SettingsViewModel this suite builds launches several viewModelScope collectors in init{},
    // none of which are children of a test's own runTest{} coroutine.
    //
    // Flake-hardening pass (2026-08-16, sweeping the terrawatch flaky-test playbook -- see
    // HomeViewModelTest's own kdoc for the original Task-13/commit-5e9e922 precedent this ports):
    // NOT every StateFlow this class exposes needs a timeout margin -- `nearbyRadiusKm`/`minMag`
    // (AlertRuleStore) and `theme` (ThemeStore) are both a synchronous `dao.metaGet` read behind an
    // in-memory SharedFlow, and `isPlusActive` is a plain passthrough StateFlow -- none of them ever
    // leave Main, so those tests are left untouched. `homeLocation` and `favorites` are different:
    // `init`'s `viewModelScope.launch(Dispatchers.Default) { _homeLocation.value =
    // homeLocationStore.get() }` is a hard-coded, un-pinnable cross-pool hop (mirrors
    // HomeViewModel's own identical block), and `favoritePlaceStore.favorites` is
    // `QuakeDao.favoritePlaces()`, which hard-codes `.mapToList(Dispatchers.Default)` regardless of
    // any pin. Every `vm.homeLocation.test {}`/`vm.favorites.test {}` below now carries
    // `timeout = 30.seconds` for the same starved-CI-runner margin commit 5e9e922 first established.
    private val createdViewModels = mutableListOf<SettingsViewModel>()

    private fun createVm(
        alertRuleStore: AlertRuleStore = AlertRuleStore(freshDao()),
        themeStore: ThemeStore = ThemeStore(freshDao()),
        homeLocationStore: HomeLocationStore = HomeLocationStore(freshDao()),
        // Plan 4 Task 6: defaulted so every pre-existing test below (none of which care about
        // entitlements) keeps compiling and passing unchanged — same "add a new store, default it"
        // shape this helper's own 3 pre-existing params already established.
        entitlementsProvider: EntitlementsProvider = AlwaysFreeEntitlements,
        // Task 2 (Plan 5): same "add a new store, default it" shape as entitlementsProvider just
        // above, for the new favorites section.
        favoritePlaceStore: FavoritePlaceStore = FavoritePlaceStore(freshDao()),
    ): SettingsViewModel =
        SettingsViewModel(alertRuleStore, themeStore, homeLocationStore, entitlementsProvider, favoritePlaceStore)
            .also { createdViewModels += it }

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
        vm.homeLocation.test(timeout = 30.seconds) {
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
        vm.homeLocation.test(timeout = 30.seconds) {
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

    // --- Plan 4 Task 6: isPlusActive mirrors the injected EntitlementsProvider directly ----------

    @Test fun `isPlusActive reflects AlwaysFreeEntitlements' constant false by default`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = createVm()
        vm.isPlusActive.test {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `isPlusActive is a direct passthrough, not a snapshot copy - a live provider flip is reflected`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val fakeProvider = FakeEntitlementsProvider()
        val vm = createVm(entitlementsProvider = fakeProvider)
        vm.isPlusActive.test {
            assertEquals(false, awaitItem())
            fakeProvider.setPlusActive(true)
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** A directly-controllable [EntitlementsProvider] fake — [AlwaysFreeEntitlements] itself is a
     * constant `false` by design and can't exercise the "live flip" half of the passthrough claim
     * above. */
    private class FakeEntitlementsProvider : EntitlementsProvider {
        private val _isPlusActive = MutableStateFlow(false)
        override val isPlusActive: StateFlow<Boolean> = _isPlusActive
        fun setPlusActive(value: Boolean) {
            _isPlusActive.value = value
        }
    }

    // --- Task 2 (Plan 5): favorites CRUD + the FIRST REAL Plus gate -------------------------------

    @Test fun `favorites starts empty when the store has none`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = createVm()
        vm.favorites.test(timeout = 30.seconds) {
            assertEquals(emptyList(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `favorites reacts to a store update landing mid-session`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val favoritePlaceStore = FavoritePlaceStore(freshDao())
        val vm = createVm(favoritePlaceStore = favoritePlaceStore)
        vm.favorites.test(timeout = 30.seconds) {
            assertEquals(emptyList(), awaitItem())
            favoritePlaceStore.add("Tokyo", GeoPoint(35.6762, 139.6503))
            assertEquals(listOf("Tokyo"), awaitItem().map { it.label })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `addFavorite writes through to the store, reaching favorites`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = createVm()
        vm.favorites.test(timeout = 30.seconds) {
            assertEquals(emptyList(), awaitItem())
            vm.addFavorite("Delhi", GeoPoint(28.6139, 77.2090))
            assertEquals(listOf("Delhi"), awaitItem().map { it.label })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `removeFavorite writes through to the store, reaching favorites`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val favoritePlaceStore = FavoritePlaceStore(freshDao())
        val vm = createVm(favoritePlaceStore = favoritePlaceStore)
        vm.favorites.test(timeout = 30.seconds) {
            assertEquals(emptyList(), awaitItem())
            vm.addFavorite("Mumbai", GeoPoint(19.0760, 72.8777))
            val added = awaitItem().single()
            vm.removeFavorite(added.id)
            assertEquals(emptyList(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `setFavoriteAlertType writes through to the store, reaching favorites`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = createVm()
        vm.favorites.test(timeout = 30.seconds) {
            assertEquals(emptyList(), awaitItem())
            vm.addFavorite("Mumbai", GeoPoint(19.0760, 72.8777))
            val added = awaitItem().single()
            vm.setFavoriteAlertType(added.id, FavoriteAlertType.OFF)
            assertEquals(FavoriteAlertType.OFF, awaitItem().single().alertType)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `canAddFavorite is true on the free tier with zero favorites`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = createVm()
        vm.favorites.test(timeout = 30.seconds) {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(true, vm.canAddFavorite())
    }

    @Test fun `canAddFavorite is false on the free tier once one favorite already exists`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = createVm()
        vm.favorites.test(timeout = 30.seconds) {
            awaitItem()
            vm.addFavorite("Mumbai", GeoPoint(19.0760, 72.8777))
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(false, vm.canAddFavorite())
    }

    @Test fun `canAddFavorite is true regardless of count when Plus is active`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val fakeProvider = FakeEntitlementsProvider().apply { setPlusActive(true) }
        val vm = createVm(entitlementsProvider = fakeProvider)
        vm.favorites.test(timeout = 30.seconds) {
            awaitItem()
            vm.addFavorite("Mumbai", GeoPoint(19.0760, 72.8777))
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(true, vm.canAddFavorite())
    }
}
