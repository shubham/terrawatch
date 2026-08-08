package com.yugma.terrawatch.network

import com.yugma.terrawatch.model.MagRevision
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.time.ExperimentalTime

object EmscParser {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parses one EMSC standing-order WebSocket message into a [Quake].
     *
     * Returns null for anything that isn't a create/update quake event — heartbeats, malformed
     * JSON, or a payload that doesn't match the documented shape (wrong type at any level, e.g.
     * `data` as a string or `coordinates` as a non-array). Never throws.
     */
    fun parse(message: String): Quake? {
        val root = runCatching { json.parseToJsonElement(message).jsonObject }.getOrNull() ?: return null
        return runCatching { buildQuake(root) }.getOrNull()
    }

    @OptIn(ExperimentalTime::class)
    private fun buildQuake(root: JsonObject): Quake? {
        val action = root["action"]?.jsonPrimitive?.contentOrNull
        if (action != "create" && action != "update") return null
        val data = root["data"]?.jsonObject ?: return null
        val props = data["properties"]?.jsonObject ?: return null
        val unid = props["unid"]?.jsonPrimitive?.contentOrNull ?: return null
        val lat = props["lat"]?.jsonPrimitive?.doubleOrNull ?: return null
        val lon = props["lon"]?.jsonPrimitive?.doubleOrNull ?: return null
        val timeIso = props["time"]?.jsonPrimitive?.contentOrNull ?: return null
        val timeMillis = Instant.parse(normalizeIso(timeIso)).toEpochMilliseconds()
        val updatedIso = props["lastupdate"]?.jsonPrimitive?.contentOrNull
        // lastupdate is a soft field: if it's present but fails to parse, fall back to the
        // primary time rather than dropping the whole event over a non-essential field.
        val updatedMillis = updatedIso?.let { runCatching { Instant.parse(normalizeIso(it)).toEpochMilliseconds() }.getOrNull() } ?: timeMillis
        val mag = props["mag"]?.jsonPrimitive?.doubleOrNull
        val magType = props["magtype"]?.jsonPrimitive?.contentOrNull
        // one abs() per path: properties.depth is documented positive but defend anyway; the
        // geometry fallback is the negative-below-sea-level coordinate.
        val depth = props["depth"]?.jsonPrimitive?.doubleOrNull?.let { abs(it) }
            ?: data["geometry"]?.jsonObject?.get("coordinates")?.jsonArray?.getOrNull(2)
                ?.jsonPrimitive?.doubleOrNull?.let { abs(it) }
        // absent/unreadable auto -> automatic (matches USGS parser convention)
        val auto = props["auto"]?.jsonPrimitive?.booleanOrNull ?: true
        return Quake(
            id = unid,
            timeMillis = timeMillis,
            lat = lat, lon = lon,
            depthKm = depth,
            mag = mag, magType = magType,
            place = props["flynn_region"]?.jsonPrimitive?.contentOrNull ?: "Unknown location",
            tsunami = false,
            felt = null,
            status = if (auto) QuakeStatus.AUTOMATIC else QuakeStatus.REVIEWED,
            sources = mapOf(Source.EMSC to unid),
            revisions = if (mag != null) listOf(MagRevision(mag, magType, updatedMillis, Source.EMSC)) else emptyList(),
            updatedAtMillis = updatedMillis,
        )
    }

    // EMSC sends "2026-08-07T04:09:41.0Z"; Instant.parse needs an explicit timezone marker.
    // Append 'Z' only when neither 'Z' nor a trailing +HH:MM/-HH:MM offset is already present —
    // either offset sign is a complete, valid timestamp on its own and must not be touched
    // (appending 'Z' to "...-05:00" produces "...-05:00Z", which fails to parse).
    private fun normalizeIso(s: String): String =
        if (s.endsWith("Z") || Regex("[+-]\\d{2}:?\\d{2}$").containsMatchIn(s)) s else s + "Z"
}
