package com.mapper.geopdf.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ForestCatalogTest {

    private fun resolveCatalogFile(): File {
        val candidates = listOf(
            File("src/main/assets/forest_catalog.json"),
            File("app/src/main/assets/forest_catalog.json")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Could not locate forest_catalog.json from ${File(".").absolutePath}")
    }

    @Test
    fun parsesBundledForestCatalogAsset() {
        val catalogFile = resolveCatalogFile()
        val catalog = catalogFile.inputStream().use { ForestCatalogParser.parse(it) }

        assertEquals(1, catalog.schemaVersion)
        assertEquals(173, catalog.maps.size)
        assertTrue(catalog.maps.all { it.id.isNotBlank() && it.mapName.isNotBlank() })
        assertTrue(catalog.maps.all { !it.resolvedUrl.isNullOrBlank() && it.polygon.size >= 3 })
    }

    @Test
    fun matchesKnownArizonaForestLocations() {
        val catalog = resolveCatalogFile().inputStream().use { ForestCatalogParser.parse(it) }

        val prescottMatches = catalog.findMatchingMaps(34.5400, -112.4685)
        assertTrue(
            "Expected Prescott National Forest for (34.5400, -112.4685), got ${prescottMatches.map { it.mapName }}",
            prescottMatches.any { it.mapName == "Prescott National Forest" }
        )

        val coronadoMatches = catalog.findMatchingMaps(32.443, -110.788)
        assertTrue(
            "Expected Coronado National Forest for (32.443, -110.788), got ${coronadoMatches.map { it.mapName }}",
            coronadoMatches.any { it.mapName == "Coronado National Forest" }
        )

        val tontoMatches = catalog.findMatchingMaps(33.85, -111.30)
        assertTrue(
            "Expected Tonto National Forest for (33.85, -111.30), got ${tontoMatches.map { it.mapName }}",
            tontoMatches.any { it.mapName == "Tonto National Forest" }
        )

        val oceanMatches = catalog.findMatchingMaps(0.0, 0.0)
        assertTrue(oceanMatches.isEmpty())
    }

    @Test
    fun ranksMatchesByFinestScaleThenSmallestArea() {
        val coarseWide = syntheticEntry(
            id = "coarse-wide",
            scale = 158400,
            minLat = 34.0,
            maxLat = 36.0,
            minLon = -113.0,
            maxLon = -111.0
        )
        val fineWide = syntheticEntry(
            id = "fine-wide",
            scale = 63360,
            minLat = 34.0,
            maxLat = 36.0,
            minLon = -113.0,
            maxLon = -111.0
        )
        val fineTight = syntheticEntry(
            id = "fine-tight",
            scale = 63360,
            minLat = 34.4,
            maxLat = 34.6,
            minLon = -112.6,
            maxLon = -112.4
        )

        val catalog = ForestCatalog(1, "2026-01-01T00:00:00Z", listOf(coarseWide, fineWide, fineTight))
        val matches = catalog.findMatchingMaps(34.5, -112.5)

        assertEquals(listOf("fine-tight", "fine-wide", "coarse-wide"), matches.map { it.id })
    }

    @Test
    fun rayCastingHandlesConcaveAndRotatedPolygons() {
        // Concave U-shaped polygon opening north:
        // left arm (lat 0..10, lon 0..2), bottom bar (lat 0..2, lon 0..10), right arm (lat 0..10, lon 8..10)
        val uPolygon = listOf(
            LatLon(0.0, 0.0),
            LatLon(10.0, 0.0),
            LatLon(10.0, 2.0),
            LatLon(2.0, 2.0),
            LatLon(2.0, 8.0),
            LatLon(10.0, 8.0),
            LatLon(10.0, 10.0),
            LatLon(0.0, 10.0)
        )

        assertTrue(isPointInPolygon(5.0, 1.0, uPolygon))
        assertTrue(isPointInPolygon(1.0, 5.0, uPolygon))
        assertTrue(isPointInPolygon(5.0, 9.0, uPolygon))
        assertFalse(isPointInPolygon(5.0, 5.0, uPolygon)) // Inside the U concavity bay
        assertTrue(isPointInPolygon(0.0, 5.0, uPolygon)) // On bottom boundary edge

        // Rotated diamond centered at (34.0, -112.0)
        val diamond = listOf(
            LatLon(35.0, -112.0),
            LatLon(34.0, -111.0),
            LatLon(33.0, -112.0),
            LatLon(34.0, -113.0)
        )
        assertTrue(isPointInPolygon(34.0, -112.0, diamond))
        assertFalse(isPointInPolygon(34.9, -111.1, diamond)) // Inside bbox corner, outside diamond
    }

    @Test
    fun findNearbyMapsFiltersByRadiusAndSortsByDistanceThenScale() {
        val covering = syntheticEntry("covering", scale = 126720, minLat = 34.4, maxLat = 34.6, minLon = -112.6, maxLon = -112.4)
        val closeCoarse = syntheticEntry("close-coarse", scale = 158400, minLat = 34.7, maxLat = 34.9, minLon = -112.6, maxLon = -112.4)
        val closeFineSameBox = syntheticEntry("close-fine", scale = 63360, minLat = 34.7, maxLat = 34.9, minLon = -112.6, maxLon = -112.4)
        val farAway = syntheticEntry("far", scale = 24000, minLat = 40.0, maxLat = 41.0, minLon = -105.0, maxLon = -104.0)

        val catalog = ForestCatalog(1, "2026-01-01T00:00:00Z", listOf(farAway, closeCoarse, covering, closeFineSameBox))
        val nearby = catalog.findNearbyMaps(34.5, -112.5, radiusKm = 50.0)

        assertEquals(listOf("covering", "close-fine", "close-coarse"), nearby.map { it.id })
    }

    private fun syntheticEntry(
        id: String,
        scale: Int?,
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double
    ): CatalogMapEntry {
        val polygon = listOf(
            LatLon(minLat, minLon),
            LatLon(maxLat, minLon),
            LatLon(maxLat, maxLon),
            LatLon(minLat, maxLon)
        )
        return CatalogMapEntry(
            id = id,
            objectId = 1,
            mapName = id,
            commonName = id,
            adminUnit = null,
            series = "Forest",
            region = "03",
            state = "AZ",
            scale = scale,
            bbox = GeoBounds(minLat, maxLat, minLon, maxLon),
            centroid = LatLon((minLat + maxLat) * 0.5, (minLon + maxLon) * 0.5),
            polygon = polygon,
            downloadUrl = "https://example.com/$id.pdf",
            resolvedUrl = "https://example.com/resolved/$id.pdf",
            fileName = "$id.pdf",
            fileSizeBytes = 10_000_000L,
            etag = null,
            lastModified = null,
            urlStatus = "ok"
        )
    }
}
