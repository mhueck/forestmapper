package com.mapper.geopdf.state

import android.content.Context
import android.graphics.Canvas
import android.net.Uri
import android.os.Environment
import com.mapper.geopdf.catalog.ActiveDownload
import com.mapper.geopdf.catalog.AutoCacheStats
import com.mapper.geopdf.catalog.CatalogMapEntry
import com.mapper.geopdf.catalog.DownloadOutcome
import com.mapper.geopdf.catalog.ForestCatalog
import com.mapper.geopdf.catalog.MapDownloadManager
import com.mapper.geopdf.catalog.MapDownloadService
import com.mapper.geopdf.catalog.NetworkEligibility
import com.mapper.geopdf.geo.GeoPdfDocumentInfo
import com.mapper.geopdf.geo.GeoPdfParser
import com.mapper.geopdf.location.GpsPosition
import com.mapper.geopdf.location.LocationTracker
import com.mapper.geopdf.pdf.TiledPdfEngine
import com.mapper.geopdf.render.MapOverlayRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.max
import kotlin.math.min

data class MapUiState(
    val availableMaps: List<GeoPdfDocumentInfo> = emptyList(),
    val activeMap: GeoPdfDocumentInfo? = null,
    val isLoading: Boolean = false,
    val statusMessage: String = "Scanning for GeoPDF maps...",
    val scale: Float = 1.0f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val isFollowingGps: Boolean = true,
    val gpsPosition: GpsPosition? = null,
    val isGpsOnActiveMap: Boolean = false,
    val catalog: ForestCatalog = ForestCatalog(1, "", emptyList()),
    val missingMatchingCatalogMaps: List<CatalogMapEntry> = emptyList(),
    val nearbyMissingCatalogMaps: List<CatalogMapEntry> = emptyList(),
    val activeDownload: ActiveDownload? = null,
    val autoDownloadEnabled: Boolean = true,
    val wifiOnlyDownload: Boolean = false
)

/**
 * Shared controller coordinating GeoPDF loading, tiled rendering, pan/zoom transforms,
 * GPS movement arrow tracking, and "Re-center / Follow GPS" mode across both the
 * Android phone UI and the Android Auto car screen.
 */
class MapController(private val appContext: Context) {

    companion object {
        const val MIN_SCALE = 0.15f
        const val MAX_SCALE = 20.0f

        @Volatile
        private var instance: MapController? = null

        fun getInstance(context: Context): MapController {
            return instance ?: synchronized(this) {
                instance ?: MapController(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val locationTracker = LocationTracker(appContext)
    val downloadManager: MapDownloadManager = MapDownloadManager(
        context = appContext,
        mapsDirProvider = ::getMapsStorageDir,
        activeFileProvider = { _uiState.value.activeMap?.file }
    )

    private val redrawListeners = CopyOnWriteArraySet<() -> Unit>()

    val pdfEngine = TiledPdfEngine {
        notifyRedraw()
    }

    private val _uiState: MutableStateFlow<MapUiState> = MutableStateFlow(
        MapUiState(
            autoDownloadEnabled = downloadManager.autoDownloadEnabled.value,
            wifiOnlyDownload = downloadManager.wifiOnlyDownload.value
        )
    )
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    // Last known viewport dimensions (updated by phone view or Android Auto surface)
    private var viewportWidthPx: Int = 1080
    private var viewportHeightPx: Int = 1920
    private var hasInitializedCameraForMap = false
    private var initialScanCompleted = false
    private var scanJob: Job? = null

    init {
        // Load bundled USFS Forest Series catalog
        scope.launch {
            val loadedCatalog = downloadManager.loadCatalog()
            val cur = _uiState.value
            val pos = cur.gpsPosition
            val available = cur.availableMaps
            val missingMatches = computeMissingMatchingCatalogMaps(pos, available, loadedCatalog)
            val nearbyMissing = computeNearbyMissingCatalogMaps(pos, available, loadedCatalog)
            val nextStatus = when {
                cur.activeDownload != null -> formatDownloadStatus(cur.activeDownload)
                initialScanCompleted && cur.activeMap == null && available.isEmpty() && missingMatches.isNotEmpty() ->
                    "Found ${missingMatches.first().mapName} for your GPS"
                else -> cur.statusMessage
            }
            _uiState.value = cur.copy(
                catalog = loadedCatalog,
                missingMatchingCatalogMaps = missingMatches,
                nearbyMissingCatalogMaps = nearbyMissing,
                statusMessage = nextStatus
            )
            if (_uiState.value.activeMap == null) {
                notifyRedraw()
            }
            if (pos != null && initialScanCompleted) {
                evaluateAutoDownloadForGps(pos)
            }
        }

        // Observe background download progress
        scope.launch {
            downloadManager.activeDownload.collect { dl ->
                val cur = _uiState.value
                val nextStatus = if (dl != null) formatDownloadStatus(dl) else cur.statusMessage
                _uiState.value = cur.copy(
                    activeDownload = dl,
                    statusMessage = nextStatus
                )
                if (cur.activeMap == null) {
                    notifyRedraw()
                }
            }
        }

        scope.launch {
            downloadManager.autoDownloadEnabled.collect { enabled ->
                _uiState.value = _uiState.value.copy(autoDownloadEnabled = enabled)
            }
        }

        scope.launch {
            downloadManager.wifiOnlyDownload.collect { wifiOnly ->
                _uiState.value = _uiState.value.copy(wifiOnlyDownload = wifiOnly)
            }
        }

        // Observe live GPS updates and keep map centered when isFollowingGps is active
        scope.launch {
            locationTracker.gpsPosition.collect { pos ->
                var current = _uiState.value
                var map = current.activeMap

                // If following real GPS and outside current map, switch to a matching local map if loaded
                if (pos != null && !pos.isSimulated && current.isFollowingGps && initialScanCompleted) {
                    val onCurrentStrict = map != null &&
                        map.primaryViewport.containsLatLon(pos.latitude, pos.longitude, marginPoints = 0f)
                    if (!onCurrentStrict) {
                        val betterLocal = findBestLocalMapForLocation(pos.latitude, pos.longitude, current.availableMaps)
                        if (betterLocal != null && betterLocal.file.absolutePath != map?.file?.absolutePath) {
                            selectMap(betterLocal)
                            current = _uiState.value
                            map = current.activeMap
                        }
                    }
                }

                val onMap = if (pos != null && map != null) {
                    map.primaryViewport.containsLatLon(pos.latitude, pos.longitude, marginPoints = 150f)
                } else {
                    false
                }

                val missingMatches = computeMissingMatchingCatalogMaps(pos, current.availableMaps, current.catalog)
                val nearbyMissing = computeNearbyMissingCatalogMaps(pos, current.availableMaps, current.catalog)

                _uiState.value = current.copy(
                    gpsPosition = pos,
                    isGpsOnActiveMap = onMap,
                    missingMatchingCatalogMaps = missingMatches,
                    nearbyMissingCatalogMaps = nearbyMissing
                )

                if (pos != null && current.isFollowingGps && map != null) {
                    centerMapOnLatLonInternal(pos.latitude, pos.longitude, keepFollow = true)
                } else {
                    notifyRedraw()
                }

                if (pos != null && initialScanCompleted) {
                    evaluateAutoDownloadForGps(pos)
                }
            }
        }

        // Initial scan for GeoPDF files
        scanMapsDirectory()
    }

    fun addRedrawListener(listener: () -> Unit) {
        redrawListeners.add(listener)
    }

    fun removeRedrawListener(listener: () -> Unit) {
        redrawListeners.remove(listener)
    }

    private fun notifyRedraw() {
        for (listener in redrawListeners) {
            listener.invoke()
        }
    }

    fun getMapsStorageDir(): File {
        val dir = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "maps")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Scans known directories on the device for GeoPDF files:
     *  1. App-specific external folder: /Android/data/com.mapper.geopdf/files/maps
     *  2. Internal files/maps
     *  3. Public /sdcard/Maps, /sdcard/Download/maps, /sdcard/Download, /sdcard/Documents/maps
     */
    fun scanMapsDirectory(force: Boolean = true) {
        if (!force && (initialScanCompleted || scanJob?.isActive == true)) return
        scanJob?.cancel()
        scanJob = scope.launch {
            val curBefore = _uiState.value
            val scanningStatus = when {
                curBefore.activeDownload != null -> formatDownloadStatus(curBefore.activeDownload)
                curBefore.activeMap != null -> curBefore.activeMap.title
                else -> "Scanning for GeoPDF maps..."
            }
            _uiState.value = curBefore.copy(
                isLoading = curBefore.activeMap == null,
                statusMessage = scanningStatus
            )
            if (curBefore.activeMap == null) {
                notifyRedraw()
            }
            val discovered = withContext(Dispatchers.IO) {
                val candidateDirs = mutableListOf<File>()
                candidateDirs.add(getMapsStorageDir())
                candidateDirs.add(File(appContext.filesDir, "maps"))

                try {
                    val extRoot = Environment.getExternalStorageDirectory()
                    candidateDirs.add(File(extRoot, "maps"))
                    candidateDirs.add(File(extRoot, "Maps"))
                    candidateDirs.add(File(extRoot, "Download/maps"))
                    candidateDirs.add(File(extRoot, "Download"))
                    candidateDirs.add(File(extRoot, "Documents/maps"))
                } catch (_: Exception) {
                }

                val pdfFiles = LinkedHashMap<String, File>()
                for (dir in candidateDirs) {
                    if (dir.exists() && dir.isDirectory) {
                        dir.listFiles { f -> f.isFile && f.extension.equals("pdf", ignoreCase = true) }
                            ?.forEach { file ->
                                pdfFiles.putIfAbsent(file.name.lowercase(), file)
                            }
                    }
                }

                // Also extract any bundled assets/maps/*.pdf if present
                try {
                    val assetNames = appContext.assets.list("maps").orEmpty()
                    for (name in assetNames) {
                        if (name.endsWith(".pdf", ignoreCase = true)) {
                            val dest = File(getMapsStorageDir(), name)
                            if (!dest.exists()) {
                                appContext.assets.open("maps/$name").use { input ->
                                    FileOutputStream(dest).use { output ->
                                        input.copyTo(output)
                                    }
                                }
                            }
                            pdfFiles.putIfAbsent(dest.name.lowercase(), dest)
                        }
                    }
                } catch (_: Exception) {
                }

                pdfFiles.values.mapNotNull { file ->
                    try {
                        GeoPdfParser.parse(file)
                    } catch (e: Exception) {
                        null
                    }
                }.sortedBy { it.title }
            }

            initialScanCompleted = true
            val gps = _uiState.value.gpsPosition
            val currentActive = _uiState.value.activeMap
            val nextActive = currentActive
                ?: (if (gps != null) findBestLocalMapForLocation(gps.latitude, gps.longitude, discovered) else null)
                ?: discovered.firstOrNull()

            val catalog = _uiState.value.catalog
            val missingMatches = computeMissingMatchingCatalogMaps(gps, discovered, catalog)
            val nearbyMissing = computeNearbyMissingCatalogMaps(gps, discovered, catalog)

            val activeDl = _uiState.value.activeDownload
            _uiState.value = _uiState.value.copy(
                availableMaps = discovered,
                isLoading = false,
                missingMatchingCatalogMaps = missingMatches,
                nearbyMissingCatalogMaps = nearbyMissing,
                statusMessage = when {
                    activeDl != null -> formatDownloadStatus(activeDl)
                    currentActive != null -> currentActive.title
                    discovered.isEmpty() && missingMatches.isNotEmpty() ->
                        "Found ${missingMatches.first().mapName} for your GPS"
                    discovered.isEmpty() ->
                        "Tap 'Select GeoPDF Map' to browse or download USFS maps"
                    else ->
                        "Loaded ${discovered.size} GeoPDF map(s)"
                }
            )

            if (nextActive != null && nextActive.file.absolutePath != currentActive?.file?.absolutePath) {
                selectMap(nextActive)
            } else if (_uiState.value.activeMap == null) {
                notifyRedraw()
            }
            if (gps != null) {
                evaluateAutoDownloadForGps(gps)
            }
        }
    }

    /**
     * Imports a GeoPDF selected via the Android system document picker (SAF) into the app's
     * maps directory so it can be accessed by both the phone UI and Android Auto.
     */
    fun importPdfUri(uri: Uri, displayNameHint: String?) {
        scope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                statusMessage = "Importing GeoPDF map..."
            )
            val parsedInfo = withContext(Dispatchers.IO) {
                try {
                    val safeName = (displayNameHint ?: "map_${System.currentTimeMillis()}.pdf")
                        .replace(Regex("""[^\w.\- ]"""), "_")
                        .let { if (it.endsWith(".pdf", ignoreCase = true)) it else "$it.pdf" }
                    val targetFile = File(getMapsStorageDir(), safeName)
                    appContext.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output, bufferSize = 256 * 1024)
                        }
                    }
                    GeoPdfParser.parse(targetFile)
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }

            if (parsedInfo != null) {
                val updatedList = (_uiState.value.availableMaps
                    .filterNot { it.file.absolutePath == parsedInfo.file.absolutePath } + parsedInfo)
                    .sortedBy { it.title }
                val gps = _uiState.value.gpsPosition
                val catalog = _uiState.value.catalog
                _uiState.value = _uiState.value.copy(
                    availableMaps = updatedList,
                    isLoading = false,
                    missingMatchingCatalogMaps = computeMissingMatchingCatalogMaps(gps, updatedList, catalog),
                    nearbyMissingCatalogMaps = computeNearbyMissingCatalogMaps(gps, updatedList, catalog),
                    statusMessage = "Loaded ${parsedInfo.title}"
                )
                selectMap(parsedInfo)
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    statusMessage = "Selected PDF does not contain GeoPDF calibration metadata (/VP or /GPTS)."
                )
                if (_uiState.value.activeMap == null) {
                    notifyRedraw()
                }
            }
        }
    }

    fun selectMap(mapInfo: GeoPdfDocumentInfo) {
        hasInitializedCameraForMap = false
        downloadManager.markMapUsed(mapInfo.file)
        val gps = _uiState.value.gpsPosition
        val onMap = gps != null && mapInfo.primaryViewport.containsLatLon(gps.latitude, gps.longitude, marginPoints = 150f)
        _uiState.value = _uiState.value.copy(
            activeMap = mapInfo,
            isLoading = true,
            isGpsOnActiveMap = onMap,
            statusMessage = "Opening ${mapInfo.title}..."
        )
        pdfEngine.openPdf(mapInfo.file) { widthPt, heightPt ->
            val dl = _uiState.value.activeDownload
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                statusMessage = if (dl != null) formatDownloadStatus(dl) else mapInfo.title
            )
            if (!hasInitializedCameraForMap) {
                fitOrCenterInitialView(mapInfo)
                hasInitializedCameraForMap = true
            }
            notifyRedraw()
        }
    }

    fun updateViewportSize(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        val changed = (widthPx != viewportWidthPx || heightPx != viewportHeightPx)
        viewportWidthPx = widthPx
        viewportHeightPx = heightPx
        val map = _uiState.value.activeMap
        if (map != null && !hasInitializedCameraForMap) {
            fitOrCenterInitialView(map)
            hasInitializedCameraForMap = true
        } else if (changed && _uiState.value.isFollowingGps) {
            val pos = _uiState.value.gpsPosition
            if (pos != null) {
                centerMapOnLatLonInternal(pos.latitude, pos.longitude, keepFollow = true)
            }
        }
    }

    private fun fitOrCenterInitialView(map: GeoPdfDocumentInfo) {
        val fitScaleX = viewportWidthPx.toFloat() / map.pageWidthPt.coerceAtLeast(1f)
        val fitScaleY = viewportHeightPx.toFloat() / map.pageHeightPt.coerceAtLeast(1f)
        val baseFitScale = min(fitScaleX, fitScaleY).coerceIn(MIN_SCALE, MAX_SCALE)

        val gps = _uiState.value.gpsPosition
        if (gps != null && map.primaryViewport.containsLatLon(gps.latitude, gps.longitude, marginPoints = 150f)) {
            val initialScale = max(baseFitScale * 2.2f, 1.0f).coerceIn(MIN_SCALE, MAX_SCALE)
            _uiState.value = _uiState.value.copy(scale = initialScale, isFollowingGps = true)
            centerMapOnLatLonInternal(gps.latitude, gps.longitude, keepFollow = true)
        } else {
            val centeredX = (viewportWidthPx - map.pageWidthPt * baseFitScale) * 0.5f
            val centeredY = (viewportHeightPx - map.pageHeightPt * baseFitScale) * 0.5f
            _uiState.value = _uiState.value.copy(
                scale = baseFitScale,
                offsetX = centeredX,
                offsetY = centeredY
            )
            notifyRedraw()
        }
    }

    /**
     * Re-centers the map on the user's current local GPS position and enables continuous
     * GPS follow mode so the map moves automatically as the user moves.
     *
     * If the current GPS position is outside the active map's bounds, checks if another loaded
     * map covers that GPS position and switches to it automatically.
     */
    fun recenterOnGps() {
        val gps = _uiState.value.gpsPosition
        val active = _uiState.value.activeMap

        if (gps == null) {
            _uiState.value = _uiState.value.copy(
                isFollowingGps = true,
                statusMessage = "Waiting for GPS fix (or tap 'Sim GPS' to test on map)..."
            )
            if (active != null) {
                fitOrCenterInitialView(active)
            }
            return
        }

        // Check if current map contains GPS; if not, check other loaded maps or download from catalog
        if (active == null || !active.primaryViewport.containsLatLon(gps.latitude, gps.longitude, marginPoints = 150f)) {
            val matchingLocal = findBestLocalMapForLocation(gps.latitude, gps.longitude, _uiState.value.availableMaps)
            if (matchingLocal != null && matchingLocal.file.absolutePath != active?.file?.absolutePath) {
                selectMap(matchingLocal)
                return
            }

            val missingCatalog = _uiState.value.missingMatchingCatalogMaps.firstOrNull()
            if (missingCatalog != null) {
                if (downloadManager.isDownloading(missingCatalog.id)) {
                    _uiState.value = _uiState.value.copy(isFollowingGps = true)
                    return
                }
                val activeDl = _uiState.value.activeDownload
                if (activeDl == null || activeDl.isAutomatic) {
                    downloadCatalogMap(missingCatalog, isAutomatic = false)
                    return
                }
            }
        }

        // Ensure a comfortable readable zoom level when locking onto GPS
        val currentScale = _uiState.value.scale
        val targetScale = if (currentScale < 0.75f) 1.5f else currentScale
        val activeDl = _uiState.value.activeDownload
        val nextStatus = when {
            activeDl != null -> formatDownloadStatus(activeDl)
            active != null -> active.title
            else -> "No USFS map covers this GPS location"
        }
        _uiState.value = _uiState.value.copy(
            scale = targetScale,
            isFollowingGps = true,
            statusMessage = nextStatus
        )
        if (active != null) {
            centerMapOnLatLonInternal(gps.latitude, gps.longitude, keepFollow = true)
        } else {
            notifyRedraw()
        }
    }

    private fun centerMapOnLatLonInternal(lat: Double, lon: Double, keepFollow: Boolean) {
        val map = _uiState.value.activeMap ?: return
        val pagePt = map.primaryViewport.latLonToPage(lat, lon)
        val scale = _uiState.value.scale
        val newOffsetX = viewportWidthPx * 0.5f - pagePt.x * scale
        val newOffsetY = viewportHeightPx * 0.5f - pagePt.y * scale

        _uiState.value = _uiState.value.copy(
            offsetX = newOffsetX,
            offsetY = newOffsetY,
            isFollowingGps = keepFollow,
            isGpsOnActiveMap = map.primaryViewport.containsLatLon(lat, lon, marginPoints = 150f)
        )
        notifyRedraw()
    }

    /**
     * Pans the map by (dx, dy) screen pixels. User-initiated panning automatically disengages
     * continuous GPS centering until the user taps the Re-center button.
     */
    fun panBy(dx: Float, dy: Float, userInitiated: Boolean = true) {
        val cur = _uiState.value
        _uiState.value = cur.copy(
            offsetX = cur.offsetX + dx,
            offsetY = cur.offsetY + dy,
            isFollowingGps = if (userInitiated) false else cur.isFollowingGps
        )
        notifyRedraw()
    }

    /**
     * Zooms the map by [zoomFactor] around screen pivot ([pivotX], [pivotY]).
     * If [isFollowingGps] is enabled and a GPS fix is on the map, zooming keeps the local GPS
     * position locked at the center of the screen.
     */
    fun zoomBy(zoomFactor: Float, pivotX: Float = viewportWidthPx * 0.5f, pivotY: Float = viewportHeightPx * 0.5f) {
        val cur = _uiState.value
        val oldScale = cur.scale
        val newScale = (oldScale * zoomFactor).coerceIn(MIN_SCALE, MAX_SCALE)
        val actualFactor = newScale / oldScale

        val gps = cur.gpsPosition
        val map = cur.activeMap
        if (cur.isFollowingGps && gps != null && map != null && cur.isGpsOnActiveMap) {
            val pagePt = map.primaryViewport.latLonToPage(gps.latitude, gps.longitude)
            val newOffsetX = viewportWidthPx * 0.5f - pagePt.x * newScale
            val newOffsetY = viewportHeightPx * 0.5f - pagePt.y * newScale
            _uiState.value = cur.copy(
                scale = newScale,
                offsetX = newOffsetX,
                offsetY = newOffsetY
            )
        } else {
            val newOffsetX = pivotX - (pivotX - cur.offsetX) * actualFactor
            val newOffsetY = pivotY - (pivotY - cur.offsetY) * actualFactor
            _uiState.value = cur.copy(
                scale = newScale,
                offsetX = newOffsetX,
                offsetY = newOffsetY
            )
        }
        notifyRedraw()
    }

    /**
     * Toggles simulated GPS movement inside the currently loaded map so the user can test
     * the directional movement arrow and Re-center follow behavior anywhere.
     */
    fun toggleGpsSimulation() {
        val map = _uiState.value.activeMap
        val vp = map?.primaryViewport
        val startLat = vp?.centerLat ?: 34.5400
        val startLon = vp?.centerLon ?: -112.4685
        locationTracker.toggleSimulation(startLat, startLon)
        if (locationTracker.isSimulating) {
            _uiState.value = _uiState.value.copy(isFollowingGps = true)
        }
    }

    /**
     * Draws both the tiled GeoPDF map and the GPS position/direction arrow overlay onto [canvas].
     * Used by both the phone's MapView and the Android Auto SurfaceCallback.
     */
    fun renderToCanvas(
        canvas: Canvas,
        width: Int,
        height: Int,
        overlayLeftInsetPx: Int = 0,
        overlayBottomInsetPx: Int = 0
    ) {
        updateViewportSize(width, height)
        val state = _uiState.value

        // Background
        canvas.drawColor(0xFF1B2228.toInt())

        val activeMap = state.activeMap
        if (activeMap != null) {
            pdfEngine.draw(
                canvas = canvas,
                viewportWidth = width,
                viewportHeight = height,
                scale = state.scale,
                offsetX = state.offsetX,
                offsetY = state.offsetY
            )

            // Draw GPS position marker + movement direction arrow + scale bar
            MapOverlayRenderer.drawOverlays(
                canvas = canvas,
                viewportWidth = width,
                viewportHeight = height,
                mapInfo = activeMap,
                state = state,
                extraLeftInsetPx = overlayLeftInsetPx,
                extraBottomInsetPx = overlayBottomInsetPx
            )
        } else {
            MapOverlayRenderer.drawEmptyState(
                canvas = canvas,
                viewportWidth = width,
                viewportHeight = height,
                message = state.statusMessage
            )
        }
    }

    fun isLocalMapDownloaded(
        entry: CatalogMapEntry,
        localMaps: List<GeoPdfDocumentInfo> = _uiState.value.availableMaps
    ): Boolean {
        val targetName = downloadManager.targetFileFor(entry).name
        return localMaps.any {
            it.file.name.equals(entry.fileName, ignoreCase = true) ||
                it.file.name.equals(targetName, ignoreCase = true)
        }
    }

    fun setAutoDownloadEnabled(enabled: Boolean) {
        downloadManager.setAutoDownloadEnabled(enabled)
        val pos = _uiState.value.gpsPosition
        if (enabled && pos != null && initialScanCompleted) {
            evaluateAutoDownloadForGps(pos)
        }
    }

    fun setWifiOnlyDownload(wifiOnly: Boolean) {
        downloadManager.setWifiOnlyDownload(wifiOnly)
        val pos = _uiState.value.gpsPosition
        if (!wifiOnly && pos != null && initialScanCompleted) {
            evaluateAutoDownloadForGps(pos)
        }
    }

    fun cancelActiveDownload() {
        downloadManager.cancelActiveDownload()
        val cur = _uiState.value
        _uiState.value = cur.copy(
            statusMessage = cur.activeMap?.title ?: "Download cancelled"
        )
        if (cur.activeMap == null) {
            notifyRedraw()
        }
    }

    fun downloadCatalogMap(
        entry: CatalogMapEntry,
        isAutomatic: Boolean = false,
        pinned: Boolean = !isAutomatic
    ) {
        val network = downloadManager.checkNetworkEligibility()
        if (network != NetworkEligibility.ALLOWED) {
            val msg = when (network) {
                NetworkEligibility.WIFI_REQUIRED ->
                    "Need ${entry.mapName} (${entry.fileSizeMbText()}) — waiting for Wi-Fi"
                NetworkEligibility.NO_INTERNET ->
                    "Need ${entry.mapName} (${entry.fileSizeMbText()}) — offline"
                NetworkEligibility.ALLOWED -> ""
            }
            val cur = _uiState.value
            val onCurrent = cur.gpsPosition?.let { pos ->
                cur.activeMap?.primaryViewport?.containsLatLon(pos.latitude, pos.longitude, marginPoints = 150f)
            } ?: false
            if (!isAutomatic || !onCurrent) {
                _uiState.value = cur.copy(statusMessage = msg)
                if (cur.activeMap == null) {
                    notifyRedraw()
                }
            }
            return
        }

        downloadManager.startDownload(entry, isAutomatic = isAutomatic, pinned = pinned) { outcome ->
            when (outcome) {
                is DownloadOutcome.Success -> {
                    val evictedPaths = outcome.evictedFiles.map { it.absolutePath }.toSet()
                    val updatedList = (_uiState.value.availableMaps
                        .filterNot {
                            it.file.absolutePath == outcome.parsedInfo.file.absolutePath ||
                                it.file.absolutePath in evictedPaths
                        } + outcome.parsedInfo)
                        .sortedBy { it.title }

                    val gps = _uiState.value.gpsPosition
                    val catalog = _uiState.value.catalog
                    _uiState.value = _uiState.value.copy(
                        availableMaps = updatedList,
                        missingMatchingCatalogMaps = computeMissingMatchingCatalogMaps(gps, updatedList, catalog),
                        nearbyMissingCatalogMaps = computeNearbyMissingCatalogMaps(gps, updatedList, catalog),
                        statusMessage = "Downloaded ${outcome.parsedInfo.title}"
                    )

                    val currentActive = _uiState.value.activeMap
                    val gpsInsideNew = gps != null &&
                        outcome.parsedInfo.primaryViewport.containsLatLon(gps.latitude, gps.longitude, marginPoints = 150f)
                    val gpsOnCurrent = gps != null && currentActive != null &&
                        currentActive.primaryViewport.containsLatLon(gps.latitude, gps.longitude, marginPoints = 150f)

                    if (!isAutomatic || currentActive == null || (gpsInsideNew && !gpsOnCurrent && _uiState.value.isFollowingGps)) {
                        selectMap(outcome.parsedInfo)
                    }
                }
                is DownloadOutcome.Failed -> {
                    val cur = _uiState.value
                    _uiState.value = cur.copy(
                        statusMessage = "Download failed (${outcome.entry.mapName}): ${outcome.reason}"
                    )
                    if (cur.activeMap == null) {
                        notifyRedraw()
                    }
                }
                is DownloadOutcome.Cancelled -> {
                    val cur = _uiState.value
                    _uiState.value = cur.copy(
                        statusMessage = cur.activeMap?.title ?: "Download cancelled"
                    )
                    if (cur.activeMap == null) {
                        notifyRedraw()
                    }
                }
            }
        }
        if (downloadManager.isDownloading()) {
            MapDownloadService.startSafely(appContext)
        }
    }

    fun isMapPinned(mapInfo: GeoPdfDocumentInfo): Boolean = downloadManager.isMapPinned(mapInfo.file)

    fun isAutoDownloaded(mapInfo: GeoPdfDocumentInfo): Boolean = downloadManager.isAutoDownloaded(mapInfo.file)

    fun isCacheManaged(mapInfo: GeoPdfDocumentInfo): Boolean = downloadManager.isCacheManaged(mapInfo.file)

    fun getAutoCacheStats(): AutoCacheStats = downloadManager.getAutoCacheStats()

    fun clearAutoAndPartialCache(onComplete: (Int) -> Unit = {}) {
        scope.launch {
            val deleted = downloadManager.clearAutoAndPartialCache()
            val deletedPaths = deleted.map { it.absolutePath }.toSet()
            if (deletedPaths.isNotEmpty()) {
                val cur = _uiState.value
                val remaining = cur.availableMaps.filterNot { it.file.absolutePath in deletedPaths }
                val gps = cur.gpsPosition
                val catalog = cur.catalog
                _uiState.value = cur.copy(
                    availableMaps = remaining,
                    missingMatchingCatalogMaps = computeMissingMatchingCatalogMaps(gps, remaining, catalog),
                    nearbyMissingCatalogMaps = computeNearbyMissingCatalogMaps(gps, remaining, catalog)
                )
            }
            onComplete(deleted.size)
        }
    }

    fun toggleMapPinned(mapInfo: GeoPdfDocumentInfo): Boolean {
        val newPinned = !downloadManager.isMapPinned(mapInfo.file)
        downloadManager.setMapPinned(mapInfo.file, newPinned)
        return newPinned
    }

    fun canDeleteMap(mapInfo: GeoPdfDocumentInfo): Boolean {
        val mapsDirPath = getMapsStorageDir().absolutePath
        return mapInfo.file.parentFile?.absolutePath == mapsDirPath
    }

    fun deleteDownloadedMap(mapInfo: GeoPdfDocumentInfo) {
        if (!canDeleteMap(mapInfo)) return
        val cur = _uiState.value
        val wasActive = cur.activeMap?.file?.absolutePath == mapInfo.file.absolutePath
        val remaining = cur.availableMaps.filterNot {
            it.file.absolutePath == mapInfo.file.absolutePath
        }

        val gps = cur.gpsPosition
        val catalog = cur.catalog
        val nextActive = if (wasActive) {
            (if (gps != null) findBestLocalMapForLocation(gps.latitude, gps.longitude, remaining) else null)
                ?: remaining.firstOrNull()
        } else {
            cur.activeMap
        }

        _uiState.value = cur.copy(
            availableMaps = remaining,
            activeMap = if (wasActive) null else nextActive,
            missingMatchingCatalogMaps = computeMissingMatchingCatalogMaps(gps, remaining, catalog),
            nearbyMissingCatalogMaps = computeNearbyMissingCatalogMaps(gps, remaining, catalog),
            statusMessage = when {
                cur.activeDownload != null -> formatDownloadStatus(cur.activeDownload)
                remaining.isEmpty() -> "Tap 'Select Map...' to browse or download USFS maps"
                else -> "Removed ${mapInfo.title}"
            }
        )
        if (wasActive && nextActive == null) {
            notifyRedraw()
        }

        scope.launch {
            if (wasActive) {
                pdfEngine.closeAndAwait()
            }
            withContext(Dispatchers.IO) {
                mapInfo.file.delete()
                downloadManager.clearSidecarsFor(mapInfo.file)
            }
            downloadManager.clearAutoMetadata(mapInfo.file)
            if (wasActive && nextActive != null) {
                selectMap(nextActive)
            }
        }
    }

    private fun evaluateAutoDownloadForGps(pos: GpsPosition) {
        if (pos.isSimulated) return
        if (!_uiState.value.autoDownloadEnabled) return
        if (downloadManager.isDownloading()) return

        val available = _uiState.value.availableMaps
        // If any locally loaded map already covers current GPS, keep using it without downloading
        val localCover = findBestLocalMapForLocation(pos.latitude, pos.longitude, available, marginPoints = 0f)
        if (localCover != null) return

        val missing = _uiState.value.missingMatchingCatalogMaps.firstOrNull() ?: return
        if (downloadManager.isInAutoCooldown(missing.id)) return

        downloadCatalogMap(missing, isAutomatic = true, pinned = false)
    }

    private fun findBestLocalMapForLocation(
        lat: Double,
        lon: Double,
        maps: List<GeoPdfDocumentInfo>,
        marginPoints: Float = 50f
    ): GeoPdfDocumentInfo? {
        val exact = maps.filter { it.primaryViewport.containsLatLon(lat, lon, marginPoints = 0f) }
        val candidates = if (exact.isNotEmpty() || marginPoints <= 0f) {
            exact
        } else {
            maps.filter { it.primaryViewport.containsLatLon(lat, lon, marginPoints = marginPoints) }
        }
        return candidates.minByOrNull { it.primaryViewport.metersPerPoint }
    }

    private fun computeMissingMatchingCatalogMaps(
        gps: GpsPosition?,
        available: List<GeoPdfDocumentInfo>,
        catalog: ForestCatalog
    ): List<CatalogMapEntry> {
        if (gps == null || catalog.maps.isEmpty()) return emptyList()
        return catalog.findMatchingMaps(gps.latitude, gps.longitude)
            .filterNot { isLocalMapDownloaded(it, available) }
    }

    private fun computeNearbyMissingCatalogMaps(
        gps: GpsPosition?,
        available: List<GeoPdfDocumentInfo>,
        catalog: ForestCatalog
    ): List<CatalogMapEntry> {
        if (gps == null || catalog.maps.isEmpty()) return emptyList()
        val exactIds = catalog.findMatchingMaps(gps.latitude, gps.longitude).map { it.id }.toSet()
        return catalog.findNearbyMaps(gps.latitude, gps.longitude, radiusKm = 60.0)
            .filterNot { it.id in exactIds || isLocalMapDownloaded(it, available) }
    }

    private fun formatDownloadStatus(dl: ActiveDownload): String {
        val dlMb = dl.bytesDownloaded / (1024.0 * 1024.0)
        val totalMb = dl.totalBytes?.let { it / (1024.0 * 1024.0) }
        val pct = dl.progressPercent
        val prefix = if (dl.isAutomatic) "Auto-downloading" else "Downloading"
        return if (pct != null && totalMb != null) {
            String.format(java.util.Locale.US, "%s %s: %d%% (%.1f / %.1f MB)", prefix, dl.mapName, pct, dlMb, totalMb)
        } else {
            String.format(java.util.Locale.US, "%s %s: %.1f MB", prefix, dl.mapName, dlMb)
        }
    }
}
