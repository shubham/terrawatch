package com.yugma.terrawatch.database

import com.yugma.terrawatch.model.Quake as DomainQuake
import kotlinx.coroutines.flow.Flow

/**
 * Task 9 (Plan 3), web-enablement storage-decision spike: the read/write surface
 * [com.yugma.terrawatch.data.QuakeRepository] and the four meta-table-backed stores
 * ([com.yugma.terrawatch.data.HomeLocationStore], [com.yugma.terrawatch.data.ThemeStore],
 * [com.yugma.terrawatch.data.AlertRuleStore], [com.yugma.terrawatch.data.OnboardingStore]) actually
 * call on [QuakeDao] — grepped across the whole repo, not guessed (EVIDENCE INTEGRITY): every one of
 * these 13 methods has a real production call site outside this module's own test suite.
 * [QuakeDao]'s own `upsert`/`upsertAll`/`countAll`/`delete(id)` do NOT — only `QuakeDaoTest` calls
 * them, to seed rows directly for its own assertions — and are deliberately left OFF this interface;
 * they stay `QuakeDao`-only methods. (~13, not the plan's own approximate "~12" — a recount at
 * extraction time; see task-9-report.md's spike section.)
 *
 * Exists so [InMemoryQuakeStore] can stand in for [QuakeDao] wherever a real `SqlDriver` isn't
 * available yet — currently wasmJs (see [InMemoryQuakeStore]'s own kdoc for the spike that led here:
 * SQLDelight's web-worker driver needs `generateAsync=true`, which turns every mutating call on the
 * SHARED generated `TerraWatchDb` interface `suspend` — confirmed empirically, not just reasoned
 * about, by flipping that flag and recompiling this exact module: `insertOrReplace`/`delete`/
 * `deleteByIdPrefix`/`meta_put`/`transaction` all failed to compile with "Suspend function ... can
 * only be called from a coroutine or another suspend function" — and that ripples past this module
 * entirely, into plain `Compose` `onClick`/`onFinish` lambdas in `composeApp` that call
 * `OnboardingStore.setOnboarded()`/`ThemeStore.setTheme()`/`AlertRuleStore.setNearbyRadius()` today
 * with no coroutine scope in hand; rejected as too invasive for this task's 30-minute decision gate).
 *
 * [QuakeDao] implements this unchanged — mechanical `: QuakeStore` + `override` on exactly these 13
 * methods, no behavior change on the jvm/android paths, confirmed by the full jvmTest suite staying
 * green unmodified (287 pre-existing tests, zero edits). Every real caller (`QuakeRepository`, the
 * four meta stores above) now takes this interface instead of the concrete class, so
 * `AppModule.kt`'s `appModule()` can hand it either a real [QuakeDao] (android/jvm) or an
 * [InMemoryQuakeStore] (wasmJs) with zero other code caring which.
 */
interface QuakeStore {
    fun byId(id: String): DomainQuake?

    fun recent(sinceMillis: Long): Flow<List<DomainQuake>>

    fun pageBefore(timeMillis: Long, limit: Int, minMag: Double?): List<DomainQuake>

    fun pageBetween(lowerInclusive: Long, upperExclusive: Long, minMag: Double?): List<DomainQuake>

    fun lastFetchedAtMillis(): Long?

    fun quakesPerDay(sinceMillis: Long): List<DayCount>

    fun bandDistribution(sinceMillis: Long): List<BandCount>

    fun strongest(sinceMillis: Long): DomainQuake?

    fun deleteByIdPrefix(prefix: String)

    fun metaGet(key: String): String?

    fun metaPut(key: String, value: String)

    fun replace(quake: DomainQuake)

    fun replaceAndDelete(quake: DomainQuake, deleteIds: List<String> = emptyList())
}
