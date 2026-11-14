package com.mapper.geopdf.geo

import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class DistanceUnit(val prefValue: String) {
    KILOMETERS("km"),
    MILES("mi");

    companion object {
        fun fromPref(value: String?, defaultUnit: DistanceUnit = defaultForLocale()): DistanceUnit {
            return when (value) {
                KILOMETERS.prefValue -> KILOMETERS
                MILES.prefValue -> MILES
                else -> defaultUnit
            }
        }

        fun defaultForLocale(locale: Locale = Locale.getDefault()): DistanceUnit {
            return when (locale.country.uppercase(Locale.US)) {
                "US", "LR", "MM" -> MILES
                else -> KILOMETERS
            }
        }
    }
}

data class ScaleBarSpec(
    val label: String,
    val widthPx: Float,
    val groundMeters: Double
)

/**
 * Computes cartographic scale-bar intervals and unit-consistent distance/speed strings
 * in metric (m / km / km/h) or imperial (ft / mi / mph).
 */
object MapScaleFormatter {

    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val METERS_PER_FOOT = 0.3048
    private const val METERS_PER_MILE = 1609.344
    private const val MILES_PER_KM = 0.621371

    private data class Step(val meters: Double, val label: String)

    private val metricSteps = listOf(
        Step(1.0, "1 m"),
        Step(2.0, "2 m"),
        Step(5.0, "5 m"),
        Step(10.0, "10 m"),
        Step(20.0, "20 m"),
        Step(50.0, "50 m"),
        Step(100.0, "100 m"),
        Step(200.0, "200 m"),
        Step(500.0, "500 m"),
        Step(1_000.0, "1 km"),
        Step(2_000.0, "2 km"),
        Step(5_000.0, "5 km"),
        Step(10_000.0, "10 km"),
        Step(20_000.0, "20 km"),
        Step(50_000.0, "50 km"),
        Step(100_000.0, "100 km"),
        Step(200_000.0, "200 km"),
        Step(500_000.0, "500 km"),
        Step(1_000_000.0, "1000 km")
    )

    private val imperialSteps = listOf(
        Step(5.0 * METERS_PER_FOOT, "5 ft"),
        Step(10.0 * METERS_PER_FOOT, "10 ft"),
        Step(20.0 * METERS_PER_FOOT, "20 ft"),
        Step(50.0 * METERS_PER_FOOT, "50 ft"),
        Step(100.0 * METERS_PER_FOOT, "100 ft"),
        Step(200.0 * METERS_PER_FOOT, "200 ft"),
        Step(500.0 * METERS_PER_FOOT, "500 ft"),
        Step(0.25 * METERS_PER_MILE, "0.25 mi"),
        Step(0.5 * METERS_PER_MILE, "0.5 mi"),
        Step(1.0 * METERS_PER_MILE, "1 mi"),
        Step(2.0 * METERS_PER_MILE, "2 mi"),
        Step(5.0 * METERS_PER_MILE, "5 mi"),
        Step(10.0 * METERS_PER_MILE, "10 mi"),
        Step(20.0 * METERS_PER_MILE, "20 mi"),
        Step(50.0 * METERS_PER_MILE, "50 mi"),
        Step(100.0 * METERS_PER_MILE, "100 mi"),
        Step(200.0 * METERS_PER_MILE, "200 mi"),
        Step(500.0 * METERS_PER_MILE, "500 mi")
    )

    /**
     * Picks the largest crisp cartographic scale step that fits within [maxWidthPx] at the
     * current map resolution [groundMetersPerPx], returning exact pixel bar width and label.
     */
    fun computeScaleBar(
        groundMetersPerPx: Double,
        unit: DistanceUnit,
        maxWidthPx: Float
    ): ScaleBarSpec? {
        if (!groundMetersPerPx.isFinite() || groundMetersPerPx <= 0.0 || maxWidthPx <= 0f) {
            return null
        }
        val maxMeters = groundMetersPerPx * maxWidthPx
        val steps = if (unit == DistanceUnit.KILOMETERS) metricSteps else imperialSteps

        val chosen = steps.lastOrNull { it.meters <= maxMeters } ?: steps.first()
        val rawWidthPx = (chosen.meters / groundMetersPerPx).toFloat()
        val widthPx = rawWidthPx.coerceIn(28f, maxWidthPx)
        return ScaleBarSpec(
            label = chosen.label,
            widthPx = widthPx,
            groundMeters = chosen.meters
        )
    }

    fun formatSpeed(metersPerSec: Float, unit: DistanceUnit): String {
        return when (unit) {
            DistanceUnit.KILOMETERS -> String.format(Locale.US, "%.1f km/h", metersPerSec * 3.6f)
            DistanceUnit.MILES -> String.format(Locale.US, "%.1f mph", metersPerSec * 2.23694f)
        }
    }

    fun formatDistanceShort(km: Double, unit: DistanceUnit): String {
        val safeKm = km.coerceAtLeast(0.0)
        return when (unit) {
            DistanceUnit.KILOMETERS -> "${safeKm.roundToInt()} km"
            DistanceUnit.MILES -> "${(safeKm * MILES_PER_KM).roundToInt()} mi"
        }
    }

    fun haversineMeters(lat1Deg: Double, lon1Deg: Double, lat2Deg: Double, lon2Deg: Double): Double {
        val lat1 = Math.toRadians(lat1Deg)
        val lat2 = Math.toRadians(lat2Deg)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(lon2Deg - lon1Deg)
        val sinDLat = sin(dLat * 0.5)
        val sinDLon = sin(dLon * 0.5)
        val a = sinDLat * sinDLat + cos(lat1) * cos(lat2) * sinDLon * sinDLon
        val c = 2.0 * asin(sqrt(a.coerceIn(0.0, 1.0)))
        return EARTH_RADIUS_METERS * c
    }
}
