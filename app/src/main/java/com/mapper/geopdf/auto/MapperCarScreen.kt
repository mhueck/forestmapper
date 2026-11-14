package com.mapper.geopdf.auto

import android.graphics.Rect
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.PanModeListener
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mapper.geopdf.R
import com.mapper.geopdf.state.MapController
import kotlinx.coroutines.launch

/**
 * Main Android Auto screen that draws the georeferenced PDF map and directional GPS
 * movement arrow onto the vehicle's [SurfaceContainer] and exposes touch/rotary controls
 * for Zoom In, Zoom Out, Pan, Re-center (Follow GPS), and Map Selection.
 */
class MapperCarScreen(carContext: CarContext) : Screen(carContext) {

    private val mapController = MapController.getInstance(carContext)

    private var surface: Surface? = null
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0
    private var isInPanMode: Boolean = false

    private val redrawListener: () -> Unit = {
        renderSurface()
    }

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            synchronized(this@MapperCarScreen) {
                surface = surfaceContainer.surface
                surfaceWidth = surfaceContainer.width
                surfaceHeight = surfaceContainer.height
            }
            if (surfaceWidth > 0 && surfaceHeight > 0) {
                mapController.updateViewportSize(surfaceWidth, surfaceHeight)
            }
            renderSurface()
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {
            renderSurface()
        }

        override fun onStableAreaChanged(stableArea: Rect) {
            renderSurface()
        }

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            synchronized(this@MapperCarScreen) {
                surface = null
            }
        }

        override fun onScroll(distanceX: Float, distanceY: Float) {
            // In SurfaceCallback.onScroll, distanceX/distanceY are the scroll deltas
            mapController.panBy(-distanceX, -distanceY, userInitiated = true)
        }

        override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
            val clamped = scaleFactor.coerceIn(0.7f, 1.4f)
            mapController.zoomBy(clamped, focusX, focusY)
        }
    }

    private var lastTemplateKey: String = ""
    private var lastEmptyCanvasKey: String = ""

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
                owner.lifecycleScope.launch {
                    owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        mapController.uiState.collect { state ->
                            val dl = state.activeDownload
                            val pctBucket = dl?.progressPercent?.let { (it / 5) * 5 }
                            val key = "${state.activeMap?.file?.absolutePath}|${state.isFollowingGps}|${dl?.mapId}|$pctBucket"
                            if (key != lastTemplateKey) {
                                lastTemplateKey = key
                                invalidate()
                            }
                            if (state.activeMap == null) {
                                val canvasKey = "${state.statusMessage}|${dl?.bytesDownloaded}"
                                if (canvasKey != lastEmptyCanvasKey) {
                                    lastEmptyCanvasKey = canvasKey
                                    renderSurface()
                                }
                            } else {
                                lastEmptyCanvasKey = ""
                            }
                        }
                    }
                }
            }

            override fun onStart(owner: LifecycleOwner) {
                mapController.addRedrawListener(redrawListener)
                renderSurface()
            }

            override fun onStop(owner: LifecycleOwner) {
                mapController.removeRedrawListener(redrawListener)
            }
        })
    }

    private fun renderSurface() {
        synchronized(this) {
            val s = surface ?: return
            if (!s.isValid || surfaceWidth <= 0 || surfaceHeight <= 0) return
            try {
                val canvas = s.lockCanvas(null) ?: return
                try {
                    mapController.renderToCanvas(canvas, surfaceWidth, surfaceHeight)
                } finally {
                    s.unlockCanvasAndPost(canvas)
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun onGetTemplate(): Template {
        val state = mapController.uiState.value

        val dl = state.activeDownload
        val mapsButtonTitle = when {
            dl != null && dl.progressPercent != null -> "DL ${(dl.progressPercent!! / 5) * 5}%"
            dl != null -> "Downloading..."
            else -> state.activeMap?.title?.take(16) ?: "Maps"
        }

        // Top Action Strip: Select PDF Map and Re-center text button
        val topActionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle(mapsButtonTitle)
                    .setIcon(
                        CarIcon.Builder(
                            IconCompat.createWithResource(carContext, R.drawable.ic_map)
                        ).build()
                    )
                    .setOnClickListener {
                        screenManager.push(MapPickerCarScreen(carContext))
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle(if (state.isFollowingGps) "Centered" else "Re-center")
                    .setBackgroundColor(
                        if (state.isFollowingGps) CarColor.PRIMARY else CarColor.DEFAULT
                    )
                    .setOnClickListener {
                        mapController.recenterOnGps()
                        invalidate()
                    }
                    .build()
            )
            .build()

        // Map Action Strip (max 4 icon buttons for map interaction: Pan, Zoom In, Zoom Out, Re-center)
        val mapActionStrip = ActionStrip.Builder()
            .addAction(Action.PAN)
            .addAction(
                Action.Builder()
                    .setIcon(
                        CarIcon.Builder(
                            IconCompat.createWithResource(carContext, R.drawable.ic_zoom_in)
                        ).build()
                    )
                    .setOnClickListener {
                        mapController.zoomBy(1.45f)
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(
                        CarIcon.Builder(
                            IconCompat.createWithResource(carContext, R.drawable.ic_zoom_out)
                        ).build()
                    )
                    .setOnClickListener {
                        mapController.zoomBy(1f / 1.45f)
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(
                        CarIcon.Builder(
                            IconCompat.createWithResource(carContext, R.drawable.ic_my_location)
                        ).build()
                    )
                    .setOnClickListener {
                        mapController.recenterOnGps()
                        invalidate()
                    }
                    .build()
            )
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(topActionStrip)
            .setMapActionStrip(mapActionStrip)
            .setPanModeListener(PanModeListener { isInPan ->
                isInPanMode = isInPan
            })
            .build()
    }
}
