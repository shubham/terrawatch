# Plan 2 Entry Conditions

Carried out of Plan 1's final whole-branch review (branch `feat/plan-1-foundation`, 2026-08-08). None blocked the Plan 1 merge; each must be addressed at the point Plan 2 touches the named area.

## Must fix early in Plan 2

1. **Dispatch ingest off the main thread** — `QuakeDao` is blocking; `FeedViewModel.init` currently runs `refreshFeed()` (~300-400 sequential SQLite transactions on a cold `all_day` ingest) and every WebSocket event write on `Dispatchers.Main`. Fix before building the real Home screen: `withContext(Dispatchers.Default)` around ingest paths or suspend-ify the DAO. (Plan 1's throwaway slice tolerated dropped frames; a map-first Home will not.)
2. **Serialize `ingest()`** — the window-query → reconcile → write sequence is not atomic across coroutines; only the final write is transactional. Becomes first-class the moment the 60s poll timer runs beside the WS collector. One-line fix: `Mutex.withLock` in `ingest()`. Must land with or before the poll loop.
3. **ViewModel lifecycle** — `FeedViewModel` is obtained via bare `koin.get()` outside any `ViewModelStore`; rotation re-creates it and leaks the previous WS collector. Plan 2 replaces the screen: wire `viewmodel-compose` (or koin-compose) properly. Also: `MainActivity` constructs `QuakeDao` + `HttpClient` before the Koin re-start guard — move construction inside it.
4. **`fetchedAtMillis` is a lie** — it's written as `updatedAtMillis` (upstream stamp), not local clock. No Plan 1 reader exists. Any staleness logic ("updated 23 min ago" chip) must first wire a real clock into `QuakeDao.toRow()`.

## Fix when touching the area

- `EmscLiveSource` requires the injected Ktor client to have the WebSockets plugin installed — implicit constructor contract; add KDoc + consider an `init` check. Reconnect loop is still integration-untested (Plan 2 owns the WS integration test per plan).
- EMSC backoff ratchets one level on graceful (non-error) socket close — benign; comment or reset.
- `alertEvents` `tryEmit` drops silently past buffer 16 and has no replay — fine with no subscribers; revisit when notifications land.
- `FeedUiState` has no `Empty` variant (spec §5.2 wants Loading/Content/Empty/Error on every screen) — Plan 2's real screens must implement all four.
- `isLive` is hardcoded `true` (TODO in code) — reflect actual WS connection state.
- Dedupe window: ±90s boundary is inclusive in `DedupeEngine` but exclusive at the top of the repository's `pageBefore` pre-filter (1 ms hole); candidate query `limit 50` can truncate dense aftershock bands.
- `Fresh` feed response with no ETag header leaves the previous etag in meta.
- `refreshFeed()` can throw (DB errors) despite returning a status enum — wrap when the poll loop lands.
- Enum `valueOf` round-trips in DAO JSON have no rename fallback — matters at the first schema/enum migration; consider a schema-version pragma then too (interrupted-DDL half-schema note from Task 6).
- Revision dedup key omits `magType`; `pickMagnitudeHolder` name undersells scope (also picks time/coords/depth).
- Converge on `kotlin.time.Instant` everywhere (EmscParser still imports the deprecated `kotlinx.datetime` typealias).
- Drop unused deps: `ktor-client-content-negotiation` + `ktor-serialization-kotlinx-json` in core:network (manual parsing); `sqldelight-webworker-driver` catalog entry stays parked for Plan 3 web storage.
- CI: `actions/setup-java@v4` → `@v5`.

## Rulings that stand (do not re-litigate)

- Alert oscillation refire (down-then-up recross fires again) = accepted v1 behavior, false-negative-averse.
- `queryArchive` throws by design (History UI wraps); `fetchFeed` returns `FeedResult` because polling must never crash.
- Absent EMSC `auto` field → `AUTOMATIC` status.
- USGS magnitude preferred over higher same-status EMSC magnitude (agency authority order).
- Desktop/web = ad-free by nature; wasm DB persistence lands Plan 3 (`DriverFactory.wasmJs` throws by design).

## Environment notes (this machine, not the code)

- Corp Zscaler proxy TLS-intercepts: JVM/emulator need the Zscaler root CA in their truststore for live data (`~/.gradle/zscaler-root-ca.pem`; desktop: `-Djavax.net.ssl.trustStore`). Tests never need network. Consider a debug-only `network_security_config` trusting user CAs so the emulator can demo live data behind the proxy — never in release.
- Node/Yarn (wasm builds) need `NODE_EXTRA_CA_CERTS`/`~/.yarnrc` pointing at the same CA (already configured machine-level).
