package com.yugma.terrawatch.data

import com.yugma.terrawatch.model.FavoriteAlertType
import com.yugma.terrawatch.model.FavoritePlace

/**
 * Task 3 (Plan 4): the two pure pieces `AlertDigestWorker`'s (androidMain, `composeApp`) periodic
 * run leans on — pulled into `core:data` rather than living inline in that androidMain class so
 * both are TDD-able with zero Android/WorkManager dependency, the same "thin platform wiring over
 * a tested common core" split every other androidMain-only caller in this codebase already follows
 * for its own DAO/repository pass-throughs.
 *
 * Task 2 (Plan 5) adds a third pure piece, [buildDigestRules] (plus its two small string-encoding
 * helpers, [favoriteRuleId]/[favoriteLabelFromRuleId]) — the worker's own multi-place evaluation,
 * extending this same "TDD-able, zero-Android-dependency" home rather than inlining the favorite-vs-
 * home rule assembly directly into `AlertDigestWorker.doWork()`.
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
 * [cap] defaults to 1000 as of Round 2 (was 100 — a review traced that value as provably too
 * small; see below). Worst-case adequacy math, not average-case, since this buffer's whole job is
 * to survive a BUSY period, not a typical one:
 *  - [notifiedIdentifiers] (this worker's own caller) appends UP TO 2 identifiers per matched
 *    quake, not 1 — this codebase's [com.yugma.terrawatch.model.Source] enum has exactly two
 *    agencies (USGS/EMSC), and a quake carrying both contributes its own id plus one more.
 *  - Nothing caps how many DISTINCT quakes one worker run's own "fresh" list can contain before
 *    reaching this function — only the NOTIFICATION display is capped (3 individual + 1 summary,
 *    see [planDigestNotifications]) — so one run's identifier contribution is open-ended under a
 *    permissive rule during an active sequence, not fixed at exactly 1 the way the ORIGINAL
 *    cap-100 version of this comment assumed ("clearing 100 entries would need roughly 75 hours of
 *    nonstop distinct matches" — true only if every run contributes exactly one identifier, which
 *    nothing in this worker's design actually guarantees).
 *  - At the digest worker's 45-minute cadence (24h / 45min = 32 runs/day) and up to 2
 *    identifiers/quake, 1000 slots exactly fill in one day at a SUSTAINED average of
 *    1000 / (32 * 2) ≈ 15.6 newly-matching quakes per run — comfortable margin over everyday
 *    activity even for a wide "near" rule (e.g. this app's own 1000 km/M3.0 device-tested config,
 *    task-3-report.md), but not a proof against a genuinely historic swarm/aftershock sequence,
 *    which no fixed-size buffer can promise against. 1000 (a 10x jump from the old 100) is a
 *    pragmatic mitigation of the SYMPTOM — this buffer being the ONLY suppression against a
 *    re-notify — not the root cause: see [com.yugma.terrawatch.data.QuakeRepository.ingest]'s
 *    missing content-diff gate (logged, docs/superpowers/plans/plan-4-backlog.md, not fixed this
 *    round) for why an unchanged quake re-enters this worker's own "fresh" candidate set on every
 *    single poll in the first place, which is what makes hitting this cap plausible at all.
 *
 * CSV size at the new cap, sanity-checked against this codebase's own real id samples
 * (core/network's `usgs_all_hour.json`/`emsc_event.json` fixtures: `"aka2026poxsgc"`/
 * `"nc75413897"`/`"us6000tj70"` at 10-13 chars, EMSC's `"20260807_0000123"` at 16) — roughly 15
 * chars/id average including the joining comma puts a full 1000-entry buffer at roughly 15,000
 * characters (~15KB) for this one `meta.value` TEXT column. Trivial for SQLite (no declared column
 * length limit, and SQLite's own default max TEXT/BLOB size is on the order of 1GB) and for an
 * in-process parse/join every 45 minutes — no size concern at this cap.
 *
 * [distinct] (stable, first-occurrence-wins) rather than a [Set]: guards against [newIds]
 * containing an id [existingCsv] already carries without ever producing a duplicate entry in the
 * output. This should not happen in practice — the worker's own selection step already filters
 * [newIds] down to ids NOT already in [existingCsv]'s parsed set before calling this — but costs
 * nothing to make safe against on its own terms rather than trusting the caller never to violate
 * it.
 */
fun appendNotifiedIds(existingCsv: String?, newIds: List<String>, cap: Int = 1000): String {
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

/**
 * Task 2 (Plan 5): [AlertRule.id] prefix an [AlertRuleEngine]-matched event uses to say "this
 * matched a favorite place, not home's own near/world rules" — [DigestNotificationCopy]'s title/body
 * builders (`composeApp`) check this to render an honest "near <label>" phrase instead of either the
 * "near you" (home) or "worldwide M6+" (world) copy, both of which would misdescribe a favorite
 * match. A plain string-prefix encoding, not a richer [AlertRule]/[AlertEvent] field of its own —
 * mirrors [DigestNotificationCopy]'s own pre-existing "near"/"world" literal-string discriminator
 * convention rather than introducing a second, parallel way to tag a rule's origin.
 */
private const val FAVORITE_RULE_PREFIX = "favorite:"

/** See [FAVORITE_RULE_PREFIX]'s own kdoc. [label] is embedded verbatim (not the favorite's numeric
 * id) — two favorites sharing an identical label is a low-severity, display-only edge case (at
 * worst, a matched favorite's notification copy names the wrong of two identically-labeled places),
 * traded for a MUCH simpler encoding/decoding pair than a delimited "id:label" scheme would need. */
fun favoriteRuleId(label: String): String = "$FAVORITE_RULE_PREFIX$label"

/** The inverse of [favoriteRuleId] — `null` for any [ruleId] that isn't one this function produced
 * (i.e. "near", "world", or any other future non-favorite rule id), so a caller can branch on
 * "was this a favorite match, and if so, which label" with one nullable call. */
fun favoriteLabelFromRuleId(ruleId: String): String? =
    ruleId.takeIf { it.startsWith(FAVORITE_RULE_PREFIX) }?.removePrefix(FAVORITE_RULE_PREFIX)

/**
 * Task 2 (Plan 5): the worker's own multi-place rule list — [homeRules] (unchanged; `AlertDigestWorker`'s
 * existing `repository.currentRules()` result, [DEFAULT_RULES]-shaped: "near" + "world", both
 * `center = null`, relying on [AlertRuleEngine.evaluate]'s own `home` fallback) come FIRST, followed
 * by one additional rule per favorite in [favorites] whose own [FavoritePlace.alertType] isn't
 * [FavoriteAlertType.OFF] (an OFF favorite contributes NOTHING — not a disabled rule, an absent one).
 *
 * **This ordering is the entire mechanism behind the "one notification per quake max, first matching
 * place wins, prefer home" dedupe ruling** — [AlertRuleEngine.evaluate]'s own `for (rule in rules)`
 * loop already returns on the FIRST rule that matches and never considers the rest, so feeding it
 * `homeRules + favoriteRules` (in that order) makes home win any overlap with a favorite, and the
 * earliest-listed of several overlapping favorites win among themselves — zero changes needed to
 * [AlertRuleEngine] itself (see AlertDigestSupportTest's own "dedupe" section for the proof, run
 * against the real engine, not just an assertion about this function's own output list).
 *
 * Each favorite's own [AlertRule] always centers on [FavoritePlace.point] (so [AlertRuleEngine.
 * evaluate]'s `home` parameter is irrelevant to it — only home's OWN `center = null` rules ever
 * consult that fallback) and always uses [favoriteRadiusKm] (the worker's own "current
 * nearbyRadiusKm setting," per this task's own dispatch — the SAME radius home's "near" rule
 * currently applies, not an independent per-favorite radius) for its `radiusKm`. Only `minMag`
 * differs by [FavoritePlace.alertType]: [FavoriteAlertType.ALL] uses [favoriteMinMag] (home's own
 * current min-magnitude setting — "existing min-mag rule semantics," per this task's own dispatch);
 * [FavoriteAlertType.MAJOR_ONLY] uses the fixed [majorOnlyMinMag] (6.0, mirroring "world"'s own fixed
 * threshold, but radius-bounded to this one favorite rather than unbounded).
 */
fun buildDigestRules(
    homeRules: List<AlertRule>,
    favorites: List<FavoritePlace>,
    favoriteRadiusKm: Double,
    favoriteMinMag: Double,
    majorOnlyMinMag: Double = 6.0,
): List<AlertRule> = homeRules + favorites.mapNotNull { favorite ->
    val minMag = when (favorite.alertType) {
        FavoriteAlertType.OFF -> return@mapNotNull null
        FavoriteAlertType.ALL -> favoriteMinMag
        FavoriteAlertType.MAJOR_ONLY -> majorOnlyMinMag
    }
    AlertRule(id = favoriteRuleId(favorite.label), minMag = minMag, radiusKm = favoriteRadiusKm, center = favorite.point)
}
