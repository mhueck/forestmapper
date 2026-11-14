package com.mapper.geopdf.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.mapper.geopdf.geo.GeoPdfDocumentInfo
import com.mapper.geopdf.geo.GeoViewport
import com.mapper.geopdf.geo.MapScaleFormatter
import com.mapper.geopdf.state.MapUiState
import java.util.Locale
import kotlin.math.abs

/**
 * Renders the local GPS position, directional movement arrow, accuracy halo, and scale bar
 * over the GeoPDF map canvas (shared between Phone UI and Android Auto Surface).
 */
object MapOverlayRenderer {

    private val accuracyFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(45, 25, 118, 210)
    }

    private val accuracyStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(130, 25, 118, 210)
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(95, 0, 0, 0)
    }

    private val arrowOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 7f
        color = Color.WHITE
    }

    private val arrowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(0, 122, 255)
    }

    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val headingRayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(170, 0, 122, 255)
    }

    private val hudBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(195, 20, 26, 31)
    }

    private val hudTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        isFakeBoldText = true
    }

    private val hudSubTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(180, 205, 230)
        textSize = 21f
    }

    private val scaleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    private val scaleLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.2f
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.MITER
        color = Color.WHITE
    }

    private val arrowPath = Path()
    private val scaleBracketPath = Path()
    private val scaleCacheLock = Any()
    private var cachedViewport: GeoViewport? = null
    private var cachedPageX: Float = 0f
    private var cachedPageY: Float = 0f
    private var cachedGroundMetersPerPt: Double = 0.0

    fun drawOverlays(
        canvas: Canvas,
        viewportWidth: Int,
        viewportHeight: Int,
        mapInfo: GeoPdfDocumentInfo,
        state: MapUiState,
        extraLeftInsetPx: Int = 0,
        extraBottomInsetPx: Int = 0
    ) {
        val hudBoxTop = drawGpsOverlaysAndHud(
            canvas = canvas,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            mapInfo = mapInfo,
            state = state,
            extraLeftInsetPx = extraLeftInsetPx,
            extraBottomInsetPx = extraBottomInsetPx
        )

        drawScaleBar(
            canvas = canvas,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            viewport = mapInfo.primaryViewport,
            state = state,
            extraLeftInsetPx = extraLeftInsetPx,
            extraBottomInsetPx = extraBottomInsetPx,
            hudBoxTop = hudBoxTop
        )
    }

    private fun drawGpsOverlaysAndHud(
        canvas: Canvas,
        viewportWidth: Int,
        viewportHeight: Int,
        mapInfo: GeoPdfDocumentInfo,
        state: MapUiState,
        extraLeftInsetPx: Int,
        extraBottomInsetPx: Int
    ): Float? {
        val gps = state.gpsPosition ?: return null
        val viewport = mapInfo.primaryViewport

        // Convert GPS (lat, lon) to PDF page point, then to screen pixel
        val pagePt = viewport.latLonToPage(gps.latitude, gps.longitude)
        val screenX = state.offsetX + pagePt.x * state.scale
        val screenY = state.offsetY + pagePt.y * state.scale

        // Compute true map rotation angle for the movement bearing (compensating for conic convergence)
        val mapNorthAngle = viewport.northAngleOnPageDegrees(gps.latitude, gps.longitude)
        val arrowAngleDeg = gps.bearingDegrees + mapNorthAngle

        // 1. Accuracy circle scaled to map resolution
        val metersPerPt = viewport.metersPerPoint.toFloat().coerceAtLeast(0.1f)
        val accuracyRadiusPx = ((gps.accuracyMeters / metersPerPt) * state.scale)
            .coerceIn(18f, minOf(viewportWidth, viewportHeight) * 0.35f)

        canvas.drawCircle(screenX, screenY, accuracyRadiusPx, accuracyFillPaint)
        canvas.drawCircle(screenX, screenY, accuracyRadiusPx, accuracyStrokePaint)

        // 2. Draw directional navigation arrow rotated in the direction of movement
        canvas.save()
        canvas.translate(screenX, screenY)
        canvas.rotate(arrowAngleDeg)

        // Velocity vector line ahead of arrow when moving
        if (gps.speedMetersPerSec >= 0.3f || gps.isSimulated) {
            val rayLen = (36f + gps.speedMetersPerSec * 2.2f).coerceAtMost(95f)
            canvas.drawLine(0f, -22f, 0f, -rayLen, headingRayPaint)
        }

        // Navigation chevron/arrow geometry pointing UP (0, -Y) in local rotated coordinates
        val arrowRadius = 24f
        arrowPath.reset()
        arrowPath.moveTo(0f, -arrowRadius * 1.25f) // Sharp forward tip
        arrowPath.lineTo(arrowRadius * 0.90f, arrowRadius * 1.05f) // Right wing
        arrowPath.lineTo(0f, arrowRadius * 0.45f) // Tail notch
        arrowPath.lineTo(-arrowRadius * 0.90f, arrowRadius * 1.05f) // Left wing
        arrowPath.close()

        // Drop shadow for visibility over light map areas
        canvas.save()
        canvas.translate(0f, 3f)
        canvas.drawPath(arrowPath, shadowPaint)
        canvas.restore()

        // White halo border + high-visibility navigation blue fill
        canvas.drawPath(arrowPath, arrowOutlinePaint)
        canvas.drawPath(arrowPath, arrowFillPaint)
        canvas.drawCircle(0f, 0f, 4.5f, centerDotPaint)

        canvas.restore()

        // 3. Draw compact bottom-left GPS & heading pill (visible on both Phone and Android Auto)
        val cardinal = bearingToCardinal(gps.bearingDegrees)
        val speedText = MapScaleFormatter.formatSpeed(gps.speedMetersPerSec, state.distanceUnit)
        val line1 = String.format(
            Locale.US,
            "%.5f°, %.5f°  •  %s (%.0f°)",
            gps.latitude,
            gps.longitude,
            cardinal,
            gps.bearingDegrees
        )
        val line2 = String.format(
            Locale.US,
            "%s  •  %s  •  %s",
            speedText,
            if (state.isFollowingGps) "FOLLOWING GPS" else "FREE PAN (Tap Re-center)",
            if (gps.isSimulated) "SIMULATED GPS" else "LIVE GPS"
        )

        val pad = 18f
        val textW = maxOf(hudTextPaint.measureText(line1), hudSubTextPaint.measureText(line2))
        val boxLeft = 24f + extraLeftInsetPx
        val boxBottom = viewportHeight - 24f - extraBottomInsetPx
        val boxTop = boxBottom - 76f
        val boxRight = (boxLeft + textW + pad * 2).coerceAtMost(viewportWidth - 24f)

        val rect = RectF(boxLeft, boxTop, boxRight, boxBottom)
        canvas.drawRoundRect(rect, 16f, 16f, hudBgPaint)
        canvas.drawText(line1, boxLeft + pad, boxTop + 32f, hudTextPaint)
        canvas.drawText(line2, boxLeft + pad, boxTop + 60f, hudSubTextPaint)
        return boxTop
    }

    private fun drawScaleBar(
        canvas: Canvas,
        viewportWidth: Int,
        viewportHeight: Int,
        viewport: GeoViewport,
        state: MapUiState,
        extraLeftInsetPx: Int,
        extraBottomInsetPx: Int,
        hudBoxTop: Float?
    ) {
        if (viewportWidth <= 0 || viewportHeight <= 0 || state.scale <= 0f) return

        val safeScale = state.scale.coerceAtLeast(1e-4f)
        val centerPageX = ((viewportWidth * 0.5f - state.offsetX) / safeScale)
            .coerceIn(viewport.bboxPage.left, viewport.bboxPage.right)
        val centerPageY = ((viewportHeight * 0.5f - state.offsetY) / safeScale)
            .coerceIn(viewport.bboxPage.top, viewport.bboxPage.bottom)

        val groundMetersPerPt = resolveGroundMetersPerPoint(viewport, centerPageX, centerPageY)
        val groundMetersPerPx = groundMetersPerPt / safeScale.toDouble()
        val maxBarWidthPx = (viewportWidth * 0.22f).coerceIn(100f, 220f)
        val spec = MapScaleFormatter.computeScaleBar(
            groundMetersPerPx = groundMetersPerPx,
            unit = state.distanceUnit,
            maxWidthPx = maxBarWidthPx
        ) ?: return

        val padX = 16f
        val labelWidth = scaleTextPaint.measureText(spec.label)
        val contentWidth = maxOf(spec.widthPx, labelWidth)
        val pillLeft = 24f + extraLeftInsetPx
        val pillRight = (pillLeft + contentWidth + padX * 2f).coerceAtMost(viewportWidth - 24f)
        if (pillRight <= pillLeft + padX * 2f) return

        val pillBottom = hudBoxTop?.let { it - 10f } ?: (viewportHeight - 24f - extraBottomInsetPx)
        val pillTop = pillBottom - 48f
        if (pillTop < 0f) return

        canvas.drawRoundRect(RectF(pillLeft, pillTop, pillRight, pillBottom), 12f, 12f, hudBgPaint)

        val centerX = (pillLeft + pillRight) * 0.5f
        canvas.drawText(spec.label, centerX, pillTop + 22f, scaleTextPaint)

        val barWidthPx = minOf(spec.widthPx, (pillRight - pillLeft - padX * 2f).coerceAtLeast(10f))
        val barLeft = centerX - barWidthPx * 0.5f
        val barRight = barLeft + barWidthPx
        val barBottomY = pillBottom - 10f
        val tickTopY = barBottomY - 8f

        scaleBracketPath.reset()
        scaleBracketPath.moveTo(barLeft, tickTopY)
        scaleBracketPath.lineTo(barLeft, barBottomY)
        scaleBracketPath.lineTo(barRight, barBottomY)
        scaleBracketPath.lineTo(barRight, tickTopY)
        canvas.drawPath(scaleBracketPath, scaleLinePaint)
    }

    private fun resolveGroundMetersPerPoint(
        viewport: GeoViewport,
        pageX: Float,
        pageY: Float
    ): Double {
        synchronized(scaleCacheLock) {
            if (cachedViewport === viewport &&
                abs(pageX - cachedPageX) <= 24f &&
                abs(pageY - cachedPageY) <= 24f &&
                cachedGroundMetersPerPt > 0.0
            ) {
                return cachedGroundMetersPerPt
            }
        }
        val computed = viewport.groundMetersPerPointAt(pageX, pageY)
        synchronized(scaleCacheLock) {
            cachedViewport = viewport
            cachedPageX = pageX
            cachedPageY = pageY
            cachedGroundMetersPerPt = computed
        }
        return computed
    }

    private val emptyStateTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
    }

    fun drawEmptyState(
        canvas: Canvas,
        viewportWidth: Int,
        viewportHeight: Int,
        message: String
    ) {
        val maxWidth = (viewportWidth - 64).coerceAtLeast(120)
        val layout = StaticLayout.Builder
            .obtain(message, 0, message.length, emptyStateTextPaint, maxWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(4f, 1.0f)
            .build()
        val x = (viewportWidth - layout.width) * 0.5f
        val y = (viewportHeight - layout.height) * 0.5f
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun bearingToCardinal(deg: Float): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val idx = (((deg % 360f + 360f) % 360f + 22.5f) / 45f).toInt() % 8
        return dirs[idx]
    }
}
