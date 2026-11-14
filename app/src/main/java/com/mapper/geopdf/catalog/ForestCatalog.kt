package com.mapper.geopdf.catalog

import java.io.InputStream
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(
    val lat: Double,
    val lon: Double
)

data class GeoBounds(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double
) {
    fun contains(lat: Double, lon: Double): Boolean {
        return lat in minLat..maxLat && lon in minLon..maxLon
    }

    fun areaDegSq(): Double {
        return max(0.0, maxLat - minLat) * max(0.0, maxLon - minLon)
    }

    /**
     * Approximate great-circle distance in kilometers from (lat, lon) to the nearest
     * point on this bounding box (0.0 if inside).
     */
    fun distanceKmTo(lat: Double, lon: Double): Double {
        if (contains(lat, lon)) return 0.0
        val clampedLat = lat.coerceIn(minLat, maxLat)
        val clampedLon = lon.coerceIn(minLon, maxLon)
        return haversineKm(lat, lon, clampedLat, clampedLon)
    }
}

data class CatalogMapEntry(
    val id: String,
    val objectId: Int,
    val mapName: String,
    val commonName: String,
    val adminUnit: String?,
    val series: String,
    val region: String?,
    val state: String?,
    val scale: Int?,
    val bbox: GeoBounds,
    val centroid: LatLon,
    val polygon: List<LatLon>,
    val downloadUrl: String,
    val resolvedUrl: String?,
    val fileName: String,
    val fileSizeBytes: Long?,
    val etag: String?,
    val lastModified: String?,
    val urlStatus: String
) {
    fun preferredDownloadUrl(): String = resolvedUrl?.takeIf { it.isNotBlank() } ?: downloadUrl

    fun fileSizeMbText(): String {
        val bytes = fileSizeBytes ?: return "?"
        return "${(bytes / (1024.0 * 1024.0)).toInt().coerceAtLeast(1)} MB"
    }

    /**
     * Checks whether (lat, lon) falls inside this map's footprint polygon, using [bbox]
     * as a fast pre-filter followed by ray-casting point-in-polygon.
     */
    fun containsPoint(lat: Double, lon: Double): Boolean {
        if (!bbox.contains(lat, lon)) return false
        if (polygon.size < 3) return true
        return isPointInPolygon(lat, lon, polygon)
    }

    fun distanceToCentroidKm(lat: Double, lon: Double): Double {
        return haversineKm(lat, lon, centroid.lat, centroid.lon)
    }
}

data class ForestCatalog(
    val schemaVersion: Int,
    val generatedAt: String,
    val maps: List<CatalogMapEntry>
) {
    /**
     * Returns all catalog maps whose footprint polygon covers ([lat], [lon]), ranked by:
     *  1. Finest cartographic scale (smallest scale denominator, e.g. 63360 before 158400)
     *  2. Smallest geographic footprint area (most specific sheet)
     *  3. Closest centroid distance
     */
    fun findMatchingMaps(lat: Double, lon: Double): List<CatalogMapEntry> {
        return maps
            .asSequence()
            .filter { it.containsPoint(lat, lon) }
            .sortedWith(
                compareBy<CatalogMapEntry>(
                    { it.scale ?: Int.MAX_VALUE },
                    { it.bbox.areaDegSq() },
                    { it.distanceToCentroidKm(lat, lon) }
                )
            )
            .toList()
    }

    /**
     * Returns maps whose bounding box is within [radiusKm] of ([lat], [lon]) (excluding or
     * including direct containment), ordered by distance then scale.
     */
    fun findNearbyMaps(lat: Double, lon: Double, radiusKm: Double = 60.0): List<CatalogMapEntry> {
        return maps
            .asSequence()
            .map { entry -> entry to entry.bbox.distanceKmTo(lat, lon) }
            .filter { (_, distKm) -> distKm <= radiusKm }
            .sortedWith(
                compareBy<Pair<CatalogMapEntry, Double>>(
                    { it.second },
                    { it.first.scale ?: Int.MAX_VALUE },
                    { it.first.distanceToCentroidKm(lat, lon) }
                )
            )
            .map { it.first }
            .toList()
    }
}

object ForestCatalogParser {

    fun parse(inputStream: InputStream): ForestCatalog {
        val text = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return parse(text)
    }

    fun parse(jsonText: String): ForestCatalog {
        val root = JsonReader(jsonText).parseRootObject()
        val schemaVersion = root.optInt("schemaVersion") ?: 1
        val generatedAt = root.optString("generatedAt", "")
        val mapsArray = root.optList("maps") ?: return ForestCatalog(schemaVersion, generatedAt, emptyList())

        val entries = ArrayList<CatalogMapEntry>(mapsArray.size)
        for (rawItem in mapsArray) {
            @Suppress("UNCHECKED_CAST")
            val obj = rawItem as? Map<String, Any?> ?: continue
            val bboxObj = obj.optMap("bbox") ?: continue
            val centroidObj = obj.optMap("centroid")
            val polyArray = obj.optList("polygon")

            val bbox = GeoBounds(
                minLat = bboxObj.requireDouble("minLat"),
                maxLat = bboxObj.requireDouble("maxLat"),
                minLon = bboxObj.requireDouble("minLon"),
                maxLon = bboxObj.requireDouble("maxLon")
            )

            val centroid = if (centroidObj != null) {
                LatLon(centroidObj.requireDouble("lat"), centroidObj.requireDouble("lon"))
            } else {
                LatLon((bbox.minLat + bbox.maxLat) * 0.5, (bbox.minLon + bbox.maxLon) * 0.5)
            }

            val polygon = ArrayList<LatLon>(polyArray?.size ?: 0)
            if (polyArray != null) {
                for (rawPt in polyArray) {
                    @Suppress("UNCHECKED_CAST")
                    val pt = rawPt as? Map<String, Any?> ?: continue
                    val lat = pt.optDouble("lat") ?: continue
                    val lon = pt.optDouble("lon") ?: continue
                    polygon.add(LatLon(lat, lon))
                }
            }

            val mapName = obj.requireString("mapName")
            entries.add(
                CatalogMapEntry(
                    id = obj.requireString("id"),
                    objectId = obj.optInt("objectId") ?: 0,
                    mapName = mapName,
                    commonName = obj.optString("commonName", mapName).ifBlank { mapName },
                    adminUnit = obj.optNullableString("adminUnit"),
                    series = obj.optString("series", "Forest"),
                    region = obj.optNullableString("region"),
                    state = obj.optNullableString("state"),
                    scale = obj.optInt("scale"),
                    bbox = bbox,
                    centroid = centroid,
                    polygon = polygon,
                    downloadUrl = obj.requireString("downloadUrl"),
                    resolvedUrl = obj.optNullableString("resolvedUrl"),
                    fileName = obj.optString("fileName", "$mapName.pdf"),
                    fileSizeBytes = obj.optLong("fileSizeBytes"),
                    etag = obj.optNullableString("etag"),
                    lastModified = obj.optNullableString("lastModified"),
                    urlStatus = obj.optString("urlStatus", "unchecked")
                )
            )
        }

        return ForestCatalog(
            schemaVersion = schemaVersion,
            generatedAt = generatedAt,
            maps = entries
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.optMap(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.optList(key: String): List<Any?>? = this[key] as? List<Any?>

    private fun Map<String, Any?>.optString(key: String, default: String): String {
        val value = this[key] as? String ?: return default
        return value
    }

    private fun Map<String, Any?>.optNullableString(key: String): String? {
        val value = this[key] as? String ?: return null
        return value.takeIf { it.isNotEmpty() }
    }

    private fun Map<String, Any?>.requireString(key: String): String {
        return this[key] as? String ?: throw IllegalArgumentException("Missing string '$key'")
    }

    private fun Map<String, Any?>.optInt(key: String): Int? = (this[key] as? Number)?.toInt()

    private fun Map<String, Any?>.optLong(key: String): Long? = (this[key] as? Number)?.toLong()

    private fun Map<String, Any?>.optDouble(key: String): Double? = (this[key] as? Number)?.toDouble()

    private fun Map<String, Any?>.requireDouble(key: String): Double {
        return optDouble(key) ?: throw IllegalArgumentException("Missing number '$key'")
    }
}

/**
 * Lightweight dependency-free JSON parser so catalog parsing behaves identically in both
 * Android runtime and plain JVM unit tests where `org.json` is stubbed.
 */
private class JsonReader(private val input: String) {
    private var pos = if (input.startsWith("\uFEFF")) 1 else 0

    fun parseRootObject(): Map<String, Any?> {
        val value = parseValue()
        skipWhitespace()
        if (pos < input.length) {
            throw IllegalArgumentException("Unexpected trailing JSON content at index $pos")
        }
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any?>
            ?: throw IllegalArgumentException("Expected top-level JSON object")
    }

    private fun parseValue(): Any? {
        skipWhitespace()
        if (pos >= input.length) {
            throw IllegalArgumentException("Unexpected end of JSON at index $pos")
        }
        return when (input[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> parseLiteral("true", true)
            'f' -> parseLiteral("false", false)
            'n' -> parseLiteral("null", null)
            '-', in '0'..'9' -> parseNumber()
            else -> throw IllegalArgumentException("Unexpected char '${input[pos]}' at index $pos")
        }
    }

    private fun parseObject(): Map<String, Any?> {
        expect('{')
        val map = LinkedHashMap<String, Any?>()
        skipWhitespace()
        if (peek() == '}') {
            pos++
            return map
        }
        while (true) {
            skipWhitespace()
            val key = parseString()
            expect(':')
            val value = parseValue()
            map[key] = value
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return map
                }
                else -> throw IllegalArgumentException("Expected ',' or '}' at index $pos")
            }
        }
    }

    private fun parseArray(): List<Any?> {
        expect('[')
        val list = ArrayList<Any?>()
        skipWhitespace()
        if (peek() == ']') {
            pos++
            return list
        }
        while (true) {
            list.add(parseValue())
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return list
                }
                else -> throw IllegalArgumentException("Expected ',' or ']' at index $pos")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (pos < input.length) {
            val ch = input[pos++]
            when (ch) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (pos >= input.length) throw IllegalArgumentException("Unterminated escape at $pos")
                    val esc = input[pos++]
                    when (esc) {
                        '"', '\\', '/' -> sb.append(esc)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > input.length) {
                                throw IllegalArgumentException("Invalid unicode escape at $pos")
                            }
                            sb.append(input.substring(pos, pos + 4).toInt(16).toChar())
                            pos += 4
                        }
                        else -> throw IllegalArgumentException("Invalid escape '\\$esc' at ${pos - 1}")
                    }
                }
                else -> sb.append(ch)
            }
        }
        throw IllegalArgumentException("Unterminated JSON string")
    }

    private fun parseNumber(): Number {
        val start = pos
        if (peek() == '-') pos++
        var isFloat = false
        while (pos < input.length) {
            val ch = input[pos]
            if (ch in '0'..'9') {
                pos++
            } else if (ch == '.' || ch == 'e' || ch == 'E') {
                isFloat = true
                pos++
                if ((ch == 'e' || ch == 'E') && pos < input.length && (input[pos] == '+' || input[pos] == '-')) {
                    pos++
                }
            } else {
                break
            }
        }
        val raw = input.substring(start, pos)
        return if (isFloat) raw.toDouble() else raw.toLongOrNull() ?: raw.toDouble()
    }

    private fun parseLiteral(expected: String, result: Any?): Any? {
        if (!input.regionMatches(pos, expected, 0, expected.length)) {
            throw IllegalArgumentException("Expected '$expected' at index $pos")
        }
        pos += expected.length
        return result
    }

    private fun skipWhitespace() {
        while (pos < input.length) {
            when (input[pos]) {
                ' ', '\t', '\r', '\n' -> pos++
                else -> return
            }
        }
    }

    private fun expect(expected: Char) {
        skipWhitespace()
        if (pos >= input.length || input[pos] != expected) {
            throw IllegalArgumentException("Expected '$expected' at index $pos")
        }
        pos++
    }

    private fun peek(): Char? = if (pos < input.length) input[pos] else null
}

internal fun isPointInPolygon(lat: Double, lon: Double, polygon: List<LatLon>): Boolean {
    if (polygon.size < 3) return false
    var inside = false
    var j = polygon.lastIndex
    for (i in polygon.indices) {
        val pi = polygon[i]
        val pj = polygon[j]
        if (isPointOnSegment(lat, lon, pi, pj)) {
            return true
        }
        val intersects = ((pi.lat > lat) != (pj.lat > lat)) &&
            (lon < (pj.lon - pi.lon) * (lat - pi.lat) / (pj.lat - pi.lat) + pi.lon)
        if (intersects) {
            inside = !inside
        }
        j = i
    }
    return inside
}

private fun isPointOnSegment(lat: Double, lon: Double, a: LatLon, b: LatLon): Boolean {
    val eps = 1e-9
    val cross = (lat - a.lat) * (b.lon - a.lon) - (lon - a.lon) * (b.lat - a.lat)
    if (kotlin.math.abs(cross) > eps) return false
    val minLat = min(a.lat, b.lat) - eps
    val maxLat = max(a.lat, b.lat) + eps
    val minLon = min(a.lon, b.lon) - eps
    val maxLon = max(a.lon, b.lon) + eps
    return lat in minLat..maxLat && lon in minLon..maxLon
}

internal fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val earthRadiusKm = 6371.0
    val dLat = (lat2 - lat1) * PI / 180.0
    val dLon = (lon2 - lon1) * PI / 180.0
    val rLat1 = lat1 * PI / 180.0
    val rLat2 = lat2 * PI / 180.0
    val a = sin(dLat / 2.0).pow(2) + cos(rLat1) * cos(rLat2) * sin(dLon / 2.0).pow(2)
    val c = 2.0 * asin(min(1.0, sqrt(a)))
    return earthRadiusKm * c
}
