package com.yugma.terrawatch.data

import com.yugma.terrawatch.database.QuakeDao

/**
 * The first-run gate (Task 4, Plan 3): "first-run flag in meta (\"onboarded\"=true) → if absent,
 * nav starts at onboarding route" (plan Task 4 brief). Same shape as [HomeLocationStore] — a plain
 * key/value row in the existing meta table, no schema change, no new persistence mechanism to
 * introduce for what is otherwise a single boolean.
 *
 * Deliberately dumb: no [kotlinx.coroutines.flow.Flow]/reactive surface like
 * [HomeLocationStore.updates] — unlike home location (which can change mid-session via a grant or
 * a city pick, and needs to reach an already-composed pill), "onboarded" only ever flips false ->
 * true exactly once per install, from [com.yugma.terrawatch.data]'s one call site (the onboarding
 * screen's "Get started"/skip action), and is read exactly once, at [AppNav][com.yugma.terrawatch]
 * 's own composition start to pick a start destination — nothing needs to observe it changing
 * live.
 */
class OnboardingStore(private val dao: QuakeDao) {
    fun isOnboarded(): Boolean = dao.metaGet(ONBOARDED_KEY) == "true"

    fun setOnboarded() {
        dao.metaPut(ONBOARDED_KEY, "true")
    }

    private companion object {
        const val ONBOARDED_KEY = "onboarded"
    }
}
