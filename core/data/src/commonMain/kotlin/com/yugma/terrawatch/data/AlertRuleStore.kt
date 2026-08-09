package com.yugma.terrawatch.data

import com.yugma.terrawatch.database.QuakeDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * Task 7 (Plan 3), USER REQUIREMENT (2026-08-09, binding): the "nearby" alert radius is
 * user-settable and now DEFAULTS TO [DEFAULT_RADIUS_KM] km (100.0 — was hardcoded 500.0 via
 * [pillStatus]'s own default parameter and [DEFAULT_RULES]'s "near" rule). Same plain
 * key/value-row-in-meta pattern [HomeLocationStore]/[OnboardingStore] already use (no schema
 * change) — two scalar rows ("rule_radiuskm"/"rule_minmag").
 *
 * Unlike [HomeLocationStore] (a synchronous `get()` PLUS a separate `updates: SharedFlow` side
 * channel), [nearbyRadiusKm]/[minMag] are themselves `Flow<Double>` properties — this class's own
 * brief calls for that shape directly, and it fits: every real consumer (`HomeViewModel`'s pill
 * wiring, `SettingsViewModel`'s slider state, `QuakeRepository.currentRules`) wants "the current
 * value, and every value it changes to after," not a one-shot read plus a separately-typed change
 * event. Both derive from the SAME [updates] signal (one "something in this store changed" pulse,
 * not one SharedFlow per field — mirrors [OnboardingStore]'s "Task 3 precedent" the brief cites for
 * `updates: SharedFlow<Unit>`): [setNearbyRadius] and [setMinMag] both just mean "re-read me," and a
 * single shared signal is simpler than keeping two independently-firing SharedFlows in sync.
 * [distinctUntilChanged] is what keeps that sharing honest — without it, calling [setMinMag] would
 * also cause [nearbyRadiusKm] to re-emit its own (unchanged) value to every collector, and vice
 * versa; see AlertRuleStoreTest's "does not re-emit when only the other field changes" pair for the
 * red-without-it/green-with-it proof.
 *
 * `replay = 1, extraBufferCapacity = 4` on [updates] matches [HomeLocationStore.updates]'s own
 * tuning verbatim — see that property's kdoc for the full reasoning (replay=1 covers a subscriber
 * that joins strictly after a set() call; extraBufferCapacity=4 covers a burst of several sets in a
 * row from a plain synchronous [MutableSharedFlow.tryEmit] caller, e.g. a Settings slider being
 * dragged).
 */
class AlertRuleStore(private val dao: QuakeDao) {
    private val _updates = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 4)
    val updates: SharedFlow<Unit> = _updates

    val nearbyRadiusKm: Flow<Double> = _updates
        .map { readRadiusKm() }
        .onStart { emit(readRadiusKm()) }
        .distinctUntilChanged()

    val minMag: Flow<Double> = _updates
        .map { readMinMag() }
        .onStart { emit(readMinMag()) }
        .distinctUntilChanged()

    fun setNearbyRadius(km: Double) {
        dao.metaPut(RADIUS_KEY, km.toString())
        _updates.tryEmit(Unit)
    }

    fun setMinMag(minMag: Double) {
        dao.metaPut(MIN_MAG_KEY, minMag.toString())
        _updates.tryEmit(Unit)
    }

    /**
     * Synchronous escape hatch for [QuakeRepository.currentRules] — the ingest hot path already
     * does several other synchronous DAO reads per call (a window query, a `byId` lookup — see
     * `QuakeRepository.ingest`'s own kdoc), so one more plain `metaGet` costs nothing and needs no
     * coroutine/Flow ceremony. Same underlying read [nearbyRadiusKm] itself does; exposed
     * separately because a `Flow`'s current value cannot be read synchronously without either
     * blocking or a `StateFlow`-style cache this store deliberately doesn't keep (see
     * [QuakeRepository.currentRules]'s own kdoc for why "read fresh, no cache" was chosen).
     */
    internal fun currentRadiusKm(): Double = readRadiusKm()

    /** See [currentRadiusKm]'s kdoc — the same synchronous escape hatch for [minMag]. */
    internal fun currentMinMag(): Double = readMinMag()

    private fun readRadiusKm(): Double = dao.metaGet(RADIUS_KEY)?.toDoubleOrNull() ?: DEFAULT_RADIUS_KM

    private fun readMinMag(): Double = dao.metaGet(MIN_MAG_KEY)?.toDoubleOrNull() ?: DEFAULT_MIN_MAG

    companion object {
        const val DEFAULT_RADIUS_KM = 100.0
        const val DEFAULT_MIN_MAG = 4.5
        private const val RADIUS_KEY = "rule_radiuskm"
        private const val MIN_MAG_KEY = "rule_minmag"
    }
}
