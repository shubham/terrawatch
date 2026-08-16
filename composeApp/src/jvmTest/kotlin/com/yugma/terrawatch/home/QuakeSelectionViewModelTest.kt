package com.yugma.terrawatch.home

// Task 3 (Plan 3): migrated out of HomeViewModelTest.kt's Task 11 selection tests -- see
// QuakeSelectionViewModel's own kdoc for why selection/detail-sheet state now lives in its own
// ViewModel rather than HomeViewModel. Same jvmTest-not-commonTest rationale as
// HomeViewModelTest/FeedViewModelTest: fakeRepository() below builds a real QuakeRepository over
// app.cash.sqldelight's JDBC in-memory driver, a JVM-only artifact.
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.yugma.terrawatch.data.QuakeRepository
import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.database.TerraWatchDb
import com.yugma.terrawatch.model.MagRevision
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import com.yugma.terrawatch.network.EmscLiveSource
import com.yugma.terrawatch.network.UsgsApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
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
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class QuakeSelectionViewModelTest {
    // Same leak-prevention discipline as HomeViewModelTest's tearDown (Task 13 flake fix) --
    // select()'s viewModelScope.launch{} coroutine must not survive past its own test.
    //
    // Flake-hardening pass (2026-08-16, sweeping the terrawatch flaky-test playbook -- see
    // HomeViewModelTest's own kdoc for the original Task-13/commit-5e9e922 precedent this ports):
    // freshRepository() never pins QuakeRepository's `ioDispatcher`, so select()'s
    // `repository.byId(id)` call (the value every test below awaits via `vm.selectedQuake`) hops
    // through a real, uncontrolled `Dispatchers.Default` thread. All six tests below now carry
    // `timeout = 30.seconds` for the same starved-CI-runner margin commit 5e9e922 first established.
    private val createdViewModels = mutableListOf<QuakeSelectionViewModel>()

    private fun createVm(
        repository: QuakeRepository,
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ): QuakeSelectionViewModel =
        QuakeSelectionViewModel(repository, savedStateHandle).also { createdViewModels += it }

    @AfterTest fun tearDown() {
        Dispatchers.resetMain()
        runBlocking { createdViewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        createdViewModels.clear()
    }

    // Migrated verbatim (behavior-wise) from HomeViewModelTest's `select populates selectedQuake
    // from the repository when the quake exists` -- repository.ingest() seeds the quake directly
    // (bypassing network entirely) since this ViewModel, unlike HomeViewModel, owns no refresh
    // loop of its own; its only relationship to QuakeRepository is the byId() DAO pass-through.
    @Test fun `select populates selectedQuake from the repository when the quake exists`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val repository = freshRepository()
        repository.ingest(freshQuake("us1234"))
        val vm = createVm(repository)
        vm.selectedQuake.test(timeout = 30.seconds) {
            assertEquals(null, awaitItem())
            vm.select("us1234")
            val selected = awaitItem()
            assertEquals("us1234", selected?.id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Migrated from HomeViewModelTest. Not-found proven as an active transition (a prior
    // selection reverting to null on a second, unknown-id select()) rather than "was already null
    // and I did nothing" -- see the original test's own kdoc for why that distinction matters.
    @Test fun `select sets selectedQuake to null when the id is not found`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val repository = freshRepository()
        repository.ingest(freshQuake("us1234"))
        val vm = createVm(repository)
        vm.selectedQuake.test(timeout = 30.seconds) {
            assertEquals(null, awaitItem())
            vm.select("us1234")
            assertEquals("us1234", awaitItem()?.id)
            vm.select("does-not-exist")
            assertEquals(null, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Migrated from HomeViewModelTest.
    @Test fun `dismissSelection clears selectedQuake back to null`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val repository = freshRepository()
        repository.ingest(freshQuake("us1234"))
        val vm = createVm(repository)
        vm.selectedQuake.test(timeout = 30.seconds) {
            assertEquals(null, awaitItem())
            vm.select("us1234")
            assertEquals("us1234", awaitItem()?.id)
            vm.dismissSelection()
            assertEquals(null, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // New behavior this task adds: select()/dismissSelection() now also read/write
    // SavedStateHandle["selected_id"] (the literal key -- hardcoded here rather than importing
    // QuakeSelectionViewModel's private companion constant, same "assert against the literal
    // persisted key" convention HomeLocationTest's `corrupt stored lat...` test uses for
    // HomeLocationStore's own meta keys). Asserted directly against the handle, not just via
    // selectedQuake's own behavior, since a bug that left selectedQuake correct but the handle
    // stale/unwritten would still pass every test above while breaking the actual restore-across-
    // process-death feature this key exists for.
    @Test fun `select writes the id into the handle, dismissSelection clears it`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val repository = freshRepository()
        repository.ingest(freshQuake("us1234"))
        val handle = SavedStateHandle()
        val vm = createVm(repository, handle)
        vm.selectedQuake.test(timeout = 30.seconds) {
            assertEquals(null, awaitItem())
            assertNull(handle.get<String>("selected_id"))

            vm.select("us1234")
            awaitItem()
            assertEquals("us1234", handle.get<String>("selected_id"))

            vm.dismissSelection()
            awaitItem()
            assertNull(handle.get<String>("selected_id"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Task 4 (Plan 3) -- Task 3 ledger minor ("select() never clears handle key on null lookup --
    // dedupe-deleted id sticks and re-fails every restore"): select() used to write the handle key
    // BEFORE the repository lookup resolved and never revisited it once that lookup came back
    // null, so an id that no longer resolves (aged out, or dedupe-superseded since whatever tap
    // produced it) stuck in the handle forever -- every future process-death relaunch's init{}
    // restore (below) would re-attempt that exact same doomed select() call again, permanently.
    // Asserted directly against the handle (not just selectedQuake, which the pre-fix code already
    // got right) for the same reason `select writes the id...` above does: a bug that left
    // selectedQuake correct while leaving the handle stale would still pass every other test here.
    //
    // Red (pre-fix): the final assertNull(handle...) fails -- handle still holds "does-not-exist".
    @Test fun `select clears the handle key when a later id does not resolve`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val repository = freshRepository()
        repository.ingest(freshQuake("us1234"))
        val handle = SavedStateHandle()
        val vm = createVm(repository, handle)
        vm.selectedQuake.test(timeout = 30.seconds) {
            assertEquals(null, awaitItem())
            vm.select("us1234")
            assertEquals("us1234", awaitItem()?.id)
            assertEquals("us1234", handle.get<String>("selected_id"))

            vm.select("does-not-exist")
            assertEquals(null, awaitItem())
            assertNull(handle.get<String>("selected_id"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    // The task's headline new behavior: a SavedStateHandle carrying a previously-selected id (the
    // shape Android hands back after process death + a system-initiated relaunch restores the
    // same handle) must re-populate selectedQuake from the repository during init, with no
    // explicit select() call from the caller. This is what actually lets the detail sheet survive
    // an `am kill` + relaunch on Android (see this task's device verification) -- pre-Task-3,
    // HomeViewModel's selectedQuake had no persistence at all and always started null.
    //
    // Red (pre-fix): QuakeSelectionViewModel doesn't exist yet / has no init-time restore, so
    // selectedQuake would stay null forever and this test would time out waiting for a non-null
    // item.
    @Test fun `a handle pre-seeded with a selected id restores selectedQuake from the repository on init`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val repository = freshRepository()
        repository.ingest(freshQuake("us1234"))
        val handle = SavedStateHandle(mapOf("selected_id" to "us1234"))
        val vm = createVm(repository, handle)
        vm.selectedQuake.test(timeout = 30.seconds) {
            var s = awaitItem()
            while (s == null) s = awaitItem()
            assertEquals("us1234", s.id)
            cancelAndIgnoreRemainingEvents()
        }
    }
}

// Builds a real QuakeRepository over an in-memory JVM SQLDelight driver. The MockEngine is never
// actually invoked by any test above (none of them call refreshFeed()/startLive()) -- it exists
// only to satisfy QuakeRepository's constructor, same shape HomeViewModelTest's own
// fakeRepositoryAlwaysFailing() uses for the same reason.
private fun freshRepository(clock: () -> Long = { 2_000_000L }): QuakeRepository {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    TerraWatchDb.Schema.create(driver)
    val dao = QuakeDao(TerraWatchDb(driver))
    val engine = MockEngine { respond("", HttpStatusCode.InternalServerError) }
    return QuakeRepository(
        UsgsApi(HttpClient(engine)),
        EmscLiveSource(HttpClient(engine)),
        dao,
        clock = clock,
    )
}

private fun freshQuake(id: String, timeMillis: Long = 1_950_000) = Quake(
    id = id, timeMillis = timeMillis, lat = 1.0, lon = 2.0, depthKm = 5.0,
    mag = 4.0, magType = "mw", place = "Fresh", tsunami = false, felt = null,
    status = QuakeStatus.AUTOMATIC, sources = mapOf(Source.USGS to id),
    revisions = listOf(MagRevision(4.0, "mw", timeMillis, Source.USGS)),
    updatedAtMillis = timeMillis,
)
