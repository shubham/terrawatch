package com.yugma.terrawatch.model

enum class Source { USGS, EMSC }
enum class QuakeStatus { AUTOMATIC, REVIEWED }

data class MagRevision(val mag: Double, val magType: String?, val atMillis: Long, val source: Source)

data class Quake(
    val id: String,
    val timeMillis: Long,
    val lat: Double,
    val lon: Double,
    val depthKm: Double?,
    val mag: Double?,
    val magType: String?,
    val place: String,
    val tsunami: Boolean,
    val felt: Int?,
    val status: QuakeStatus,
    val sources: Map<Source, String>,
    val revisions: List<MagRevision>,
    val updatedAtMillis: Long,
)
