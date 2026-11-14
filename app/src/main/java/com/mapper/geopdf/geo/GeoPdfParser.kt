package com.mapper.geopdf.geo

import android.graphics.RectF
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Metadata and georeferencing calibration extracted from a GeoPDF file.
 */
data class GeoPdfDocumentInfo(
    val file: File,
    val title: String,
    val pageWidthPt: Float,
    val pageHeightPt: Float,
    val primaryViewport: GeoViewport,
    val allViewports: List<GeoViewport>
)

/**
 * Lightweight, streaming GeoPDF metadata parser that extracts ISO 32000 (/VP -> /Measure -> /GEO)
 * and TerraGo (/LGIDict) georeferencing dictionaries from large PDF files (50MB - 200MB+)
 * without loading the full PDF into memory.
 */
object GeoPdfParser {

    private const val CHUNK_SIZE = 1024 * 1024 // 1 MB scan window
    private const val OVERLAP_SIZE = 64 * 1024 // 64 KB overlap between chunks

    fun parse(file: File): GeoPdfDocumentInfo? {
        if (!file.exists() || !file.canRead() || file.length() < 128) return null

        RandomAccessFile(file, "r").use { raf ->
            val fileLen = raf.length()

            // Most GeoPDFs produced by Esri ArcGIS Pro / pypdf / GDAL place the Catalog, Info,
            // and Page dictionary either in the first 2 MB or the last 2 MB of the file.
            val headSize = min(fileLen, 2L * 1024 * 1024).toInt()
            val headBytes = ByteArray(headSize)
            raf.seek(0)
            raf.readFully(headBytes)
            val headText = String(headBytes, Charsets.ISO_8859_1)

            val parsedFromHead = parseFromTextChunk(file, headText)
            if (parsedFromHead != null) {
                return parsedFromHead
            }

            // If not found in the first 2 MB, check the last 2 MB
            if (fileLen > 2L * 1024 * 1024) {
                val tailSize = min(fileLen, 2L * 1024 * 1024).toInt()
                val tailBytes = ByteArray(tailSize)
                raf.seek(fileLen - tailSize)
                raf.readFully(tailBytes)
                val tailText = String(tailBytes, Charsets.ISO_8859_1)
                val parsedFromTail = parseFromTextChunk(file, tailText)
                if (parsedFromTail != null) {
                    return parsedFromTail
                }
            }

            // Full streaming scan if the Page object is in the middle of the file
            var offset = max(0L, headSize - OVERLAP_SIZE.toLong())
            val buf = ByteArray(CHUNK_SIZE)
            while (offset < fileLen) {
                val toRead = min(CHUNK_SIZE.toLong(), fileLen - offset).toInt()
                raf.seek(offset)
                raf.readFully(buf, 0, toRead)
                val chunkText = String(buf, 0, toRead, Charsets.ISO_8859_1)
                if (chunkText.contains("/VP") || chunkText.contains("/LGIDict") || chunkText.contains("/GPTS")) {
                    val parsed = parseFromTextChunk(file, chunkText)
                    if (parsed != null) return parsed
                }
                if (toRead < CHUNK_SIZE) break
                offset += (CHUNK_SIZE - OVERLAP_SIZE)
            }
        }
        return null
    }

    internal fun parseFromTextChunk(file: File, text: String): GeoPdfDocumentInfo? {
        if (!text.contains("/GPTS") && !text.contains("/LGIDict")) return null

        // Extract Title if present
        val titleMatch = Regex("""/Title\s*\(([^)]*)\)""").find(text)
        val rawTitle = titleMatch?.groupValues?.get(1)
        val decodedTitle = rawTitle?.let { WktProjection.decodePdfEscapes(it).trim() }
            ?.takeIf { it.isNotEmpty() }
            ?: file.nameWithoutExtension

        // Extract MediaBox [ x0 y0 x1 y1 ]
        val mediaBoxMatch = Regex("""/MediaBox\s*\[\s*([-+\d.eE]+)\s+([-+\d.eE]+)\s+([-+\d.eE]+)\s+([-+\d.eE]+)\s*]""").find(text)
        val mediaX0 = mediaBoxMatch?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
        val mediaY0 = mediaBoxMatch?.groupValues?.get(2)?.toFloatOrNull() ?: 0f
        val mediaX1 = mediaBoxMatch?.groupValues?.get(3)?.toFloatOrNull() ?: 3000f
        val mediaY1 = mediaBoxMatch?.groupValues?.get(4)?.toFloatOrNull() ?: 3000f

        val pageWidthPt = abs(mediaX1 - mediaX0)
        val pageHeightPt = abs(mediaY1 - mediaY0)
        val mediaMinX = min(mediaX0, mediaX1)
        val mediaMinY = min(mediaY0, mediaY1)

        val viewports = mutableListOf<GeoViewport>()
        val seenSignatures = mutableSetOf<String>()

        // Split by /BBox occurrences inside the chunk to parse each viewport independently
        val bboxMatches = Regex("""/BBox\s*\[\s*([-+\d.eE]+)\s+([-+\d.eE]+)\s+([-+\d.eE]+)\s+([-+\d.eE]+)\s*]""")
            .findAll(text)
            .toList()

        for ((idx, bboxMatch) in bboxMatches.withIndex()) {
            val blockStart = bboxMatch.range.first
            val blockEnd = if (idx + 1 < bboxMatches.size) {
                min(bboxMatches[idx + 1].range.first, blockStart + 6000)
            } else {
                min(text.length, blockStart + 6000)
            }
            val block = text.substring(blockStart, blockEnd)

            val bx1 = bboxMatch.groupValues[1].toFloatOrNull() ?: continue
            val by1 = bboxMatch.groupValues[2].toFloatOrNull() ?: continue
            val bx2 = bboxMatch.groupValues[3].toFloatOrNull() ?: continue
            val by2 = bboxMatch.groupValues[4].toFloatOrNull() ?: continue

            val gptsMatch = Regex("""/GPTS\s*\[\s*([^\]]+)]""").find(block) ?: continue
            val lptsMatch = Regex("""/LPTS\s*\[\s*([^\]]+)]""").find(block)
            val wktMatch = Regex("""/WKT\s*\(([^)]*)\)""").find(block)

            val gptsNums = parseDoubleList(gptsMatch.groupValues[1])
            val lptsNums = if (lptsMatch != null) {
                parseDoubleList(lptsMatch.groupValues[1])
            } else {
                listOf(0.0, 1.0, 0.0, 0.0, 1.0, 0.0, 1.0, 1.0)
            }

            if (gptsNums.size >= 8 && lptsNums.size >= 8) {
                val minPdfX = min(bx1, bx2)
                val maxPdfX = max(bx1, bx2)
                val minPdfY = min(by1, by2)
                val maxPdfY = max(by1, by2)

                val sig = "%.1f,%.1f,%.1f,%.1f,%.4f".format(minPdfX, minPdfY, maxPdfX, maxPdfY, gptsNums[0])
                if (seenSignatures.add(sig)) {
                    val pageLeft = minPdfX - mediaMinX
                    val pageRight = maxPdfX - mediaMinX
                    val pageTop = pageHeightPt - (maxPdfY - mediaMinY)
                    val pageBottom = pageHeightPt - (minPdfY - mediaMinY)

                    val bboxPage = GeoBBox(pageLeft, pageTop, pageRight, pageBottom)
                    val count = min(gptsNums.size / 2, lptsNums.size / 2)
                    val tiePoints = ArrayList<GeoViewport.TiePoint>(count)

                    for (i in 0 until count) {
                        val lat = gptsNums[i * 2]
                        val lon = gptsNums[i * 2 + 1]
                        val u = lptsNums[i * 2]
                        val v = lptsNums[i * 2 + 1]

                        val pdfX = minPdfX + u * (maxPdfX - minPdfX)
                        val pdfY = minPdfY + v * (maxPdfY - minPdfY)

                        val pageX = pdfX - mediaMinX
                        val pageY = pageHeightPt - (pdfY - mediaMinY)
                        tiePoints.add(GeoViewport.TiePoint(lat, lon, pageX, pageY))
                    }

                    val projection = if (wktMatch != null) {
                        WktProjection.parseWkt(wktMatch.groupValues[1])
                    } else {
                        WktProjection()
                    }
                    viewports.add(
                        GeoViewport(
                            name = "Viewport ${viewports.size + 1} (${projection.projectionName})",
                            pageWidthPt = pageWidthPt,
                            pageHeightPt = pageHeightPt,
                            bboxPage = bboxPage,
                            tiePoints = tiePoints,
                            projection = projection
                        )
                    )
                }
            }
        }

        if (viewports.isEmpty()) return null

        // Select the largest-area viewport as the primary map viewport (ignoring small inset maps)
        val sorted = viewports.sortedByDescending { it.areaSqPt }
        val primary = sorted.first()

        return GeoPdfDocumentInfo(
            file = file,
            title = decodedTitle,
            pageWidthPt = pageWidthPt,
            pageHeightPt = pageHeightPt,
            primaryViewport = primary,
            allViewports = sorted
        )
    }

    private fun parseDoubleList(raw: String): List<Double> {
        return Regex("""[-+]?\d*\.?\d+(?:[eE][-+]?\d+)?""")
            .findAll(raw)
            .mapNotNull { it.value.toDoubleOrNull() }
            .toList()
    }
}
