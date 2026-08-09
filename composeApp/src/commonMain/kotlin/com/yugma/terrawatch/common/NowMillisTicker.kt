package com.yugma.terrawatch.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

// How often the ticker below re-reads wall-clock time — originally HomeScreen.kt's own Fix Round 2
// review finding (a plain wall-clock read inside a @Composable body is not itself a Compose State,
// so a relative-time string/staleness verdict computed from it would otherwise freeze at whatever
// it was when some OTHER State last changed, however much real time then passed with zero new
// data).
private const val TICKER_INTERVAL_MILLIS = 30_000L

/**
 * Task 5 (Plan 3): extracted out of `home/HomeScreen.kt` (its original, Task 9-era home) the moment
 * a second consumer needed it — `history/HistoryScreen.kt`'s own [com.yugma.terrawatch.ui.components.QuakeCard]
 * rows need the identical live-advancing "now" for [com.yugma.terrawatch.ui.format.formatRelativeTime]
 * that Home's pill/banner/feed rows already did, and Insights (Task 6) will want the same for its
 * own strongest-quake card. One shared implementation rather than three private copies drifting
 * apart — same "extract on the second real consumer" call this codebase already made for
 * `home/FeedSheet.kt`'s `FeedList`/`LiveStatusRow` (Task 12, split out for `TwoPaneLayout` to reuse).
 *
 * `produceState` runs its block in a coroutine scoped to the caller's composition lifetime; looping
 * forever and re-assigning `value` every [TICKER_INTERVAL_MILLIS] is what actually triggers
 * recomposition of whatever reads this ticker — a plain `Clock.System.now()` call in a composable
 * body is not itself an observable Compose `State`.
 */
@Composable
internal fun rememberNowMillisTicker(): State<Long> =
    produceState(initialValue = currentTimeMillis()) {
        while (true) {
            delay(TICKER_INTERVAL_MILLIS)
            value = currentTimeMillis()
        }
    }

@OptIn(ExperimentalTime::class)
internal fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
