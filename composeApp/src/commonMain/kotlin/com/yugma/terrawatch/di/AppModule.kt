package com.yugma.terrawatch.di

import com.yugma.terrawatch.data.HomeLocationStore
import com.yugma.terrawatch.data.QuakeRepository
import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.feed.FeedViewModel
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
    // viewModel {} (not factory {}) — scopes FeedViewModel to the platform ViewModelStore via
    // koin-compose-viewmodel's koinViewModel<FeedViewModel>() at the App() call site, instead of
    // minting a fresh instance (and a fresh startLive() collector) on every recomposition/rotation.
    viewModel { FeedViewModel(get()) }
    // Task 8: Home is now App()'s real screen; FeedViewModel/FeedScreen stay registered/on-disk
    // unreferenced (Task 9's detail sheet may reuse FeedScreen's list internals).
    // Task 9: HomeViewModel gains the pill's home-location dependency — HomeLocationStore and
    // LocationProvider are both already singles above, so get()/get() resolve them by the
    // constructor's own parameter types, same pattern as QuakeRepository's get()-per-param above.
    viewModel { HomeViewModel(get(), get(), get()) }
}
