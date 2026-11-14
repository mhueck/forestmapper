package com.mapper.geopdf.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.mapper.geopdf.state.MapController

/**
 * Custom interactive map canvas view for the Android phone/tablet UI.
 * Supports pinch-to-zoom, double-tap zoom, and pan gestures while rendering
 * large GeoPDF tiles and the live GPS directional movement arrow.
 */
class GeoPdfMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val mapController = MapController.getInstance(context)

    private val redrawCallback: () -> Unit = {
        postInvalidateOnAnimation()
    }

    private var isScaling = false

    private val scaleGestureDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isScaling = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor.coerceIn(0.7f, 1.4f)
                mapController.zoomBy(factor, detector.focusX, detector.focusY)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (!isScaling) {
                    mapController.panBy(-distanceX, -distanceY, userInitiated = true)
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                mapController.zoomBy(1.6f, e.x, e.y)
                return true
            }
        }
    )

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        mapController.addRedrawListener(redrawCallback)
    }

    override fun onDetachedFromWindow() {
        mapController.removeRedrawListener(redrawCallback)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            mapController.updateViewportSize(w, h)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val scaleHandled = scaleGestureDetector.onTouchEvent(event)
        val gestureHandled = gestureDetector.onTouchEvent(event)
        return scaleHandled || gestureHandled || super.onTouchEvent(event)
    }

    private var overlayLeftInsetPx: Int = 0
    private var overlayBottomInsetPx: Int = 0

    fun setOverlayInsets(leftPx: Int, bottomPx: Int) {
        if (overlayLeftInsetPx != leftPx || overlayBottomInsetPx != bottomPx) {
            overlayLeftInsetPx = leftPx
            overlayBottomInsetPx = bottomPx
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        mapController.renderToCanvas(canvas, width, height, overlayLeftInsetPx, overlayBottomInsetPx)
    }
}
