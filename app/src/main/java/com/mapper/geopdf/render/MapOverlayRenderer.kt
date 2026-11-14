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
import com.mapper.geopdf.state.MapUiState
import java.util.Locale

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

    private val arrowPath = Path()

    fun drawOverlays(
        canvas: Canvas,
        viewportWidth: Int,
        viewportHeight: Int,
        mapInfo: GeoPdfDocumentInfo,
        state: MapUiState,
        extraLeftInsetPx: Int = 0,
        extraBottomInsetPx: Int = 0
    ) {
        val gps = state.gpsPosition ?: return
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
        val speedMph = gps.speedMetersPerSec * 2.23694f
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
            "%.1f mph  •  %s  •  %s",
            speedMph,
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
