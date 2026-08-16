package com.yugma.terrawatch.data

import com.yugma.terrawatch.database.QuakeStore

/**
 * "Since your last visit" tracking for the feed sheet's summary banner (feat/feed-visit-ux),
 * persisted as one scalar row in the existing key/value meta table — same "no schema change, reuse
 * [QuakeDao][com.yugma.terrawatch.database.QuakeDao]'s existing `metaGet`/`metaPut`" shape
 * [HomeLocationStore] already establishes for `home_lat`/`home_lon`.
 *
 * The write/read split is deliberately asymmetric in WHEN each side fires, not just WHERE the data
 * lives:
 * - [get] is read exactly once per process lifetime, at `HomeViewModel.init{}` — see that class's
 *   own `visitSummary` kdoc. This is what makes "fresh visit" (the summary banner's own gating
 *   condition) fall out of this app's existing architecture for free, with no separate timestamp
 *   or "is this fresh" flag needed: `HomeViewModel` is a real `ViewModel`, scoped to the single
 *   Activity's `ViewModelStore` (Koin's `viewModel {}` at the composition root — see that class's
 *   own `_startupCameraTarget` kdoc for the identical "constructed exactly once per real process
 *   start" fact already relied on there), so its `init{}` block runs once per process start
 *   (cold launch, or a process-death recreate) and never again for a mere background/foreground
 *   cycle or configuration change. Reading [get] there is therefore automatically "once per fresh
 *   visit" — never mid-session, by construction, not by a separately-tracked flag.
 * - [set] is written on `MainActivity.onStop()` (androidMain — this app's Activity lifecycle, not
 *   a commonMain composable-scoped hook, so it fires from EVERY tab/screen the user might be on
 *   when backgrounding, not only while Home happens to be composed), overwriting whatever was
 *   there with "now" — deliberately NOT written at open/`get()` time, since that would make "last
 *   visit" always read as "just now" and defeat the whole comparison. The value [get] reads back
 *   on the NEXT fresh visit is therefore always "the end of the PREVIOUS session," exactly the
 *   reference point the banner's own "N quakes since your last visit" copy promises.
 */
class VisitStore(private val dao: QuakeStore) {
    fun get(): Long? = dao.metaGet(LAST_VISIT_KEY)?.toLongOrNull()

    fun set(millis: Long) { dao.metaPut(LAST_VISIT_KEY, millis.toString()) }

    private companion object {
        const val LAST_VISIT_KEY = "last_visit_millis"
    }
}
