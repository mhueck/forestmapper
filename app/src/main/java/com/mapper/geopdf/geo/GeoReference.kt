package com.mapper.geopdf.geo

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class PagePoint(
    val x: Float,
    val y: Float
)

data class GeoBBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun width(): Float = right - left
    fun height(): Float = bottom - top
    fun centerX(): Float = (left + right) * 0.5f
    fun centerY(): Float = (top + bottom) * 0.5f
}

/**
 * Represents a georeferenced viewport on a PDF page (ISO 32000 /VP Measure or TerraGo /LGIDict).
 *
 * Page coordinates (pageX, pageY) are in top-left origin PDF points:
 *   pageX in [0, pageWidthPt]
 *   pageY in [0, pageHeightPt]
 * matching Android's PdfRenderer coordinate system.
 */
data class GeoViewport(
    val name: String,
    val pageWidthPt: Float,
    val pageHeightPt: Float,
    /** Bounding box in top-left origin PDF page points. */
    val bboxPage: GeoBBox,
    /** 4 or more calibration tie points pairing (latitude, longitude) with (pageX, pageY). */
    val tiePoints: List<TiePoint>,
    /** Parsed WKT projection definition, if present. */
    val projection: WktProjection
) {
    data class TiePoint(
        val latitude: Double,
        val longitude: Double,
        val pageX: Double,
        val pageY: Double
    )

    val minLat: Double = tiePoints.minOfOrNull { it.latitude } ?: 0.0
    val maxLat: Double = tiePoints.maxOfOrNull { it.latitude } ?: 0.0
    val minLon: Double = tiePoints.minOfOrNull { it.longitude } ?: 0.0
    val maxLon: Double = tiePoints.maxOfOrNull { it.longitude } ?: 0.0

    val centerLat: Double = (minLat + maxLat) * 0.5
    val centerLon: Double = (minLon + maxLon) * 0.5

    /** Area of this viewport in square PDF points (used to distinguish main map from small inset maps). */
    val areaSqPt: Float = bboxPage.width() * bboxPage.height()

    // Affine transform from projected (easting, northing) -> (pageX, pageY):
    // pageX = a0 + a1 * E + a2 * N
    // pageY = b0 + b1 * E + b2 * N
    private val affineENtoPage: DoubleArray
    // Inverse affine transform from (pageX, pageY) -> (easting, northing):
    // E = invA0 + invA1 * pageX + invA2 * pageY
    // N = invB0 + invB1 * pageX + invB2 * pageY
    private val affinePageToEN: DoubleArray

    /** Approximate scale in meters per PDF point at the center of the viewport. */
    val metersPerPoint: Double

    init {
        val projectedPts = tiePoints.map { tp ->
            val (e, n) = projection.latLonToEastNorth(tp.latitude, tp.longitude)
             ProjectedTiePoint(e, n, tp.pageX, tp.pageY)
        }
        affineENtoPage = solveAffine(
            projectedPts.map { it.easting },
            projectedPts.map { it.northing },
            projectedPts.map { it.pageX },
            projectedPts.map { it.pageY }
        )
        affinePageToEN = invertAffine2D(affineENtoPage)

        // Compute meters per PDF point from the Jacobian of pageToEN
        val dEdX = affinePageToEN[1]
        val dNdX = affinePageToEN[4]
        val dEdY = affinePageToEN[2]
        val dNdY = affinePageToEN[5]
        val scaleX = sqrt(dEdX * dEdX + dNdX * dNdX)
        val scaleY = sqrt(dEdY * dEdY + dNdY * dNdY)
        metersPerPoint = ((scaleX + scaleY) * 0.5).coerceAtLeast(0.01)
    }

    private data class ProjectedTiePoint(
        val easting: Double,
        val northing: Double,
        val pageX: Double,
        val pageY: Double
    )

    /**
     * Converts geographic (latitude, longitude) in WGS84/NAD83 degrees to PDF page coordinates (pageX, pageY).
     */
    fun latLonToPage(latitude: Double, longitude: Double): PagePoint {
        val (e, n) = projection.latLonToEastNorth(latitude, longitude)
        val x = affineENtoPage[0] + affineENtoPage[1] * e + affineENtoPage[2] * n
        val y = affineENtoPage[3] + affineENtoPage[4] * e + affineENtoPage[5] * n
        return PagePoint(x.toFloat(), y.toFloat())
    }

    /**
     * Converts PDF page coordinates (pageX, pageY) back to geographic (latitude, longitude) in degrees.
     */
    fun pageToLatLon(pageX: Float, pageY: Float): Pair<Double, Double> {
        val px = pageX.toDouble()
        val py = pageY.toDouble()
        val e = affinePageToEN[0] + affinePageToEN[1] * px + affinePageToEN[2] * py
        val n = affinePageToEN[3] + affinePageToEN[4] * px + affinePageToEN[5] * py
        return projection.eastNorthToLatLon(e, n)
    }

    /**
     * Checks whether the given (latitude, longitude) falls inside this viewport's bounding box.
     */
    fun containsLatLon(latitude: Double, longitude: Double, marginPoints: Float = 0f): Boolean {
        if (latitude < minLat - 1.0 || latitude > maxLat + 1.0 ||
            longitude < minLon - 1.0 || longitude > maxLon + 1.0
        ) {
            return false
        }
        val pt = latLonToPage(latitude, longitude)
        return pt.x >= bboxPage.left - marginPoints &&
            pt.x <= bboxPage.right + marginPoints &&
            pt.y >= bboxPage.top - marginPoints &&
            pt.y <= bboxPage.bottom + marginPoints
    }

    /**
     * Computes the screen-space / page-space north angle (in degrees clockwise from page-up / -Y)
     * at the given (latitude, longitude). This corrects the GPS bearing arrow for conic projection
     * convergence so the movement arrow points in the exact map direction.
     */
    fun northAngleOnPageDegrees(latitude: Double, longitude: Double): Float {
        val p1 = latLonToPage(latitude, longitude)
        val p2 = latLonToPage(latitude + 0.001, longitude)
        val dx = (p2.x - p1.x).toDouble()
        val dy = (p2.y - p1.y).toDouble()
        if (abs(dx) < 1e-7 && abs(dy) < 1e-7) return 0f
        // Angle of vector (dx, dy) relative to page-up (0, -1):
        return Math.toDegrees(atan2(dx, -dy)).toFloat()
    }

    companion object {
        /**
         * Solves least-squares 2D affine transform:
         *   U = a0 + a1 * X + a2 * Y
         *   V = b0 + b1 * X + b2 * Y
         * Returns DoubleArray of size 6: [a0, a1, a2, b0, b1, b2].
         */
        private fun solveAffine(
            xs: List<Double>,
            ys: List<Double>,
            us: List<Double>,
            vs: List<Double>
        ): DoubleArray {
            val n = xs.size
            if (n < 3) {
                return doubleArrayOf(0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
            }
            // Normal equations (A^T A) c = A^T b where each row of A is [1, x, y]
            var s1 = 0.0
            var sx = 0.0
            var sy = 0.0
            var sxx = 0.0
            var sxy = 0.0
            var syy = 0.0
            var su = 0.0
            var sxu = 0.0
            var syu = 0.0
            var sv = 0.0
            var sxv = 0.0
            var syv = 0.0

            // Shift coordinates around centroid for numerical stability
            val cx = xs.average()
            val cy = ys.average()

            for (i in 0 until n) {
                val dx = xs[i] - cx
                val dy = ys[i] - cy
                val u = us[i]
                val v = vs[i]
                s1 += 1.0
                sx += dx
                sy += dy
                sxx += dx * dx
                sxy += dx * dy
                syy += dy * dy
                su += u
                sxu += dx * u
                syu += dy * u
                sv += v
                sxv += dx * v
                syv += dy * v
            }

            val m = arrayOf(
                doubleArrayOf(s1, sx, sy),
                doubleArrayOf(sx, sxx, sxy),
                doubleArrayOf(sy, sxy, syy)
            )
            val coeffU = solve3x3(m, doubleArrayOf(su, sxu, syu))
            val coeffV = solve3x3(m, doubleArrayOf(sv, sxv, syv))

            // Unshift centroid:
            // u = c0 + c1*(x - cx) + c2*(y - cy) = (c0 - c1*cx - c2*cy) + c1*x + c2*y
            val a1 = coeffU[1]
            val a2 = coeffU[2]
            val a0 = coeffU[0] - a1 * cx - a2 * cy

            val b1 = coeffV[1]
            val b2 = coeffV[2]
            val b0 = coeffV[0] - b1 * cx - b2 * cy

            return doubleArrayOf(a0, a1, a2, b0, b1, b2)
        }

        private fun solve3x3(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
            val aug = Array(3) { r -> DoubleArray(4) { c -> if (c < 3) a[r][c] else b[r] } }
            for (col in 0..2) {
                var pivot = col
                for (row in col + 1..2) {
                    if (abs(aug[row][col]) > abs(aug[pivot][col])) pivot = row
                }
                val tmp = aug[col]
                aug[col] = aug[pivot]
                aug[pivot] = tmp

                val denom = aug[col][col]
                if (abs(denom) < 1e-18) return doubleArrayOf(0.0, 1.0, 0.0)
                for (c in col..3) aug[col][c] /= denom
                for (row in 0..2) {
                    if (row != col) {
                        val factor = aug[row][col]
                        for (c in col..3) aug[row][c] -= factor * aug[col][c]
                    }
                }
            }
            return doubleArrayOf(aug[0][3], aug[1][3], aug[2][3])
        }

        private fun invertAffine2D(a: DoubleArray): DoubleArray {
            val a0 = a[0]
            val a1 = a[1]
            val a2 = a[2]
            val b0 = a[3]
            val b1 = a[4]
            val b2 = a[5]
            val det = a1 * b2 - a2 * b1
            if (abs(det) < 1e-18) {
                return doubleArrayOf(0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
            }
            val invDet = 1.0 / det
            val ia1 = b2 * invDet
            val ia2 = -a2 * invDet
            val ia0 = -(ia1 * a0 + ia2 * b0)
            val ib1 = -b1 * invDet
            val ib2 = a1 * invDet
            val ib0 = -(ib1 * a0 + ib2 * b0)
            return doubleArrayOf(ia0, ia1, ia2, ib0, ib1, ib2)
        }
    }
}

/**
 * Supports the map projections used by USFS / USGS / Esri GeoPDFs:
 *  - Equidistant_Conic (North_America_Equidistant_Conic)
 *  - Lambert_Conformal_Conic
 *  - Transverse_Mercator (including UTM zones)
 *  - Mercator_Auxiliary_Sphere / Web Mercator
 *  - Albers (Albers Equal Area Conic)
 *  - Geographic (Identity lon/lat)
 */
data class WktProjection(
    val projectionName: String = "Geographic",
    val semiMajor: Double = 6378137.0,
    val inverseFlattening: Double = 298.257222101,
    val centralMeridianDeg: Double = 0.0,
    val latitudeOfOriginDeg: Double = 0.0,
    val standardParallel1Deg: Double = 0.0,
    val standardParallel2Deg: Double = 0.0,
    val falseEasting: Double = 0.0,
    val falseNorthing: Double = 0.0,
    val scaleFactor: Double = 1.0
) {
    private val f = if (inverseFlattening > 0.0) 1.0 / inverseFlattening else 0.0
    private val e2 = 2.0 * f - f * f
    private val e = sqrt(e2.coerceAtLeast(0.0))
    private val lam0 = Math.toRadians(centralMeridianDeg)
    private val phi0 = Math.toRadians(latitudeOfOriginDeg)
    private val phi1 = Math.toRadians(standardParallel1Deg)
    private val phi2 = Math.toRadians(standardParallel2Deg)

    // Precomputed constants for Equidistant Conic
    private val eqConicN: Double
    private val eqConicG: Double
    private val eqConicRho0: Double

    init {
        val m1 = smallM(phi1)
        val m2 = smallM(phi2)
        val M1 = meridionalArc(phi1)
        val M2 = meridionalArc(phi2)
        val M0 = meridionalArc(phi0)
        eqConicN = if (abs(phi1 - phi2) > 1e-7 && abs(M2 - M1) > 1e-3) {
            semiMajor * (m1 - m2) / (M2 - M1)
        } else {
            sin(phi1).coerceIn(-1.0, 1.0).let { if (abs(it) < 1e-6) 0.5 else it }
        }
        eqConicG = m1 / eqConicN + M1 / semiMajor
        eqConicRho0 = semiMajor * (eqConicG - M0 / semiMajor)
    }

    private fun smallM(phi: Double): Double {
        val s = sin(phi)
        return cos(phi) / sqrt((1.0 - e2 * s * s).coerceAtLeast(1e-12))
    }

    private fun meridionalArc(phi: Double): Double {
        val e4 = e2 * e2
        val e6 = e4 * e2
        val c0 = 1.0 - e2 / 4.0 - 3.0 * e4 / 64.0 - 5.0 * e6 / 256.0
        val c2 = 3.0 * e2 / 8.0 + 3.0 * e4 / 32.0 + 45.0 * e6 / 1024.0
        val c4 = 15.0 * e4 / 256.0 + 45.0 * e6 / 1024.0
        val c6 = 35.0 * e6 / 3072.0
        return semiMajor * (c0 * phi - c2 * sin(2.0 * phi) + c4 * sin(4.0 * phi) - c6 * sin(6.0 * phi))
    }

    fun latLonToEastNorth(latDeg: Double, lonDeg: Double): Pair<Double, Double> {
        val phi = Math.toRadians(latDeg.coerceIn(-89.9999, 89.9999))
        val lam = Math.toRadians(lonDeg)
        val dLam = wrapRadians(lam - lam0)

        return when {
            projectionName.contains("Equidistant_Conic", ignoreCase = true) -> {
                val M = meridionalArc(phi)
                val rho = semiMajor * (eqConicG - M / semiMajor)
                val theta = eqConicN * dLam
                val easting = falseEasting + rho * sin(theta)
                val northing = falseNorthing + eqConicRho0 - rho * cos(theta)
                Pair(easting, northing)
            }

            projectionName.contains("Mercator", ignoreCase = true) &&
                !projectionName.contains("Transverse", ignoreCase = true) -> {
                val easting = falseEasting + semiMajor * dLam
                val northing = falseNorthing + semiMajor * ln(tan(PI / 4.0 + phi / 2.0))
                Pair(easting, northing)
            }

            projectionName.contains("Lambert_Conformal_Conic", ignoreCase = true) -> {
                fun tFunc(p: Double): Double {
                    val es = e * sin(p)
                    return tan(PI / 4.0 - p / 2.0) / ((1.0 - es) / (1.0 + es)).pow(e / 2.0)
                }
                val m1 = smallM(phi1)
                val m2 = smallM(phi2)
                val t0 = tFunc(phi0)
                val t1 = tFunc(phi1)
                val t2 = tFunc(phi2)
                val t = tFunc(phi)
                val n = if (abs(phi1 - phi2) > 1e-7) {
                    ln(m1 / m2) / ln(t1 / t2)
                } else {
                    sin(phi1)
                }
                val F = m1 / (n * t1.pow(n))
                val rho0 = semiMajor * F * t0.pow(n)
                val rho = semiMajor * F * t.pow(n)
                val theta = n * dLam
                val easting = falseEasting + rho * sin(theta)
                val northing = falseNorthing + rho0 - rho * cos(theta)
                Pair(easting, northing)
            }

            projectionName.contains("Transverse_Mercator", ignoreCase = true) -> {
                val sinPhi = sin(phi)
                val cosPhi = cos(phi)
                val tanPhi = tan(phi)
                val ep2 = e2 / (1.0 - e2)
                val N = semiMajor / sqrt(1.0 - e2 * sinPhi * sinPhi)
                val T = tanPhi * tanPhi
                val C = ep2 * cosPhi * cosPhi
                val A = cosPhi * dLam
                val M = meridionalArc(phi)
                val M0 = meridionalArc(phi0)
                val easting = falseEasting + scaleFactor * N * (
                    A + (1.0 - T + C) * A.pow(3) / 6.0 +
                        (5.0 - 18.0 * T + T * T + 72.0 * C - 58.0 * ep2) * A.pow(5) / 120.0
                    )
                val northing = falseNorthing + scaleFactor * (
                    M - M0 + N * tanPhi * (
                        A * A / 2.0 +
                            (5.0 - T + 9.0 * C + 4.0 * C * C) * A.pow(4) / 24.0 +
                            (61.0 - 58.0 * T + T * T + 600.0 * C - 330.0 * ep2) * A.pow(6) / 720.0
                        )
                    )
                Pair(easting, northing)
            }

            else -> {
                // Geographic fallback (scaled by cos(phi0))
                val easting = (lonDeg - centralMeridianDeg) * cos(phi0)
                val northing = latDeg
                Pair(easting, northing)
            }
        }
    }

    fun eastNorthToLatLon(easting: Double, northing: Double): Pair<Double, Double> {
        val dx = easting - falseEasting
        val dy = northing - falseNorthing

        return when {
            projectionName.contains("Equidistant_Conic", ignoreCase = true) -> {
                val rho0MinusDy = eqConicRho0 - dy
                val sign = if (eqConicN >= 0.0) 1.0 else -1.0
                val rho = sign * sqrt(dx * dx + rho0MinusDy * rho0MinusDy)
                val theta = atan2(sign * dx, sign * rho0MinusDy)
                val M = semiMajor * (eqConicG - rho / semiMajor)
                val mu = M / (semiMajor * (1.0 - e2 / 4.0 - 3.0 * e2 * e2 / 64.0 - 5.0 * e2 * e2 * e2 / 256.0))
                val e1 = (1.0 - sqrt(1.0 - e2)) / (1.0 + sqrt(1.0 - e2))
                val phi = mu +
                    (3.0 * e1 / 2.0 - 27.0 * e1.pow(3) / 32.0) * sin(2.0 * mu) +
                    (21.0 * e1 * e1 / 16.0 - 55.0 * e1.pow(4) / 32.0) * sin(4.0 * mu) +
                    (151.0 * e1.pow(3) / 96.0) * sin(6.0 * mu)
                val lam = lam0 + theta / eqConicN
                Pair(Math.toDegrees(phi), Math.toDegrees(lam))
            }

            projectionName.contains("Mercator", ignoreCase = true) &&
                !projectionName.contains("Transverse", ignoreCase = true) -> {
                val lam = lam0 + dx / semiMajor
                val phi = 2.0 * atan2(kotlin.math.exp(dy / semiMajor), 1.0) - PI / 2.0
                Pair(Math.toDegrees(phi), Math.toDegrees(lam))
            }

            else -> {
                // Numerical Newton-Raphson inversion using latLonToEastNorth (universal for any projection!)
                var lat = latitudeOfOriginDeg.takeIf { it != 0.0 } ?: 34.0
                var lon = centralMeridianDeg.takeIf { it != 0.0 } ?: -112.0
                val eps = 1e-5
                for (iter in 0 until 8) {
                    val (e0, n0) = latLonToEastNorth(lat, lon)
                    val errE = easting - e0
                    val errN = northing - n0
                    if (abs(errE) < 1e-3 && abs(errN) < 1e-3) break
                    val (eLat, nLat) = latLonToEastNorth(lat + eps, lon)
                    val (eLon, nLon) = latLonToEastNorth(lat, lon + eps)
                    val dEdLat = (eLat - e0) / eps
                    val dNdLat = (nLat - n0) / eps
                    val dEdLon = (eLon - e0) / eps
                    val dNdLon = (nLon - n0) / eps
                    val det = dEdLat * dNdLon - dEdLon * dNdLat
                    if (abs(det) < 1e-18) break
                    lat += (errE * dNdLon - errN * dEdLon) / det
                    lon += (dEdLat * errN - dNdLat * errE) / det
                }
                Pair(lat, lon)
            }
        }
    }

    private fun wrapRadians(rad: Double): Double {
        var r = rad
        while (r > PI) r -= 2.0 * PI
        while (r < -PI) r += 2.0 * PI
        return r
    }

    companion object {
        fun parseWkt(wktRaw: String): WktProjection {
            val wkt = decodePdfEscapes(wktRaw)
            val projMatch = Regex("""PROJECTION\s*\[\s*"([^"]+)"""").find(wkt)
            val projName = projMatch?.groupValues?.get(1) ?: "Geographic"

            val spheroidMatch = Regex("""SPHEROID\s*\[\s*"[^"]+"\s*,\s*([-+\d.eE]+)\s*,\s*([-+\d.eE]+)""").find(wkt)
            val semiMajor = spheroidMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 6378137.0
            val invFlat = spheroidMatch?.groupValues?.get(2)?.toDoubleOrNull() ?: 298.257222101

            val params = mutableMapOf<String, Double>()
            Regex("""PARAMETER\s*\[\s*"([^"]+)"\s*,\s*([-+\d.eE]+)\s*]""").findAll(wkt).forEach { m ->
                val key = m.groupValues[1].lowercase()
                val value = m.groupValues[2].toDoubleOrNull() ?: 0.0
                params[key] = value
            }

            return WktProjection(
                projectionName = projName,
                semiMajor = semiMajor,
                inverseFlattening = invFlat,
                centralMeridianDeg = params["central_meridian"] ?: params["longitude_of_center"] ?: 0.0,
                latitudeOfOriginDeg = params["latitude_of_origin"] ?: params["latitude_of_center"] ?: 0.0,
                standardParallel1Deg = params["standard_parallel_1"] ?: 0.0,
                standardParallel2Deg = params["standard_parallel_2"] ?: params["standard_parallel_1"] ?: 0.0,
                falseEasting = params["false_easting"] ?: 0.0,
                falseNorthing = params["false_northing"] ?: 0.0,
                scaleFactor = params["scale_factor"] ?: 1.0
            )
        }

        /**
         * Decodes octal escape sequences in PDF literal strings, e.g. \133 -> '[', \042 -> '"', \055 -> '-'.
         */
        fun decodePdfEscapes(input: String): String {
            val sb = StringBuilder(input.length)
            var i = 0
            while (i < input.length) {
                val c = input[i]
                if (c == '\\' && i + 1 < input.length) {
                    val next = input[i + 1]
                    if (next in '0'..'7') {
                        var oct = "" + next
                        var advance = 2
                        if (i + 2 < input.length && input[i + 2] in '0'..'7') {
                            oct += input[i + 2]
                            advance = 3
                            if (i + 3 < input.length && input[i + 3] in '0'..'7') {
                                oct += input[i + 3]
                                advance = 4
                            }
                        }
                        val code = oct.toIntOrNull(8)
                        if (code != null) {
                            sb.append(code.toChar())
                        }
                        i += advance
                        continue
                    } else {
                        sb.append(next)
                        i += 2
                        continue
                    }
                }
                sb.append(c)
                i++
            }
            return sb.toString()
        }
    }
}
