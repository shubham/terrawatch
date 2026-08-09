package com.yugma.terrawatch.network

import com.yugma.terrawatch.model.Quake
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

class EmscLiveSource(
    private val http: HttpClient,
    private val url: String = "wss://www.seismicportal.eu/standing_order/websocket",
) {
    // Task 10: truthful "is the live WebSocket actually open right now" signal — HomeUiState.isLive
    // (via QuakeRepository.liveConnected) binds directly to this instead of the old "startLive() was
    // called" placeholder. True the instant a session is established (first line inside the
    // http.webSocket{} block below), false the moment that session ends for ANY reason — graceful
    // return or thrown exception, both handled below. Structure-level tested only (jvmTest asserts
    // the initial false); the true/false flips themselves need a fake WS server to exercise honestly
    // and are Plan-2-integration scope (see docs/superpowers/plans/plan-2-entry-conditions.md).
    //
    // Fix Round 1 (I1, "zombie LIVE"): the flip to false above depends entirely on the `for (frame
    // in incoming)` loop actually ending — either the server sends a WS close frame, or the
    // underlying TCP connection itself errors out. Neither happens for a socket that's gone dark
    // silently: a NAT/carrier/proxy timeout, a phone moving out of range, or a server that simply
    // stops writing without closing cleanly all leave the client-side socket object looking
    // perfectly healthy — `incoming` just never produces another frame, `for (frame in incoming)`
    // parks forever, `_connected` never flips back to false, and the LIVE dot claims a connection
    // that no longer exists, indefinitely. This is a client-observable liveness gap that no amount
    // of code in this loop alone can close: nothing available to `events()` distinguishes "no
    // frames because nothing new happened" from "no frames because the socket is dead." The fix
    // lives one layer down, at both `HttpClient` construction sites (MainActivity.kt, jvmMain's
    // main.kt): `install(WebSockets) { pingIntervalMillis = 30_000 }` makes ktor itself send a
    // ping frame on that cadence and require a pong back — a socket that's actually dead fails to
    // pong, ktor surfaces that as an exception out of the `http.webSocket { ... }` session block,
    // and this file's existing `catch (_: Throwable)` branch below (already present pre-Fix-Round-1
    // for ordinary connection failures) flips `_connected.value = false` and falls through to the
    // reconnect/backoff path exactly as it already does for any other socket failure — no new
    // branch needed here, just a real failure signal reaching the branch that was already correct.
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    fun events(): Flow<Quake> = flow {
        var backoffMs = 1_000L
        while (true) {
            // Whether THIS attempt's session ever actually reached open — used below to decide
            // whether backoff resets. Re-declared fresh every loop iteration.
            var openedThisAttempt = false
            try {
                http.webSocket(url) {
                    _connected.value = true // first line inside the session lambda, per this task's brief
                    openedThisAttempt = true
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            EmscParser.parse(frame.readText())?.let { emit(it) }
                        }
                    }
                }
                // Normal return = the session closed gracefully (server- or network-initiated, not
                // an exception) — still "was connected" for backoff purposes, see below.
                _connected.value = false
            } catch (ce: kotlinx.coroutines.CancellationException) {
                _connected.value = false
                throw ce
            } catch (_: Throwable) {
                _connected.value = false
                // fall through to backoff
            }
            // Plan 1 ledger minor ("EMSC backoff ratchets one level on graceful (non-error) socket
            // close"): only reset the fast path if a session in THIS attempt actually reached open —
            // an attempt that never connects at all (offline / proxy-blocked / DNS down) must keep
            // climbing toward the 60s ceiling instead of spinning back to 1s on every failed try. Not
            // restructuring the loop further per this task's brief: a session that opens then drops
            // gracefully still costs one full reconnect cycle before resuming (the delay below still
            // runs even on a graceful close), just at the fast 1s rate rather than a ratcheted-up one.
            if (openedThisAttempt) backoffMs = 1_000L
            delay(backoffMs + Random.nextLong(0, 500))
            backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
        }
    }
}
