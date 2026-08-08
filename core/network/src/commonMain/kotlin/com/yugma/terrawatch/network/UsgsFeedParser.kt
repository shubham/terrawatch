package com.yugma.terrawatch.network

import com.yugma.terrawatch.model.MagRevision
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

object UsgsFeedParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(geojson: String): List<Quake> {
        val root = json.parseToJsonElement(geojson).jsonObject
        return root.getValue("features").jsonArray.mapNotNull { f -> feature(f.jsonObject) }
    }

    private fun feature(f: JsonObject): Quake? {
        val props = f["properties"]?.jsonObject ?: return null
        val coords = f["geometry"]?.jsonObject?.get("coordinates")?.jsonArray ?: return null
        if (coords.size < 2) return null
        val featureId = f["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val ids = props["ids"]?.jsonPrimitive?.contentOrNull
        val canonicalId = ids?.split(',')?.firstOrNull { it.isNotBlank() }?.trim() ?: featureId
        val mag = props["mag"]?.jsonPrimitive?.doubleOrNull
        val time = props["time"]?.jsonPrimitive?.longOrNull ?: return null
        val updated = props["updated"]?.jsonPrimitive?.longOrNull ?: time
        return Quake(
            id = canonicalId,
            timeMillis = time,
            lat = coords[1].jsonPrimitive.doubleOrNull ?: return null,
            lon = coords[0].jsonPrimitive.doubleOrNull ?: return null,
            depthKm = coords.getOrNull(2)?.jsonPrimitive?.doubleOrNull,
            mag = mag,
            magType = props["magType"]?.jsonPrimitive?.contentOrNull,
            place = props["place"]?.jsonPrimitive?.contentOrNull ?: "Unknown location",
            tsunami = props["tsunami"]?.jsonPrimitive?.intOrNull == 1,
            felt = props["felt"]?.jsonPrimitive?.intOrNull,
            status = if (props["status"]?.jsonPrimitive?.contentOrNull == "reviewed")
                QuakeStatus.REVIEWED else QuakeStatus.AUTOMATIC,
            sources = mapOf(Source.USGS to featureId),
            revisions = if (mag != null)
                listOf(MagRevision(mag, props["magType"]?.jsonPrimitive?.contentOrNull, updated, Source.USGS))
            else emptyList(),
            updatedAtMillis = updated,
        )
    }
}
