package com.yugma.terrawatch.network

import com.yugma.terrawatch.model.MagRevision
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
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

    @OptIn(ExperimentalTime::class)
    fun parse(message: String): Quake? {
        val root = runCatching { json.parseToJsonElement(message).jsonObject }.getOrNull() ?: return null
        val action = root["action"]?.jsonPrimitive?.contentOrNull
        if (action != "create" && action != "update") return null
        val data = root["data"]?.jsonObject ?: return null
        val props = data["properties"]?.jsonObject ?: return null
        val unid = props["unid"]?.jsonPrimitive?.contentOrNull ?: return null
        val lat = props["lat"]?.jsonPrimitive?.doubleOrNull ?: return null
        val lon = props["lon"]?.jsonPrimitive?.doubleOrNull ?: return null
        val timeIso = props["time"]?.jsonPrimitive?.contentOrNull ?: return null
        val timeMillis = runCatching { Instant.parse(normalizeIso(timeIso)).toEpochMilliseconds() }.getOrNull() ?: return null
        val updatedIso = props["lastupdate"]?.jsonPrimitive?.contentOrNull
        val updatedMillis = updatedIso?.let { runCatching { Instant.parse(normalizeIso(it)).toEpochMilliseconds() }.getOrNull() } ?: timeMillis
        val mag = props["mag"]?.jsonPrimitive?.doubleOrNull
        val magType = props["magtype"]?.jsonPrimitive?.contentOrNull
        val depth = props["depth"]?.jsonPrimitive?.doubleOrNull
            ?: data["geometry"]?.jsonObject?.get("coordinates")?.jsonArray?.getOrNull(2)
                ?.jsonPrimitive?.doubleOrNull?.let { abs(it) }
        val auto = props["auto"]?.jsonPrimitive?.booleanOrNull
            ?: (props["auto"]?.jsonPrimitive?.contentOrNull != "false")
        return Quake(
            id = unid,
            timeMillis = timeMillis,
            lat = lat, lon = lon,
            depthKm = depth?.let { abs(it) },
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

    // EMSC sends "2026-08-07T04:09:41.0Z"; Instant.parse needs well-formed fractions — it accepts this,
    // but some payloads omit 'Z'. Append when missing.
    private fun normalizeIso(s: String): String = if (s.endsWith("Z") || s.contains('+')) s else s + "Z"
}
