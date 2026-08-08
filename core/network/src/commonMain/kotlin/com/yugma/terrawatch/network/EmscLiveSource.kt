package com.yugma.terrawatch.network

import com.yugma.terrawatch.model.Quake
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

class EmscLiveSource(
    private val http: HttpClient,
    private val url: String = "wss://www.seismicportal.eu/standing_order/websocket",
) {
    fun events(): Flow<Quake> = flow {
        var backoffMs = 1_000L
        while (true) {
            try {
                http.webSocket(url) {
                    backoffMs = 1_000L
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            EmscParser.parse(frame.readText())?.let { emit(it) }
                        }
                    }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // fall through to backoff
            }
            delay(backoffMs + Random.nextLong(0, 500))
            backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
        }
    }
}
