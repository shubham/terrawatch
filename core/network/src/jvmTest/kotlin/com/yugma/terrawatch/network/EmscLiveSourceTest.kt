package com.yugma.terrawatch.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertFalse

// Task 10: EmscLiveSource.connected must start false — HomeUiState.isLive (via
// QuakeRepository.liveConnected) now binds directly to this instead of the old "startLive() was
// called" placeholder, so a source that has never even attempted a connection must never read as
// live. The true/false FLIPS themselves live inside events()'s reconnect loop, driven by a real
// WebSocket session actually opening/closing — exercising that honestly needs a fake WS server (or
// a fakeable ktor engine hook), neither of which exists in this project yet; the Task 10 brief and
// plan-2-entry-conditions.md both call this out as Plan-2-integration scope, not this task's. This
// is deliberately the minimum honest slice: initial value + type, nothing more claimed.
class EmscLiveSourceTest {
    @Test fun `connected starts false before any connection attempt`() {
        val source = EmscLiveSource(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }))
        assertFalse(source.connected.value)
    }
}
