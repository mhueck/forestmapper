package com.mapper.geopdf.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class GeoPdfParserTest {

    @Test
    fun parseSampleMaps_extractsPrimaryEquidistantConicViewportAndProjectsAccurately() {
        val sampleDir = File("../maps").takeIf { it.exists() } ?: File("maps")
        if (!sampleDir.exists()) return

        val pdfFiles = sampleDir.listFiles { f -> f.extension.equals("pdf", ignoreCase = true) }.orEmpty()
        assertTrue("Expected sample PDFs in maps/", pdfFiles.isNotEmpty())

        for (file in pdfFiles) {
            val info = GeoPdfParser.parse(file)
            assertNotNull("Failed to parse GeoPDF metadata from ${file.name}", info)
            requireNotNull(info)

            val primary = info.primaryViewport
            assertEquals("Equidistant_Conic", primary.projection.projectionName)
            assertTrue("Expected primary viewport width > 2000pt", primary.bboxPage.width() > 2000f)
            assertTrue("Expected primary viewport height > 2000pt", primary.bboxPage.height() > 2000f)

            // Verify round-trip accuracy at all 4 calibration corners
            for (tp in primary.tiePoints) {
                val mappedPage = primary.latLonToPage(tp.latitude, tp.longitude)
                assertEquals(tp.pageX.toFloat(), mappedPage.x, 1.5f)
                assertEquals(tp.pageY.toFloat(), mappedPage.y, 1.5f)

                val (invLat, invLon) = primary.pageToLatLon(mappedPage.x, mappedPage.y)
                assertEquals(tp.latitude, invLat, 1e-3)
                assertEquals(tp.longitude, invLon, 1e-3)
            }

            // Verify center of map is inside viewport bounds
            val centerPt = primary.latLonToPage(primary.centerLat, primary.centerLon)
            assertTrue(abs(centerPt.x - primary.bboxPage.centerX()) < 50f)
            assertTrue(abs(centerPt.y - primary.bboxPage.centerY()) < 50f)
        }
    }
}
