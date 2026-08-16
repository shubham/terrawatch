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
import com.yugma.terrawatch.notifications.NotificationAlertsUiState
import com.yugma.terrawatch.notifications.NotificationPermissionCondition
import kotlinx.coroutines.CoroutineDispatcher
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
    //
    // Flake-hardening pass (2026-08-16, superseded note above -- the "un-pinnable" half): SettingsViewModel
    // gained the identical pinnable `ioDispatcher` ctor param HomeViewModel's own flake-hardening pass
    // added (see that class's kdoc for the ~10-15% TestMainDispatcher race this closes) -- every test
    // below now also pins it to the same UnconfinedTestDispatcher instance backing Dispatchers.Main.
    // `QuakeDao.favoritePlaces()`'s own hard-coded `.mapToList(Dispatchers.Default)` remains genuinely
    // un-pinnable (a separate module's DAO-level crossing) -- the `timeout = 30.seconds` margin on
    // `favorites` stays for that reason; `homeLocation`'s own timeout is now a harmless belt rather
    // than the operative fix.
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
        // Flake-hardening pass (2026-08-16): matches HomeViewModelTest's own createVm() pin style --
        // defaulted to the real Dispatchers.Default (compile-safe), every test below passes its own
        // UnconfinedTestDispatcher explicitly instead.
        ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
        // Fix (post-Plan-5 tail): same "add a new param, default it" shape as ioDispatcher just
        // above -- see SettingsViewModel's own kdoc for why these 3 are function types, not the
        // concrete NotificationPermissionRequester/AlertDigestScheduler classes.
        readNotificationCondition: () -> NotificationPermissionCondition = { NotificationPermissionCondition.PRE_33 },
        isDigestEnqueued: suspend () -> Boolean = { false },
        ensureDigestEnqueued: () -> Unit = {},
    ): SettingsViewModel =
        SettingsViewModel(
            alertRuleStore,
            themeStore,
            homeLocationStore,
            entitlementsProvider,
            favoritePlaceStore,
            ioDispatcher,
            readNotificationCondition,
            isDigestEnqueued,
            ensureDigestEnqueued,
        ).also { createdViewModels += it }

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
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val vm = createVm(ioDispatcher = testDispatcher)
        vm.nearbyRadiusKm.test {
            assertEquals(AlertRuleStore.DEFAULT_RADIUS_KM, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `setNearbyRadius round-trips through the VM's own nearbyRadiusKm`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val vm = createVm(ioDispatcher = testDispatcher)
        vm.nearbyRadiusKm.test {
            assertEquals(100.0, awaitItem())
            vm.setNearbyRadius(250.0)
            assertEquals(250.0, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `minMag reflects the store's default`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val vm = createVm(ioDispatcher = testDispatcher)
        vm.minMag.test {
            assertEquals(AlertRuleStore.DEFAULT_MIN_MAG, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `setMinMag round-trips through the VM's own minMag`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val vm = createVm(ioDispatcher = testDispatcher)
        vm.minMag.test {
            assertEquals(4.5, awaitItem())
            vm.setMinMag(6.0)
            assertEquals(6.0, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `theme reflects SYSTEM by default`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val vm = createVm(ioDispatcher = testDispatcher)
        vm.theme.test {
            assertEquals(ThemeSetting.SYSTEM, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `setTheme round-trips through the VM's own theme`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val vm = createVm(ioDispatcher = testDispatcher)
        vm.theme.test {
            assertEquals(ThemeSetting.SYSTEM, awaitItem())
            vm.setTheme(ThemeSetting.DUSK)
            assertEquals(ThemeSetting.DUSK, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `homeLocation loads the previously stored point`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val homeLocationStore = HomeLocationStore(freshDao()).apply { set(GeoPoint(12.34, 56.78)) }
        val vm = createVm(homeLocationStore = homeLocationStore, ioDispatcher = testDispatcher)
        vm.homeLocation.test(timeout = 30.seconds) {
            var v = awaitItem()
            while (v == null) v = awaitItem()
            assertEquals(GeoPoint(12.34, 56.78), v)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `homeLocation reacts to a store update landing mid-session`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val homeLocationStore = HomeLocationStore(freshDao())
        val vm = createVm(homeLocationStore = homeLocationStore, ioDispatcher = testDispatcher)
        vm.homeLocation.test(timeout = 30.seconds) {
            assertEquals(null, awaitItem())
            homeLocationStore.set(GeoPoint(9.9, 8.8))
            assertEquals(GeoPoint(9.9, 8.8), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `sequential setNearbyRadius calls persist last-call-wins through the store`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val vm = createVm(ioDispatcher = testDispatcher)
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
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val vm = createVm(ioDispatcher = testDispatcher)
        vm.isPlusActive.test {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `isPlusActive is a direct passthrough, not a snapshot copy - a live provider flip is reflected`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val fakeProvider = FakeEntitlementsProvider()
        val vm = createVm(entitlementsProvider = fakeProvider, ioDispatcher = testDispatcher)
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
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val vm = createVm(ioDispatcher = testDispatcher)
        vm.favorites.test(timeout = 30.seconds) {
            assertEquals(emptyList(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `favorites reacts to a store update landing mid-session`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val favoritePlaceStore = FavoritePlaceStore(freshDao())
        val vm = createVm(favoritePlaceStore = favoritePlaceStore, ioDispatcher = testDispatcher)
        vm.favorites.test(timeout = 30.seconds) {
            assertEquals(emptyList(), awaitItem())
            favoritePlaceStore.add("Tokyo", GeoPoint(35.6762, 139.6503))
            assertEquals(listOf("Tokyo"), awaitItem().map { it.label })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `addFavorite writes through to the store, reaching favorites`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val vm = createVm(ioDispatcher = testDispatcher)
        vm.favorites.test(timeout = 30.seconds) {
            assertEquals(emptyList(), awaitItem())
            vm.addFavorite("Delhi", GeoPoint(28.6139, 77.2090))
            assertEquals(listOf("Delhi"), awaitItem().map { it.label })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `removeFavorite writes through to the store, reaching favorites`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val favoritePlaceStore = FavoritePlaceStore(freshDao())
        val vm = createVm(favoritePlaceStore = favoritePlaceStore, ioDispatcher = testDispatcher)
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
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val vm = createVm(ioDispatcher = testDispatcher)
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
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val vm = createVm(ioDispatcher = testDispatcher)
        vm.favorites.test(timeout = 30.seconds) {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(true, vm.canAddFavorite())
    }

    @Test fun `canAddFavorite is false on the free tier once one favorite already exists`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val vm = createVm(ioDispatcher = testDispatcher)
        vm.favorites.test(timeout = 30.seconds) {
            awaitItem()
            vm.addFavorite("Mumbai", GeoPoint(19.0760, 72.8777))
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(false, vm.canAddFavorite())
    }

    @Test fun `canAddFavorite is true regardless of count when Plus is active`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val fakeProvider = FakeEntitlementsProvider().apply { setPlusActive(true) }
        val vm = createVm(entitlementsProvider = fakeProvider, ioDispatcher = testDispatcher)
        vm.favorites.test(timeout = 30.seconds) {
            awaitItem()
            vm.addFavorite("Mumbai", GeoPoint(19.0760, 72.8777))
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(true, vm.canAddFavorite())
    }

    // --- Fix (post-Plan-5 tail, RESULTS.md round2 concern #6): ALERTS row live refresh -------------
    //
    // Device-verified root cause (98bc1cd8, Android 14, fresh install): granting POST_NOTIFICATIONS
    // through the EXTERNAL system Settings page and returning via recents (no app restart) left
    // Settings' "Alerts: Off" line stuck on "Off" even though the permission condition itself had
    // genuinely become GRANTED -- confirmed via the explainer/"Open Settings" button correctly
    // disappearing (proving reduceNotificationPermissionState's own condition->uiState half already
    // worked) while the status text stayed wrong (proving `enqueued` was the actual stale half:
    // nothing had ever called the one function that schedules the digest worker mid-session). These
    // tests TDD the ViewModel-level fix: a fake, flippable-between-calls condition reader plus a
    // spy `ensureDigestEnqueued` stand in for the two real platform calls neither jvm actual can
    // fake directly (see SettingsViewModel's own kdoc on its 3 new constructor params).

    @Test fun `alertsUiState reflects the condition read at construction`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val vm = createVm(
            ioDispatcher = testDispatcher,
            readNotificationCondition = { NotificationPermissionCondition.PERMANENTLY_DENIED },
        )
        vm.alertsUiState.test {
            assertEquals(NotificationAlertsUiState.NEEDS_SETTINGS, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `refreshAlertsState re-reads a condition that changed since construction`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        var fakeCondition = NotificationPermissionCondition.PERMANENTLY_DENIED
        val vm = createVm(ioDispatcher = testDispatcher, readNotificationCondition = { fakeCondition })
        vm.alertsUiState.test {
            assertEquals(NotificationAlertsUiState.NEEDS_SETTINGS, awaitItem())
            // Simulates a grant made in system Settings while this screen was merely paused --
            // the fake flips its answer here, same as the real requester would after the grant.
            fakeCondition = NotificationPermissionCondition.GRANTED
            vm.refreshAlertsState()
            assertEquals(NotificationAlertsUiState.ENABLED, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `refreshAlertsState leaves alertsUiState untouched when the condition hasn't changed`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val vm = createVm(
            ioDispatcher = testDispatcher,
            readNotificationCondition = { NotificationPermissionCondition.DENIED },
        )
        vm.alertsUiState.test {
            assertEquals(NotificationAlertsUiState.CAN_ASK, awaitItem())
            vm.refreshAlertsState()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `refreshAlertsState ensures the digest worker is enqueued once the condition becomes ENABLED`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        var fakeCondition = NotificationPermissionCondition.DENIED
        var fakeEnqueued = false
        var ensureCallCount = 0
        val vm = createVm(
            ioDispatcher = testDispatcher,
            readNotificationCondition = { fakeCondition },
            isDigestEnqueued = { fakeEnqueued },
            ensureDigestEnqueued = {
                ensureCallCount++
                fakeEnqueued = true
            },
        )
        vm.alertsEnqueued.test {
            assertEquals(false, awaitItem())
            fakeCondition = NotificationPermissionCondition.GRANTED
            vm.refreshAlertsState()
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, ensureCallCount)
    }

    @Test fun `refreshAlertsState never calls ensureDigestEnqueued while the condition is still not enabled`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        var ensureCallCount = 0
        val vm = createVm(
            ioDispatcher = testDispatcher,
            readNotificationCondition = { NotificationPermissionCondition.DENIED },
            ensureDigestEnqueued = { ensureCallCount++ },
        )
        vm.alertsEnqueued.test {
            awaitItem()
            vm.refreshAlertsState()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(0, ensureCallCount)
    }
}
