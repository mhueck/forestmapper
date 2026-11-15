package com.mapper.geopdf.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class DestinationGuidanceTest {

    private fun createMercatorViewport(): GeoViewport {
        val projection = WktProjection(
            projectionName = "Mercator",
            centralMeridianDeg = -121.0
        )
        return GeoViewport(
            name = "TestMercator",
            pageWidthPt = 1000f,
            pageHeightPt = 1000f,
            bboxPage = GeoBBox(0f, 0f, 1000f, 1000f),
            tiePoints = listOf(
                GeoViewport.TiePoint(48.0, -121.5, 0.0, 0.0),
                GeoViewport.TiePoint(48.0, -120.5, 1000.0, 0.0),
                GeoViewport.TiePoint(47.0, -121.5, 0.0, 1000.0),
                GeoViewport.TiePoint(47.0, -120.5, 1000.0, 1000.0)
            ),
            projection = projection
        )
    }

    @Test
    fun formatDistanceMeters_formatsMetricAndImperialCorrectly() {
        assertEquals("0 m", MapScaleFormatter.formatDistanceMeters(0.0, DistanceUnit.KILOMETERS))
        assertEquals("450 m", MapScaleFormatter.formatDistanceMeters(450.4, DistanceUnit.KILOMETERS))
        assertEquals("1.0 km", MapScaleFormatter.formatDistanceMeters(1000.0, DistanceUnit.KILOMETERS))
        assertEquals("12.3 km", MapScaleFormatter.formatDistanceMeters(12340.0, DistanceUnit.KILOMETERS))
        assertEquals("125 km", MapScaleFormatter.formatDistanceMeters(125000.0, DistanceUnit.KILOMETERS))

        assertEquals("500 ft", MapScaleFormatter.formatDistanceMeters(152.4, DistanceUnit.MILES))
        assertEquals("1.0 mi", MapScaleFormatter.formatDistanceMeters(1609.344, DistanceUnit.MILES))
        assertEquals("100 mi", MapScaleFormatter.formatDistanceMeters(160934.4, DistanceUnit.MILES))

        assertEquals("", MapScaleFormatter.formatDistanceMeters(Double.NaN, DistanceUnit.KILOMETERS))
        assertEquals("", MapScaleFormatter.formatDistanceMeters(Double.POSITIVE_INFINITY, DistanceUnit.MILES))
    }

    @Test
    fun initialBearingDegrees_matchesCardinalAzimuths() {
        assertEquals(0.0, MapScaleFormatter.initialBearingDegrees(47.0, -121.0, 48.0, -121.0), 0.1)
        assertEquals(90.0, MapScaleFormatter.initialBearingDegrees(0.0, 0.0, 0.0, 1.0), 0.1)
        assertEquals(180.0, MapScaleFormatter.initialBearingDegrees(48.0, -121.0, 47.0, -121.0), 0.1)
        assertEquals(270.0, MapScaleFormatter.initialBearingDegrees(0.0, 0.0, 0.0, -1.0), 0.1)
        assertEquals(0.0, MapScaleFormatter.initialBearingDegrees(47.5, -121.0, 47.5, -121.0), 0.1)
    }

    @Test
    fun computePlacement_returnsVisibleFlagWhenOnMapAndUnobstructed() {
        val viewport = createMercatorViewport()
        val dest = MapDestination(47.5, -121.0)

        val placement = DestinationGuidance.computePlacement(
            destination = dest,
            viewport = viewport,
            viewportWidth = 1000,
            viewportHeight = 1000,
            offsetX = 0f,
            offsetY = 0f,
            scale = 1f
        )

        assertTrue(placement is DestinationPlacement.VisibleFlag)
        val flag = placement as DestinationPlacement.VisibleFlag
        assertEquals(500f, flag.screenX, 2f)
        assertEquals(500f, flag.screenY, 30f)
    }

    @Test
    fun computePlacement_returnsOffscreenBorderForOutOfBoundsDirections() {
        val viewport = createMercatorViewport()

        val nePlacement = DestinationGuidance.computePlacement(
            destination = MapDestination(49.0, -119.5),
            viewport = viewport,
            viewportWidth = 1000,
            viewportHeight = 1000,
            offsetX = 0f,
            offsetY = 0f,
            scale = 1f
        )
        assertTrue(nePlacement is DestinationPlacement.OffscreenBorder)
        val ne = nePlacement as DestinationPlacement.OffscreenBorder
        assertTrue("Expected NE angle in (0, 90), got ${ne.directionAngleDeg}", ne.directionAngleDeg in 1f..89f)
        assertTrue("Expected badge near top/right border: (${ne.badgeCenterX}, ${ne.badgeCenterY})", ne.badgeCenterX > 500f && ne.badgeCenterY < 500f)

        val swPlacement = DestinationGuidance.computePlacement(
            destination = MapDestination(46.0, -122.5),
            viewport = viewport,
            viewportWidth = 1000,
            viewportHeight = 1000,
            offsetX = 0f,
            offsetY = 0f,
            scale = 1f
        )
        assertTrue(swPlacement is DestinationPlacement.OffscreenBorder)
        val sw = swPlacement as DestinationPlacement.OffscreenBorder
        assertTrue("Expected SW angle in (-180, -90), got ${sw.directionAngleDeg}", sw.directionAngleDeg in -179f..-91f)
        assertTrue("Expected badge near bottom/left border: (${sw.badgeCenterX}, ${sw.badgeCenterY})", sw.badgeCenterX < 500f && sw.badgeCenterY > 500f)
    }

    @Test
    fun computePlacement_turnsVisiblePointCoveredByTopInsetIntoNorthBorderArrow() {
        val viewport = createMercatorViewport()
        // Around pageY ~ 50 (inside page 0..1000, but hidden behind a 140px top control bar)
        val destNearTop = MapDestination(47.95, -121.0)

        val placement = DestinationGuidance.computePlacement(
            destination = destNearTop,
            viewport = viewport,
            viewportWidth = 1000,
            viewportHeight = 1000,
            offsetX = 0f,
            offsetY = 0f,
            scale = 1f,
            insets = OverlayInsets(topPx = 140)
        )

        assertTrue(placement is DestinationPlacement.OffscreenBorder)
        val off = placement as DestinationPlacement.OffscreenBorder
        assertTrue("Badge center should sit below top inset 140: y=${off.badgeCenterY}", off.badgeCenterY >= 140f)
        // Angle should point nearly straight up (~0 deg)
        assertEquals(0f, off.directionAngleDeg, 5f)
    }

    @Test
    fun computePlacement_avoidsExcludedRects() {
        val viewport = createMercatorViewport()
        // East of map page: direct hit would land on right border near center Y (500)
        val destEast = MapDestination(47.5, -119.5)
        val fabObstacle = ScreenBounds(left = 860f, top = 420f, right = 990f, bottom = 720f)

        val placement = DestinationGuidance.computePlacement(
            destination = destEast,
            viewport = viewport,
            viewportWidth = 1000,
            viewportHeight = 1000,
            offsetX = 0f,
            offsetY = 0f,
            scale = 1f,
            insets = OverlayInsets(excludedRects = listOf(fabObstacle)),
            pillHalfWidthPx = 64f,
            pillHalfHeightPx = 20f,
            arrowClearancePx = 30f
        )

        assertTrue(placement is DestinationPlacement.OffscreenBorder)
        val off = placement as DestinationPlacement.OffscreenBorder
        assertFalse(
            "Badge (${off.badgeCenterX}, ${off.badgeCenterY}) must clear FAB obstacle $fabObstacle",
            fabObstacle.expanded(40f, 30f).contains(off.badgeCenterX, off.badgeCenterY)
        )
    }

    @Test
    fun computePlacement_usesGreatCircleBearingBeyond2500Km() {
        val viewport = createMercatorViewport()
        // London (~7,700 km NE of -121.0, 47.5) and Tokyo (~7,600 km NW)
        val london = MapDestination(51.5074, -0.1278)
        val tokyo = MapDestination(35.6762, 139.6503)

        val londonPlacement = DestinationGuidance.computePlacement(
            destination = london,
            viewport = viewport,
            viewportWidth = 1000,
            viewportHeight = 1000,
            offsetX = 0f,
            offsetY = 0f,
            scale = 1f
        )
        assertNotNull(londonPlacement)
        assertTrue(londonPlacement is DestinationPlacement.OffscreenBorder)
        val londonOff = londonPlacement as DestinationPlacement.OffscreenBorder
        val londonExpectedBearing = MapScaleFormatter.initialBearingDegrees(47.5, -121.0, london.latitude, london.longitude)
        assertAngleMatches(londonExpectedBearing, londonOff.directionAngleDeg.toDouble(), toleranceDeg = 5.0)

        val tokyoPlacement = DestinationGuidance.computePlacement(
            destination = tokyo,
            viewport = viewport,
            viewportWidth = 1000,
            viewportHeight = 1000,
            offsetX = 0f,
            offsetY = 0f,
            scale = 1f
        )
        assertTrue(tokyoPlacement is DestinationPlacement.OffscreenBorder)
        val tokyoOff = tokyoPlacement as DestinationPlacement.OffscreenBorder
        val tokyoExpectedBearing = MapScaleFormatter.initialBearingDegrees(47.5, -121.0, tokyo.latitude, tokyo.longitude)
        assertAngleMatches(tokyoExpectedBearing, tokyoOff.directionAngleDeg.toDouble(), toleranceDeg = 5.0)
    }

    @Test
    fun computePlacement_rejectsInvalidDestination() {
        val viewport = createMercatorViewport()
        assertNull(
            DestinationGuidance.computePlacement(
                destination = MapDestination(95.0, 0.0),
                viewport = viewport,
                viewportWidth = 1000,
                viewportHeight = 1000,
                offsetX = 0f,
                offsetY = 0f,
                scale = 1f
            )
        )
        assertNull(
            DestinationGuidance.computePlacement(
                destination = MapDestination(Double.NaN, -121.0),
                viewport = viewport,
                viewportWidth = 1000,
                viewportHeight = 1000,
                offsetX = 0f,
                offsetY = 0f,
                scale = 1f
            )
        )
    }

    @Test
    fun mapUiState_shouldKeepScreenOn_tracksDestinationSelection() {
        val noDest = com.mapper.geopdf.state.MapUiState()
        assertFalse(noDest.shouldKeepScreenOn)

        val withDest = noDest.copy(destination = MapDestination(47.5, -121.0))
        assertTrue(withDest.shouldKeepScreenOn)

        val cleared = withDest.copy(destination = null)
        assertFalse(cleared.shouldKeepScreenOn)
    }

    private fun assertAngleMatches(expectedDeg: Double, actualDeg: Double, toleranceDeg: Double) {
        val eRad = Math.toRadians(expectedDeg)
        val aRad = Math.toRadians(actualDeg)
        val dot = (sin(eRad) * sin(aRad) + cos(eRad) * cos(aRad)).coerceIn(-1.0, 1.0)
        val diffDeg = Math.toDegrees(kotlin.math.acos(dot))
        assertTrue("Expected angle near $expectedDeg deg, got $actualDeg deg (diff=$diffDeg)", diffDeg <= toleranceDeg)
    }
}
