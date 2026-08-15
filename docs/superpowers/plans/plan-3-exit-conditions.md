# Plan 3 Exit Conditions

Closed out of Plan 3's final whole-branch review (branch `feat/plan-3-screens`, 2026-08-15) — the
Task 14 deliverable that review gates (see
`.superpowers/sdd/2026-08-09-terrawatch-plan-3-screens/task-14-brief.md`). None block the Plan 3
merge; each binds Plan 4 at the point it touches the named area — same convention
`plan-2-entry-conditions.md`/`plan-3-entry-conditions.md` already established for their own carries.

F3 (three false/misplaced README feature claims — phantom pull-to-refresh, infinite scroll
misattributed to the feed sheet instead of History, "full searchable" archive that has no search)
closed in full this wave (969e186); not carried below.

## Carried Importants

1. **F1: DB retention policy** — `quake` table (`core/database/.../Quake.sq`) has no
   pruning/retention query, only per-id `delete` and the debug-only `deleteByIdPrefix`; every
   ingested row (live feed + full FDSN archive backfill via History's deep-scroll) accumulates
   forever. Product decision needed: History's "cached pages browse offline" contract
   (`QuakeRepository.loadArchivePage`'s own kdoc) implicitly wants old rows kept, which is in
   tension with any future pruning. Filed to `plan-4-backlog.md`'s "From Plan 3 final review"
   section; not fixed this wave.
2. ✅ **F2: unindexed full-table `MAX()` on a hot path** (a0452ff) —
   `CREATE INDEX quake_fetchedAt ON quake(fetchedAtMillis)` added next to the existing
   `quake_time`/`quake_mag` indexes. Caveat, noted in that commit's body: this schema has no
   migration path (single-version SQLDelight, no `.sqm` files) — `DriverFactory.jvm.kt`'s explicit
   `!dbFile.exists()` gate and `DriverFactory.android.kt`'s `AndroidSqliteDriver` (its `onCreate`
   fires only when the underlying DB file doesn't exist yet) both mean `Schema.create()`, and so
   this index, only ever runs for a brand-new DB file. Any already-installed on-device DB —
   including whatever's already on the 98bc1cd8 device from earlier Plan 2/3 verification passes —
   keeps querying `lastFetchedAt` unindexed until that install is wiped or reinstalled. Accepted
   pre-release: no shipped user base yet, so no real migration is being skipped for anyone.
3. ✅ **F4: Settings slider main-thread DB writes** (a0452ff) —
   `SettingsViewModel.setNearbyRadius`/`setMinMag` now
   `viewModelScope.launch(Dispatchers.Default) { ... }`, matching this same class's own
   `homeLocationStore.get()` convention in its `init` block. jvmTest count for this module is
   unchanged (8 `SettingsViewModelTest` cases, all still green).
4. **F5: archive ingest must not feed alert evaluation before notifications land** —
   `QuakeRepository.loadArchivePage` (History's deep-scroll backfill) runs every paged-in row
   through the exact same `ingest()` → `AlertRuleEngine.evaluate()` path the live/refresh loop
   uses — that function's own kdoc already flags this. Harmless today because nothing consumes
   `alertEvents` yet, but the instant Plan 4 wires real notifications off that stream, a user
   deep-scrolling History past old M6+ quakes will notification-storm on events years old.
   `AlertRuleEngine` has no recency window of its own. Filed to backlog; not fixed this wave.
5. **M1: torn write** — `HomeLocationStore.set()` (`core/data/.../HomeLocation.kt`) writes
   `home_lat`/`home_lon` as two separate, non-atomic `dao.metaPut` calls. A `get()` racing between
   them can read a torn point (new lat paired with stale lon). Carried since Plan 2 —
   `plan-3-entry-conditions.md`'s own "Investigate" ledger already flagged the identical
   "non-atomic home_lat/lon metaPuts" as a minor; still unfixed at Plan 3's close, now raised to an
   Important.
6. **M2: unclamped radius read** — `AlertRuleStore.readRadiusKm()`/`setNearbyRadius()` accept and
   return any `Double`, unclamped. The app's only real bound today (50–1000km, `SettingsScreen`'s
   `RADIUS_STEPS_KM = [50, 100, 250, 500, 1000]`) is a UI-layer slider-snap convenience, not a
   stored-value guarantee — the store itself has no defensive floor/ceiling, so a corrupted row or
   any future non-slider caller round-trips an out-of-range value straight through to every reader
   (the pill, `QuakeRepository.currentRules`) with no validation.
7. **M3: collector waste** — `AlertRuleStore.nearbyRadiusKm` and `.minMag` both derive from the
   SAME `_updates` `SharedFlow` (by that class's own kdoc: "both derive from the SAME updates
   signal"). `HomeViewModel` and `SettingsViewModel` each independently `collect{}` BOTH flows in
   their own `init` blocks — 4 separate subscriptions across just these two screens today, more per
   additional screen. Every store mutation re-triggers a `dao.metaGet` read on every one of those
   collectors regardless of which field actually changed; `distinctUntilChanged` only filters the
   redundant final *emission*, not the redundant *read* upstream of it.
8. **M4: pill/world-rule vocabulary split** — `PillStatus.pillStatus()` (Home's status pill) only
   ever reflects a home-relative "near" check; it has no notion of `AlertRuleEngine.DEFAULT_RULES`'s
   independent "world" rule (M6.0+, unbounded radius — any major quake anywhere on Earth). A
   world-rule match already populates `alertEvents` today with zero visible effect. Decide the
   reconciliation (a third pill state for world-rule matches, or notifications scoped to near-rule
   only) at notifications (Plan 4). Also filed to backlog for visibility.

## Rulings that stand (do not re-litigate)

- Android-only scope directive (2026-08-10, binding): all verification happens on the physical
  98bc1cd8; desktop/web stay compile-green only, zero runtime-feature or polish effort until
  re-opened. Plan 4 scoping is Android-first.
- Nearby-radius default is 100km (was 500km), user-settable 50–1000km via Settings — Task 7 USER
  REQUIREMENT, not a candidate for reversion.
- `refreshFailed`'s "latest-STARTED attempt wins" semantics (a user `retryNow()` supersedes an
  in-flight poll's verdict, not just the reverse) — Plan 3 Task 2 review ruling, accepted as
  intended, self-healing behavior, not a bug.
- Alert oscillation refire (a down-then-up recross fires again), `queryArchive` throws by design
  (History's own UI wraps it), USGS-magnitude-over-EMSC preference, absent-EMSC-`auto` →
  `AUTOMATIC` — all Plan 1 rulings, untouched by Plan 3, still stand.
- Debug inject hook (`debug-` id namespace, `ingestDebugBypassingDedupe` bypass path, unconditional
  purge-on-init, no `AlertRuleEngine` evaluation for fakes) — unchanged shape, still the accepted
  design.
- Ad-slot Spacer and all monetization/notification wiring stay deferred to Plan 4 in full (spec
  §8/§10); nothing in Plan 3 started them.
- Desktop live map (JDK 25 toolchain path) and wasm `SqlDriver` remain explicitly out of scope
  until the Android-only directive is lifted — a standing decision, not a Plan 3 gap.

## Environment notes (this machine, not the code)

- **Git identity / gitconfig access:** reading this machine's real `~/.gitconfig` from this agent
  environment is unreliable (macOS TCC-style access friction) — every git invocation in this repo
  sets `GIT_CONFIG_GLOBAL=/tmp/tw-gitconfig` first (a minimal stand-in carrying only the
  `gh auth git-credential` passthrough, no identity of its own). The real identity lives in THIS
  repo's own `.git/config` (personal GitHub — Shubham Mishra / mishra.shubham5208@gmail.com — never
  the Paytm work account): never rely on the machine's real global config, which may resolve to the
  work identity instead. `gh auth status` lists both a `shubham` (personal, active) and a
  `shubham-pml` account side by side on this machine — a live footgun if `GIT_CONFIG_GLOBAL` is
  ever left unset before a commit/push.
- **Local JDK/Gradle mismatch:** this Mac's default `java`/`JAVA_HOME` (Android Studio's bundled
  JBR) is JDK 25, which this project's pinned Kotlin (2.0.21) cannot run under —
  `./gradlew` fails immediately (`IllegalArgumentException: 25.0.2` inside the bundled
  Kotlin/IntelliJ `JavaVersion.parse`) before any task even starts, configuration cache or not.
  Point `JAVA_HOME` at one of the two JBRs already installed under
  `~/Library/Java/JavaVirtualMachines/` (`jbr-17.0.14` matches CI's Temurin 17 exactly; `jbr-21.0.11`
  also present) for any local Gradle invocation.
- **OxygenOS blocks shell automation, including for a11y passes:** `pm clear` and forced rotation
  both fail via `adb shell` on the 98bc1cd8 device (carried from Plan 2) — this reaches
  TalkBack/rotation verification too, since neither can be scripted for a fresh-install or
  configuration-change regression check; those cases either run manually on-device or substitute
  emulator-5554 (whose own map canvas renders black under this machine's Zscaler proxy — good for
  chrome/UI checks there, not for anything map-rendering).
- **Device 98bc1cd8 conventions:** OnePlus 9R (`LE2101`), the sole real-device verification target
  per the Android-only directive. Every task that changes visible UI ends with install + interact +
  screenshot on this device (`adb -s 98bc1cd8` or `ANDROID_SERIAL=98bc1cd8`); emulator-5554 is now a
  fallback only for the specific cases physically impossible on-device (the OxygenOS shell blocks
  above), not a parallel pass — a change from Plan 2/3's own "AND emulator-5554" mandate, superseded
  by the 2026-08-10 directive.
