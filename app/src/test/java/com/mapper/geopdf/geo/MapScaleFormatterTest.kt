package com.mapper.geopdf.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

class MapScaleFormatterTest {

    @Test
    fun computeScaleBar_selectsCleanMetricAndImperialIntervalsWithinMaxWidth() {
        val metric1km = MapScaleFormatter.computeScaleBar(
            groundMetersPerPx = 10.0,
            unit = DistanceUnit.KILOMETERS,
            maxWidthPx = 220f
        )
        assertNotNull(metric1km)
        requireNotNull(metric1km)
        assertEquals("2 km", metric1km.label)
        assertEquals(200f, metric1km.widthPx, 0.5f)

        val metricSmall = MapScaleFormatter.computeScaleBar(
            groundMetersPerPx = 3.0,
            unit = DistanceUnit.KILOMETERS,
            maxWidthPx = 200f
        )
        requireNotNull(metricSmall)
        assertEquals("500 m", metricSmall.label)
        assertEquals(500f / 3f, metricSmall.widthPx, 0.5f)

        val imperial1mi = MapScaleFormatter.computeScaleBar(
            groundMetersPerPx = 10.0,
            unit = DistanceUnit.MILES,
            maxWidthPx = 220f
        )
        requireNotNull(imperial1mi)
        assertEquals("1 mi", imperial1mi.label)
        assertEquals(160.9344f, imperial1mi.widthPx, 0.5f)

        val imperialFeet = MapScaleFormatter.computeScaleBar(
            groundMetersPerPx = 1.0,
            unit = DistanceUnit.MILES,
            maxWidthPx = 200f
        )
        requireNotNull(imperialFeet)
        assertEquals("500 ft", imperialFeet.label)
        assertEquals(152.4f, imperialFeet.widthPx, 0.5f)
    }

    @Test
    fun computeScaleBar_rejectsInvalidScaleOrWidth() {
        assertNull(MapScaleFormatter.computeScaleBar(0.0, DistanceUnit.KILOMETERS, 200f))
        assertNull(MapScaleFormatter.computeScaleBar(-5.0, DistanceUnit.MILES, 200f))
        assertNull(MapScaleFormatter.computeScaleBar(Double.NaN, DistanceUnit.KILOMETERS, 200f))
        assertNull(MapScaleFormatter.computeScaleBar(10.0, DistanceUnit.KILOMETERS, 0f))
    }

    @Test
    fun distanceUnitAndFormattingHelpers_matchMetricAndImperialConfigs() {
        assertEquals(DistanceUnit.MILES, DistanceUnit.defaultForLocale(Locale.US))
        assertEquals(DistanceUnit.KILOMETERS, DistanceUnit.defaultForLocale(Locale.GERMANY))
        assertEquals(DistanceUnit.MILES, DistanceUnit.fromPref("mi", DistanceUnit.KILOMETERS))
        assertEquals(DistanceUnit.KILOMETERS, DistanceUnit.fromPref("km", DistanceUnit.MILES))
        assertEquals(DistanceUnit.MILES, DistanceUnit.fromPref(null, DistanceUnit.MILES))

        assertEquals("36.0 km/h", MapScaleFormatter.formatSpeed(10f, DistanceUnit.KILOMETERS))
        assertEquals("22.4 mph", MapScaleFormatter.formatSpeed(10f, DistanceUnit.MILES))

        assertEquals("16 km", MapScaleFormatter.formatDistanceShort(16.09344, DistanceUnit.KILOMETERS))
        assertEquals("10 mi", MapScaleFormatter.formatDistanceShort(16.09344, DistanceUnit.MILES))
    }

    @Test
    fun groundMetersPerPointAt_matchesGeodesicScaleOnSampleGeoPdfs() {
        val sampleDir = File("../maps").takeIf { it.exists() } ?: File("maps")
        if (!sampleDir.exists()) return
        val samplePdf = sampleDir.listFiles { f -> f.extension.equals("pdf", ignoreCase = true) }
            ?.firstOrNull() ?: return

        val info = GeoPdfParser.parse(samplePdf)
        requireNotNull(info)
        val vp = info.primaryViewport

        val geodesicPerPt = vp.groundMetersPerPointAt()
        // For Equidistant Conic USFS maps (~1:126,720), geodesic meters/pt should match metersPerPoint within 2%
        val relDiff = kotlin.math.abs(geodesicPerPt - vp.metersPerPoint) / vp.metersPerPoint
        assertTrue(
            "Expected geodesic ($geodesicPerPt) ~ projected (${vp.metersPerPoint}), diff=$relDiff",
            relDiff < 0.02
        )
    }
}
