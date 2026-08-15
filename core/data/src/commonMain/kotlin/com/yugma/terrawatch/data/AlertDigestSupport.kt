package com.yugma.terrawatch.data

/**
 * Task 3 (Plan 4): the two pure pieces `AlertDigestWorker`'s (androidMain, `composeApp`) periodic
 * run leans on — pulled into `core:data` rather than living inline in that androidMain class so
 * both are TDD-able with zero Android/WorkManager dependency, the same "thin platform wiring over
 * a tested common core" split every other androidMain-only caller in this codebase already follows
 * for its own DAO/repository pass-throughs.
 */

/**
 * Parses the CSV persisted under the worker's own `"alert_notified_ids"` meta key back into an
 * ordered list of identifiers (oldest-notified first, matching [appendNotifiedIds]'s own append
 * order) — a null or blank value (never-run-yet, or a corrupt/hand-edited row) degrades to an
 * empty list rather than throwing, same "missing precondition degrades quietly" posture this
 * codebase's other meta readers ([AlertRuleStore]'s corrupt-value fallback, [HomeLocationStore.get])
 * already take. Tolerates stray whitespace around a comma (defensive, not load-bearing — every
 * real writer is [appendNotifiedIds] itself, which never inserts any).
 *
 * Fix Round 1 (I1): "identifiers," not "quake ids" — since that fix, an entry here can be either a
 * quake's own canonical id OR one of its per-agency [com.yugma.terrawatch.model.Quake.sources]
 * values, both fed in by the worker's own [notifiedIdentifiers] call. This function itself stays a
 * plain, opaque string-list parser either way; it has no reason to care which kind of identifier
 * any one entry is.
 */
fun parseNotifiedIds(csv: String?): List<String> =
    csv?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

/**
 * The dedupe-across-runs ring buffer: folds [newIds] onto whatever [parseNotifiedIds] recovers
 * from [existingCsv], then caps the result at [cap] entries by dropping the OLDEST ones first —
 * a plain FIFO ring buffer, not an LRU (an id's position never moves once appended; only running
 * off the front ages it out). Returns the new CSV to persist back into the SAME meta key.
 *
 * [cap] defaults to 100 (the worker's own dispatch-specified bound) — keeps the persisted string
 * from growing unboundedly over a device's lifetime while comfortably covering "how many quakes
 * could plausibly be notified-and-then-need-re-suppression within a realistic run cadence" (a
 * 45-minute period means clearing 100 entries would need roughly 75 hours of nonstop distinct
 * matches with zero repeats — far beyond what a real quake feed produces).
 *
 * [distinct] (stable, first-occurrence-wins) rather than a [Set]: guards against [newIds]
 * containing an id [existingCsv] already carries without ever producing a duplicate entry in the
 * output. This should not happen in practice — the worker's own selection step already filters
 * [newIds] down to ids NOT already in [existingCsv]'s parsed set before calling this — but costs
 * nothing to make safe against on its own terms rather than trusting the caller never to violate
 * it.
 */
fun appendNotifiedIds(existingCsv: String?, newIds: List<String>, cap: Int = 100): String {
    val merged = (parseNotifiedIds(existingCsv) + newIds).distinct()
    val trimmed = if (merged.size > cap) merged.takeLast(cap) else merged
    return trimmed.joinToString(",")
}

/**
 * Fix Round 1 (I1, review finding): every identifier [event]'s own quake is currently reachable
 * by — its current canonical [com.yugma.terrawatch.model.Quake.id] AND every per-agency id in
 * [com.yugma.terrawatch.model.Quake.sources]'s values. [DedupeEngine.merge] can change WHICH id a
 * row's own `id` field carries on a later merge (it prefers whichever side carries a
 * [com.yugma.terrawatch.model.Source.USGS] entry — see that function's own `id` selection), but
 * `sources` only ever GROWS (`existing.sources + incoming.sources`, never shrinks) — so an id this
 * worker already recorded as notified stays reachable through [com.yugma.terrawatch.model.
 * Quake.sources] on the merged row even after the row's own `id` has moved on to a different
 * agency's value.
 *
 * The single source of truth both [filterFreshAlertEvents] (the read side, checking membership)
 * and the worker's own ring-buffer append (the write side, recording membership) call — so the two
 * can never independently drift on "what counts as this event's identity."
 */
fun notifiedIdentifiers(event: AlertEvent): Set<String> = setOf(event.quake.id) + event.quake.sources.values

/**
 * Fix Round 1 (I1, review finding): the dedupe-across-runs freshness filter — an [AlertEvent]
 * counts as fresh (never notified before) only when NONE of [notifiedIdentifiers] appears in
 * [alreadyNotifiedIds]. Checking every source id, not just the row's own CURRENT [com.yugma.
 * terrawatch.model.Quake.id], is what absorbs a canonical-id swap: a same-event merge that changes
 * which agency's id becomes the row's own `id` must not look like a brand-new quake purely because
 * that one field's value changed — see [notifiedIdentifiers]'s own kdoc for why `sources` is what
 * still remembers the old id. Order-preserving (a plain filter), matching [planDigestNotifications]'s
 * own "the caller decides ordering" posture — this function's one job is inclusion, not sequencing.
 */
fun filterFreshAlertEvents(events: List<AlertEvent>, alreadyNotifiedIds: Set<String>): List<AlertEvent> =
    events.filter { event -> notifiedIdentifiers(event).none { it in alreadyNotifiedIds } }

/**
 * One worker run's notification shape: the first [individual] events get their own individual
 * notification, and [summaryExtraCount] (0 when nothing was left over) is how many MORE matched
 * events exist beyond that — the count a summary notification's own copy quotes (e.g. "N more
 * earthquakes matched your alerts"), never a full second listing.
 */
data class DigestPlan(val individual: List<AlertEvent>, val summaryExtraCount: Int)

/**
 * Splits [events] into what gets its own notification vs. what gets folded into one summary line
 * — the dispatch's own "notify max 3 individual + 1 summary if more" rule, [maxIndividual]
 * defaulted to that literal 3.
 *
 * Deliberately does NOT sort [events] itself — ordering (e.g. strongest-magnitude-first, the
 * worker's own real call site) is the CALLER's decision to make before calling this; this
 * function's one job is "given events in whatever order you've already chosen, split them," not a
 * second, competing definition of what "first" means.
 */
fun planDigestNotifications(events: List<AlertEvent>, maxIndividual: Int = 3): DigestPlan {
    val individual = events.take(maxIndividual)
    val summaryExtraCount = (events.size - individual.size).coerceAtLeast(0)
    return DigestPlan(individual, summaryExtraCount)
}
