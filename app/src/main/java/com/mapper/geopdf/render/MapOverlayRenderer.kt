package com.mapper.geopdf.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.mapper.geopdf.geo.DestinationGuidance
import com.mapper.geopdf.geo.DestinationPlacement
import com.mapper.geopdf.geo.GeoPdfDocumentInfo
import com.mapper.geopdf.geo.GeoViewport
import com.mapper.geopdf.geo.MapDestination
import com.mapper.geopdf.geo.MapScaleFormatter
import com.mapper.geopdf.geo.OverlayInsets
import com.mapper.geopdf.geo.ScreenBounds
import com.mapper.geopdf.state.MapUiState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders the local GPS position, directional movement arrow, accuracy halo, and scale bar
 * over the GeoPDF map canvas (shared between Phone UI and Android Auto Surface).
 */
object MapOverlayRenderer {

    // Base GPS arrow radius increased by 20% (24f -> 28.8f, ~52x66 px) and shared across nav icons.
    private const val NAV_ICON_RADIUS_PX = 28.8f
    private const val NAV_ICON_WIDTH_PX = NAV_ICON_RADIUS_PX * 1.80f
    private const val NAV_ICON_HEIGHT_PX = NAV_ICON_RADIUS_PX * 2.30f
    private const val OFFSCREEN_ARROW_GAP_PX = 6f
    private const val OFFSCREEN_ARROW_OUTLINE_MARGIN_PX = 4.5f
    private const val OFFSCREEN_ARROW_CLEARANCE_PX =
        OFFSCREEN_ARROW_GAP_PX + NAV_ICON_HEIGHT_PX + OFFSCREEN_ARROW_OUTLINE_MARGIN_PX

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
        strokeWidth = 8.4f
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

    private val destHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val destHaloStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }

    private val destDarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(20, 25, 31)
    }

    private val destDarkStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(20, 25, 31)
    }

    private val destFlagOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeJoin = Paint.Join.ROUND
        color = Color.rgb(20, 25, 31)
    }

    private val destAccentFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(255, 143, 0)
    }

    private val destBorderStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.rgb(255, 143, 0)
    }

    private val destBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(225, 20, 26, 31)
    }

    private val destTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        isFakeBoldText = true
    }

    private val arrowPath = Path()
    private val destArrowPath = Path()
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
        insets: OverlayInsets = OverlayInsets()
    ) {
        val hudRect = drawGpsOverlaysAndHud(
            canvas = canvas,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            mapInfo = mapInfo,
            state = state,
            extraLeftInsetPx = insets.leftPx,
            extraBottomInsetPx = insets.bottomPx
        )

        val scaleBarRect = drawScaleBar(
            canvas = canvas,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            viewport = mapInfo.primaryViewport,
            state = state,
            extraLeftInsetPx = insets.leftPx,
            extraBottomInsetPx = insets.bottomPx,
            hudBoxTop = hudRect?.top
        )

        val destination = state.destination
        if (destination != null) {
            val combinedExclusions = insets.excludedRects + listOfNotNull(hudRect, scaleBarRect)
            drawDestinationOverlay(
                canvas = canvas,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                viewport = mapInfo.primaryViewport,
                state = state,
                destination = destination,
                insets = insets.copy(excludedRects = combinedExclusions)
            )
        }
    }

    private fun drawGpsOverlaysAndHud(
        canvas: Canvas,
        viewportWidth: Int,
        viewportHeight: Int,
        mapInfo: GeoPdfDocumentInfo,
        state: MapUiState,
        extraLeftInsetPx: Int,
        extraBottomInsetPx: Int
    ): ScreenBounds? {
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
            val rayLen = (43f + gps.speedMetersPerSec * 2.6f).coerceAtMost(114f)
            canvas.drawLine(0f, -26.4f, 0f, -rayLen, headingRayPaint)
        }

        // Navigation chevron/arrow geometry pointing UP (0, -Y) in local rotated coordinates
        val arrowRadius = NAV_ICON_RADIUS_PX
        arrowPath.reset()
        arrowPath.moveTo(0f, -arrowRadius * 1.25f) // Sharp forward tip
        arrowPath.lineTo(arrowRadius * 0.90f, arrowRadius * 1.05f) // Right wing
        arrowPath.lineTo(0f, arrowRadius * 0.45f) // Tail notch
        arrowPath.lineTo(-arrowRadius * 0.90f, arrowRadius * 1.05f) // Left wing
        arrowPath.close()

        // Drop shadow for visibility over light map areas
        canvas.save()
        canvas.translate(0f, 3.5f)
        canvas.drawPath(arrowPath, shadowPaint)
        canvas.restore()

        // White halo border + high-visibility navigation blue fill
        canvas.drawPath(arrowPath, arrowOutlinePaint)
        canvas.drawPath(arrowPath, arrowFillPaint)
        canvas.drawCircle(0f, 0f, 5.4f, centerDotPaint)

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
        return ScreenBounds(boxLeft, boxTop, boxRight, boxBottom)
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
    ): ScreenBounds? {
        if (viewportWidth <= 0 || viewportHeight <= 0 || state.scale <= 0f) return null

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
        ) ?: return null

        val padX = 16f
        val labelWidth = scaleTextPaint.measureText(spec.label)
        val contentWidth = maxOf(spec.widthPx, labelWidth)
        val pillLeft = 24f + extraLeftInsetPx
        val pillRight = (pillLeft + contentWidth + padX * 2f).coerceAtMost(viewportWidth - 24f)
        if (pillRight <= pillLeft + padX * 2f) return null

        val pillBottom = hudBoxTop?.let { it - 10f } ?: (viewportHeight - 24f - extraBottomInsetPx)
        val pillTop = pillBottom - 48f
        if (pillTop < 0f) return null

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
        return ScreenBounds(pillLeft, pillTop, pillRight, pillBottom)
    }

    private fun drawDestinationOverlay(
        canvas: Canvas,
        viewportWidth: Int,
        viewportHeight: Int,
        viewport: GeoViewport,
        state: MapUiState,
        destination: MapDestination,
        insets: OverlayInsets
    ) {
        val distText = state.gpsPosition?.let { gps ->
            val meters = MapScaleFormatter.haversineMeters(
                gps.latitude,
                gps.longitude,
                destination.latitude,
                destination.longitude
            )
            MapScaleFormatter.formatDistanceMeters(meters, state.distanceUnit)
        }

        val padX = 18f
        val iconW = 36f
        val iconH = 25f
        val iconGap = 12f
        val pillH = 56f
        val pillW = if (distText != null) {
            padX + iconW + iconGap + destTextPaint.measureText(distText) + padX
        } else {
            58f
        }

        val placement = DestinationGuidance.computePlacement(
            destination = destination,
            viewport = viewport,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            offsetX = state.offsetX,
            offsetY = state.offsetY,
            scale = state.scale,
            insets = insets,
            pillHalfWidthPx = pillW * 0.5f,
            pillHalfHeightPx = pillH * 0.5f,
            arrowClearancePx = OFFSCREEN_ARROW_CLEARANCE_PX
        ) ?: return

        when (placement) {
            is DestinationPlacement.VisibleFlag -> {
                drawCheckeredFlag(canvas, placement.screenX, placement.screenY)
            }
            is DestinationPlacement.OffscreenBorder -> {
                drawOffscreenDestinationBadge(
                    canvas = canvas,
                    centerX = placement.badgeCenterX,
                    centerY = placement.badgeCenterY,
                    angleDeg = placement.directionAngleDeg,
                    pillW = pillW,
                    pillH = pillH,
                    iconW = iconW,
                    iconH = iconH,
                    iconGap = iconGap,
                    padX = padX,
                    distText = distText
                )
            }
        }
    }

    private fun drawCheckeredFlag(canvas: Canvas, x: Float, y: Float) {
        // Anchor dot at exact selected map coordinate
        canvas.drawCircle(x, y + 3f, 9.5f, shadowPaint)
        canvas.drawCircle(x, y, 9f, destHaloPaint)
        canvas.drawCircle(x, y, 5.5f, destAccentFillPaint)

        // Tilted pole + checkered cloth scaled to NAV_ICON_HEIGHT_PX (~66 px)
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(-6f)

        val poleTopY = -NAV_ICON_HEIGHT_PX
        canvas.drawLine(0f, -3f, 0f, poleTopY, destHaloStrokePaint)
        canvas.drawLine(0f, -3f, 0f, poleTopY, destDarkStrokePaint)

        val flagLeft = 2.5f
        val flagTop = poleTopY + 2f
        val flagW = NAV_ICON_WIDTH_PX
        val flagH = NAV_ICON_WIDTH_PX * 0.68f

        val haloRect = RectF(flagLeft - 3f, flagTop - 3f, flagLeft + flagW + 3f, flagTop + flagH + 3f)
        canvas.drawRoundRect(haloRect, 4f, 4f, shadowPaint)
        canvas.drawRoundRect(haloRect, 4f, 4f, destHaloPaint)
        drawCheckeredGrid(canvas, flagLeft, flagTop, flagW, flagH, cols = 4, rows = 3)
        canvas.drawRect(flagLeft, flagTop, flagLeft + flagW, flagTop + flagH, destFlagOutlinePaint)

        // Top finial cap
        canvas.drawCircle(0f, poleTopY, 5f, destHaloPaint)
        canvas.drawCircle(0f, poleTopY, 3f, destDarkPaint)
        canvas.restore()
    }

    private fun drawOffscreenDestinationBadge(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        angleDeg: Float,
        pillW: Float,
        pillH: Float,
        iconW: Float,
        iconH: Float,
        iconGap: Float,
        padX: Float,
        distText: String?
    ) {
        val halfW = pillW * 0.5f
        val halfH = pillH * 0.5f
        val pillRect = RectF(centerX - halfW, centerY - halfH, centerX + halfW, centerY + halfH)

        // Directional pointer orbiting outside the horizontal badge along angleDeg,
        // matching the NAV_ICON_WIDTH_PX x NAV_ICON_HEIGHT_PX (~52x66 px) arrow icon size.
        val rad = Math.toRadians(angleDeg.toDouble())
        val ux = sin(rad).toFloat()
        val uy = (-cos(rad)).toFloat()
        val tx = if (abs(ux) > 1e-4f) halfW / abs(ux) else Float.MAX_VALUE
        val ty = if (abs(uy) > 1e-4f) halfH / abs(uy) else Float.MAX_VALUE
        val edgeDist = minOf(tx, ty)

        val arrowWingHalfW = NAV_ICON_WIDTH_PX * 0.5f
        val arrowWingY = -(edgeDist + OFFSCREEN_ARROW_GAP_PX)
        val arrowTipY = arrowWingY - NAV_ICON_HEIGHT_PX
        val arrowNotchY = arrowWingY - NAV_ICON_HEIGHT_PX * 0.26f

        canvas.save()
        canvas.translate(centerX, centerY)
        canvas.rotate(angleDeg)
        destArrowPath.reset()
        destArrowPath.moveTo(0f, arrowTipY)
        destArrowPath.lineTo(arrowWingHalfW, arrowWingY)
        destArrowPath.lineTo(0f, arrowNotchY)
        destArrowPath.lineTo(-arrowWingHalfW, arrowWingY)
        destArrowPath.close()
        canvas.drawPath(destArrowPath, shadowPaint)
        canvas.drawPath(destArrowPath, arrowOutlinePaint)
        canvas.drawPath(destArrowPath, destAccentFillPaint)
        canvas.restore()

        // Horizontal upright badge so distance text is always readable on any screen edge
        val shadowRect = RectF(pillRect.left, pillRect.top + 3f, pillRect.right, pillRect.bottom + 3f)
        canvas.drawRoundRect(shadowRect, 16f, 16f, shadowPaint)
        canvas.drawRoundRect(pillRect, 16f, 16f, destBgPaint)
        canvas.drawRoundRect(pillRect, 16f, 16f, destBorderStrokePaint)

        val iconLeft = if (distText != null) pillRect.left + padX else centerX - iconW * 0.5f
        val iconTop = centerY - iconH * 0.5f
        drawCheckeredGrid(canvas, iconLeft, iconTop, iconW, iconH, cols = 4, rows = 3)
        canvas.drawRect(iconLeft, iconTop, iconLeft + iconW, iconTop + iconH, destFlagOutlinePaint)

        if (distText != null) {
            val textX = iconLeft + iconW + iconGap
            val baselineY = centerY - (destTextPaint.descent() + destTextPaint.ascent()) * 0.5f
            canvas.drawText(distText, textX, baselineY, destTextPaint)
        }
    }

    private fun drawCheckeredGrid(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        cols: Int,
        rows: Int
    ) {
        canvas.drawRect(left, top, left + width, top + height, destHaloPaint)
        val cellW = width / cols
        val cellH = height / rows
        for (r in 0 until rows) {
            val cellTop = top + r * cellH
            val cellBottom = cellTop + cellH
            for (c in 0 until cols) {
                if ((r + c) % 2 == 0) {
                    val cellLeft = left + c * cellW
                    canvas.drawRect(cellLeft, cellTop, cellLeft + cellW, cellBottom, destDarkPaint)
                }
            }
        }
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
