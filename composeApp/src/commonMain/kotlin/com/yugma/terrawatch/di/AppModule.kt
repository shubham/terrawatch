package com.yugma.terrawatch.di

import com.yugma.terrawatch.data.HomeLocationStore
import com.yugma.terrawatch.data.QuakeRepository
import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.home.HomeViewModel
import com.yugma.terrawatch.location.LocationProvider
import com.yugma.terrawatch.network.EmscLiveSource
import com.yugma.terrawatch.network.UsgsApi
import io.ktor.client.HttpClient
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

// Platform entry points supply: HttpClient (engine differs), QuakeDao (driver differs), and
// LocationProvider (android needs a Context, jvm/wasmJs need nothing — see LocationProvider.kt's
// no-declared-constructor rationale, mirroring DriverFactory).
// kotlinx.datetime.Clock is now a deprecated typealias for kotlin.time.Clock (Kotlin 2.1+, still
// @ExperimentalTime as of Kotlin 2.2) — same migration as kotlinx.datetime.Instant elsewhere in
// this codebase (see EmscParser.kt). Importing the stdlib type directly avoids a typealias
// nested-object resolution quirk seen when going through the kotlinx.datetime alias.
@OptIn(ExperimentalTime::class)
fun appModule(http: HttpClient, dao: QuakeDao, locationProvider: LocationProvider): Module = module {
    single { UsgsApi(http) }
    single { EmscLiveSource(http) }
    single { dao }
    single { QuakeRepository(get(), get(), get(), clock = { Clock.System.now().toEpochMilliseconds() }) }
    single { HomeLocationStore(get()) }
    single { locationProvider }
    // viewModel {} (not factory {}) — scopes HomeViewModel to the platform ViewModelStore via
    // koin-compose-viewmodel's koinViewModel<HomeViewModel>() at the App() call site, instead of
    // minting a fresh instance (and a fresh startLive() collector) on every recomposition/rotation.
    // Task 9: HomeViewModel gains the pill's home-location dependency — HomeLocationStore and
    // LocationProvider are both already singles above, so get()/get() resolve them by the
    // constructor's own parameter types, same pattern as QuakeRepository's get()-per-param above.
    // Fix Round 1 (entangled minor): FeedViewModel/FeedScreen registration removed — dead since
    // Task 8 made Home the app's real screen (see App.kt); nothing has referenced either since,
    // and they carried a latent second startLive() collector (see FeedViewModel's own former
    // init{}) that this repository never needed twice. Both deleted outright, not just
    // unregistered — see task-10-report.md's Fix Round 1 for the removal record.
    viewModel { HomeViewModel(get(), get(), get()) }
}
