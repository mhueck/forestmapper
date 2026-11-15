package com.mapper.geopdf.geo

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

data class MapDestination(
    val latitude: Double,
    val longitude: Double
) {
    fun isValid(): Boolean =
        latitude.isFinite() &&
            longitude.isFinite() &&
            latitude in -90.0..90.0 &&
            longitude in -180.0..180.0
}

data class ScreenBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun contains(x: Float, y: Float): Boolean =
        x in left..right && y in top..bottom

    fun expanded(padX: Float, padY: Float): ScreenBounds =
        ScreenBounds(
            left = left - padX,
            top = top - padY,
            right = right + padX,
            bottom = bottom + padY
        )

    fun centerX(): Float = (left + right) * 0.5f
    fun centerY(): Float = (top + bottom) * 0.5f
}

data class OverlayInsets(
    val leftPx: Int = 0,
    val topPx: Int = 0,
    val rightPx: Int = 0,
    val bottomPx: Int = 0,
    val excludedRects: List<ScreenBounds> = emptyList()
)

sealed class DestinationPlacement {
    data class VisibleFlag(
        val screenX: Float,
        val screenY: Float
    ) : DestinationPlacement()

    data class OffscreenBorder(
        val badgeCenterX: Float,
        val badgeCenterY: Float,
        val directionAngleDeg: Float
    ) : DestinationPlacement()
}

/**
 * Computes screen placement for a selected destination:
 * - [DestinationPlacement.VisibleFlag] when the destination falls inside both the active map
 *   page and the unobstructed screen bounds.
 * - [DestinationPlacement.OffscreenBorder] when the destination lies outside the visible section
 *   of the map, intersecting the direction ray with the safe border and avoiding HUD/controls.
 */
object DestinationGuidance {

    private const val MAX_DIRECT_PROJECTION_METERS = 2_500_000.0

    fun computePlacement(
        destination: MapDestination,
        viewport: GeoViewport,
        viewportWidth: Int,
        viewportHeight: Int,
        offsetX: Float,
        offsetY: Float,
        scale: Float,
        insets: OverlayInsets = OverlayInsets(),
        pillHalfWidthPx: Float = 64f,
        pillHalfHeightPx: Float = 20f,
        arrowClearancePx: Float = 30f
    ): DestinationPlacement? {
        if (!destination.isValid() || viewportWidth <= 0 || viewportHeight <= 0 || scale <= 0f) {
            return null
        }

        val visLeft = insets.leftPx.toFloat().coerceIn(0f, viewportWidth.toFloat())
        val visTop = insets.topPx.toFloat().coerceIn(0f, viewportHeight.toFloat())
        val visRight = (viewportWidth - insets.rightPx).toFloat().coerceIn(visLeft, viewportWidth.toFloat())
        val visBottom = (viewportHeight - insets.bottomPx).toFloat().coerceIn(visTop, viewportHeight.toFloat())
        val visibleBounds = ScreenBounds(visLeft, visTop, visRight, visBottom)
        if (visRight - visLeft < 24f || visBottom - visTop < 24f) {
            return null
        }

        val centerPageX = ((visibleBounds.centerX() - offsetX) / scale)
            .coerceIn(0f, viewport.pageWidthPt)
        val centerPageY = ((visibleBounds.centerY() - offsetY) / scale)
            .coerceIn(0f, viewport.pageHeightPt)
        val (centerLat, centerLon) = viewport.pageToLatLon(centerPageX, centerPageY)

        val directMeters = if (centerLat.isFinite() && centerLon.isFinite()) {
            MapScaleFormatter.haversineMeters(centerLat, centerLon, destination.latitude, destination.longitude)
        } else {
            Double.POSITIVE_INFINITY
        }

        var destScreenX: Float? = null
        var destScreenY: Float? = null
        if (directMeters <= MAX_DIRECT_PROJECTION_METERS) {
            val destPage = viewport.latLonToPage(destination.latitude, destination.longitude)
            if (destPage.x.isFinite() && destPage.y.isFinite()) {
                val sx = offsetX + destPage.x * scale
                val sy = offsetY + destPage.y * scale
                if (sx.isFinite() && sy.isFinite()) {
                    val onMapPage = destPage.x in 0f..viewport.pageWidthPt &&
                        destPage.y in 0f..viewport.pageHeightPt
                    if (onMapPage && visibleBounds.contains(sx, sy)) {
                        return DestinationPlacement.VisibleFlag(sx, sy)
                    }
                    destScreenX = sx
                    destScreenY = sy
                }
            }
        }

        val padX = pillHalfWidthPx + arrowClearancePx
        val padY = pillHalfHeightPx + arrowClearancePx
        val borderLeft = (visLeft + padX).coerceAtMost(visibleBounds.centerX())
        val borderRight = (visRight - padX).coerceAtLeast(visibleBounds.centerX())
        val borderTop = (visTop + padY).coerceAtMost(visibleBounds.centerY())
        val borderBottom = (visBottom - padY).coerceAtLeast(visibleBounds.centerY())
        val borderBounds = ScreenBounds(borderLeft, borderTop, borderRight, borderBottom)

        val originX = borderBounds.centerX()
        val originY = borderBounds.centerY()

        var dx: Float
        var dy: Float
        if (destScreenX != null && destScreenY != null) {
            dx = destScreenX - originX
            dy = destScreenY - originY
        } else {
            dx = 0f
            dy = 0f
        }

        if (hypot(dx.toDouble(), dy.toDouble()) < 1e-3 && centerLat.isFinite() && centerLon.isFinite()) {
            val bearingDeg = MapScaleFormatter.initialBearingDegrees(
                centerLat,
                centerLon,
                destination.latitude,
                destination.longitude
            )
            val screenAngleDeg = bearingDeg + viewport.northAngleOnPageDegrees(centerLat, centerLon)
            val rad = Math.toRadians(screenAngleDeg)
            dx = sin(rad).toFloat()
            dy = (-cos(rad)).toFloat()
        }

        if (hypot(dx.toDouble(), dy.toDouble()) < 1e-6) {
            dx = 0f
            dy = -1f
        }

        val hit = intersectRayWithRect(originX, originY, dx, dy, borderBounds)
        val adjusted = avoidExcludedRects(
            x = hit.first,
            y = hit.second,
            bounds = borderBounds,
            excludedRects = insets.excludedRects,
            padX = padX,
            padY = padY
        )

        val destOutsideBorder = destScreenX != null && destScreenY != null &&
            !borderBounds.contains(destScreenX, destScreenY) &&
            hypot((destScreenX - adjusted.first).toDouble(), (destScreenY - adjusted.second).toDouble()) > 20.0
        val dirDx = if (destOutsideBorder) destScreenX!! - adjusted.first else dx
        val dirDy = if (destOutsideBorder) destScreenY!! - adjusted.second else dy

        val angleDeg = Math.toDegrees(atan2(dirDx.toDouble(), (-dirDy).toDouble())).toFloat()
        return DestinationPlacement.OffscreenBorder(
            badgeCenterX = adjusted.first,
            badgeCenterY = adjusted.second,
            directionAngleDeg = angleDeg
        )
    }

    internal fun intersectRayWithRect(
        originX: Float,
        originY: Float,
        dx: Float,
        dy: Float,
        bounds: ScreenBounds
    ): Pair<Float, Float> {
        var minT = Float.POSITIVE_INFINITY
        if (dx > 1e-5f) {
            minT = min(minT, (bounds.right - originX) / dx)
        } else if (dx < -1e-5f) {
            minT = min(minT, (bounds.left - originX) / dx)
        }
        if (dy > 1e-5f) {
            minT = min(minT, (bounds.bottom - originY) / dy)
        } else if (dy < -1e-5f) {
            minT = min(minT, (bounds.top - originY) / dy)
        }
        if (!minT.isFinite() || minT < 0f) {
            return originX.coerceIn(bounds.left, bounds.right) to
                originY.coerceIn(bounds.top, bounds.bottom)
        }
        val hx = (originX + dx * minT).coerceIn(bounds.left, bounds.right)
        val hy = (originY + dy * minT).coerceIn(bounds.top, bounds.bottom)
        return hx to hy
    }

    private fun avoidExcludedRects(
        x: Float,
        y: Float,
        bounds: ScreenBounds,
        excludedRects: List<ScreenBounds>,
        padX: Float,
        padY: Float
    ): Pair<Float, Float> {
        var curX = x.coerceIn(bounds.left, bounds.right)
        var curY = y.coerceIn(bounds.top, bounds.bottom)
        val expanded = excludedRects.map { it.expanded(padX, padY) }

        for (obstacle in expanded) {
            if (!obstacle.contains(curX, curY)) continue

            val candidates = listOf(
                curX to obstacle.top,
                curX to obstacle.bottom,
                obstacle.left to curY,
                obstacle.right to curY
            ).filter { (cx, cy) -> bounds.contains(cx, cy) }

            val clearCandidate = candidates
                .filter { (cx, cy) -> expanded.none { other -> other.contains(cx, cy) } }
                .minByOrNull { (cx, cy) -> abs(cx - curX) + abs(cy - curY) }

            val chosen = clearCandidate
                ?: candidates.minByOrNull { (cx, cy) -> abs(cx - curX) + abs(cy - curY) }

            if (chosen != null) {
                curX = chosen.first
                curY = chosen.second
            }
        }
        return curX to curY
    }
}
