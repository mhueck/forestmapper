package com.mapper.geopdf.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Memory-safe, multi-resolution tiled PDF renderer designed for large (50MB - 200MB+) GeoPDF maps.
 *
 * Uses a single dedicated background thread for all Android [PdfRenderer] operations (since PdfRenderer
 * requires serialized access), maintains an immediate full-page overview bitmap, and renders high-DPI
 * 512x512 tiles on demand for the currently visible screen region.
 */
class TiledPdfEngine(
    private val onInvalidate: () -> Unit
) {
    companion object {
        const val TILE_SIZE_PX = 512
        private const val MAX_OVERVIEW_DIM_PX = 1536
        private const val MIN_TILE_PYRAMID_SCALE = 0.5f
        private const val MAX_TILE_PYRAMID_SCALE = 16.0f
    }

    data class TileKey(
        val filePath: String,
        val scaleStep: Int, // Power-of-two step: scale = 2^scaleStep
        val tileX: Int,
        val tileY: Int
    )

    private val pdfExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "GeoPdfTileRenderer").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val pdfDispatcher = pdfExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + pdfDispatcher)

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private var currentPage: PdfRenderer.Page? = null
    private var currentFilePath: String = ""

    @Volatile
    var pageWidthPt: Float = 1f
        private set

    @Volatile
    var pageHeightPt: Float = 1f
        private set

    @Volatile
    private var overviewBitmap: Bitmap? = null

    // 48 MB LRU cache for 512x512 ARGB_8888 tiles (~1 MB each -> ~48 tiles cached)
    private val tileCache = object : LruCache<TileKey, Bitmap>(48 * 1024) {
        override fun sizeOf(key: TileKey, value: Bitmap): Int {
            return value.allocationByteCount / 1024
        }
    }

    private val inFlightKeys = Collections.synchronizedSet( LinkedHashSet<TileKey>())
    private var tileRenderJob: Job? = null

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /**
     * Opens a GeoPDF file and renders its low-res overview bitmap asynchronously.
     */
    fun openPdf(file: File, onReady: ((widthPt: Float, heightPt: Float) -> Unit)? = null) {
        scope.launch {
            closeInternal()
            try {
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                if (renderer.pageCount <= 0) {
                    renderer.close()
                    pfd.close()
                    return@launch
                }
                val page = renderer.openPage(0)
                fileDescriptor = pfd
                pdfRenderer = renderer
                currentPage = page
                currentFilePath = file.absolutePath
                pageWidthPt = page.width.toFloat().coerceAtLeast(1f)
                pageHeightPt = page.height.toFloat().coerceAtLeast(1f)

                // Render overview bitmap for instant panning/zooming backdrop
                val maxDim = max(pageWidthPt, pageHeightPt)
                val overviewScale = (MAX_OVERVIEW_DIM_PX / maxDim).coerceIn(0.1f, 2.0f)
                val ovW = (pageWidthPt * overviewScale).roundToInt().coerceAtLeast(1)
                val ovH = (pageHeightPt * overviewScale).roundToInt().coerceAtLeast(1)

                val ovBmp = Bitmap.createBitmap(ovW, ovH, Bitmap.Config.ARGB_8888)
                ovBmp.eraseColor(Color.WHITE)
                val matrix = Matrix().apply {
                    setScale(overviewScale, overviewScale)
                }
                page.render(ovBmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                overviewBitmap = ovBmp

                Dispatchers.Main.let {
                    CoroutineScope(it).launch {
                        onReady?.invoke(pageWidthPt, pageHeightPt)
                        onInvalidate()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Draws the PDF page onto [canvas] given the current transformation:
     *   screenX = pageX * scale + offsetX
     *   screenY = pageY * scale + offsetY
     * and queues high-resolution tile rendering for the visible viewport.
     */
    fun draw(
        canvas: Canvas,
        viewportWidth: Int,
        viewportHeight: Int,
        scale: Float,
        offsetX: Float,
        offsetY: Float
    ) {
        val ov = overviewBitmap ?: return
        val filePath = currentFilePath
        if (filePath.isEmpty()) return

        // 1. Always draw the overview bitmap first as a seamless backdrop
        val pageDestRect = RectF(
            offsetX,
            offsetY,
            offsetX + pageWidthPt * scale,
            offsetY + pageHeightPt * scale
        )
        canvas.drawBitmap(ov, null, pageDestRect, bitmapPaint)

        // 2. Determine discrete tile pyramid level for current zoom scale
        val clampedScale = scale.coerceIn(MIN_TILE_PYRAMID_SCALE, MAX_TILE_PYRAMID_SCALE)
        val scaleStep = floor(ln(clampedScale.toDouble()) / ln(2.0)).toInt().coerceIn(-1, 4)
        val tilePyramidScale = 2.0.pow(scaleStep.toDouble()).toFloat()

        // Visible rectangle in page coordinates (0..pageWidthPt, 0..pageHeightPt)
        val visPageLeft = ((-offsetX) / scale).coerceIn(0f, pageWidthPt)
        val visPageTop = ((-offsetY) / scale).coerceIn(0f, pageHeightPt)
        val visPageRight = ((viewportWidth - offsetX) / scale).coerceIn(0f, pageWidthPt)
        val visPageBottom = ((viewportHeight - offsetY) / scale).coerceIn(0f, pageHeightPt)

        if (visPageRight <= visPageLeft || visPageBottom <= visPageTop) return

        // Page points covered by a single 512px tile at tilePyramidScale
        val tileSpanPt = TILE_SIZE_PX / tilePyramidScale

        val minTileX = floor(visPageLeft / tileSpanPt).toInt().coerceAtLeast(0)
        val maxTileX = ceil(visPageRight / tileSpanPt).toInt() - 1
        val minTileY = floor(visPageTop / tileSpanPt).toInt().coerceAtLeast(0)
        val maxTileY = ceil(visPageBottom / tileSpanPt).toInt() - 1

        val missingKeys = mutableListOf<TileKey>()
        val tileDestRect = RectF()

        for (ty in minTileY..maxTileY) {
            for (tx in minTileX..maxTileX) {
                val key = TileKey(filePath, scaleStep, tx, ty)
                val cachedTile = tileCache.get(key)
                if (cachedTile != null) {
                    val tilePageLeft = tx * tileSpanPt
                    val tilePageTop = ty * tileSpanPt
                    val tilePageRight = tilePageLeft + tileSpanPt
                    val tilePageBottom = tilePageTop + tileSpanPt

                    tileDestRect.set(
                        offsetX + tilePageLeft * scale,
                        offsetY + tilePageTop * scale,
                        offsetX + tilePageRight * scale,
                        offsetY + tilePageBottom * scale
                    )
                    canvas.drawBitmap(cachedTile, null, tileDestRect, bitmapPaint)
                } else {
                    missingKeys.add(key)
                }
            }
        }

        if (missingKeys.isNotEmpty()) {
            scheduleTiles(missingKeys, tilePyramidScale)
        }
    }

    private fun scheduleTiles(keys: List<TileKey>, tilePyramidScale: Float) {
        val newKeys = keys.filter { tileCache.get(it) == null && !inFlightKeys.contains(it) }
        if (newKeys.isEmpty()) return

        inFlightKeys.addAll(newKeys)
        tileRenderJob?.cancel()
        tileRenderJob = scope.launch {
            val page = currentPage ?: return@launch
            val activePath = currentFilePath
            for (key in keys) {
                if (key.filePath != activePath) break
                if (tileCache.get(key) != null) {
                    inFlightKeys.remove(key)
                    continue
                }
                try {
                    val bmp = Bitmap.createBitmap(TILE_SIZE_PX, TILE_SIZE_PX, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)

                    val tileSpanPt = TILE_SIZE_PX / tilePyramidScale
                    val pageStartX = key.tileX * tileSpanPt
                    val pageStartY = key.tileY * tileSpanPt

                    val matrix = Matrix().apply {
                        setTranslate(-pageStartX, -pageStartY)
                        postScale(tilePyramidScale, tilePyramidScale)
                    }
                    page.render(bmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    tileCache.put(key, bmp)
                    inFlightKeys.remove(key)

                    CoroutineScope(Dispatchers.Main).launch {
                        onInvalidate()
                    }
                } catch (t: Throwable) {
                    inFlightKeys.remove(key)
                }
            }
        }
    }

    private fun closeInternal() {
        tileRenderJob?.cancel()
        inFlightKeys.clear()
        tileCache.evictAll()
        overviewBitmap = null
        try {
            currentPage?.close()
        } catch (_: Exception) {
        }
        currentPage = null
        try {
            pdfRenderer?.close()
        } catch (_: Exception) {
        }
        pdfRenderer = null
        try {
            fileDescriptor?.close()
        } catch (_: Exception) {
        }
        fileDescriptor = null
    }

    fun close() {
        scope.launch {
            closeInternal()
        }
    }

    suspend fun closeAndAwait() {
        withContext(pdfDispatcher) {
            closeInternal()
        }
    }
}
