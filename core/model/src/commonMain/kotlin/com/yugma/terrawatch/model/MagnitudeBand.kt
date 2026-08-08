package com.yugma.terrawatch.model

enum class MagnitudeBand { LOW, MODERATE, STRONG, MAJOR, UNKNOWN }

fun magnitudeBand(mag: Double?): MagnitudeBand = when {
    mag == null -> MagnitudeBand.UNKNOWN
    mag < 3.0 -> MagnitudeBand.LOW
    mag < 4.5 -> MagnitudeBand.MODERATE
    mag < 6.0 -> MagnitudeBand.STRONG
    else -> MagnitudeBand.MAJOR
}
