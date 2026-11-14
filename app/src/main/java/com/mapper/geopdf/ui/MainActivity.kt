package com.mapper.geopdf.ui

import android.Manifest
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mapper.geopdf.R
import com.mapper.geopdf.catalog.ActiveDownload
import com.mapper.geopdf.catalog.CatalogMapEntry
import com.mapper.geopdf.databinding.ActivityMainBinding
import com.mapper.geopdf.geo.GeoPdfDocumentInfo
import com.mapper.geopdf.state.MapController
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mapController: MapController

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            mapController.locationTracker.startTracking()
        }
    }

    private val openPdfLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val fileName = queryFileName(uri)
            mapController.importPdfUri(uri, fileName)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        applyWindowInsets()

        mapController = MapController.getInstance(this)

        setupControls()
        observeState()
        checkAndRequestLocationPermissions()
    }

    private fun applyWindowInsets() {
        val topLp = binding.topControlBar.layoutParams as ViewGroup.MarginLayoutParams
        val baseTop = topLp.topMargin
        val baseTopLeft = topLp.leftMargin
        val baseTopRight = topLp.rightMargin

        val bottomLp = binding.bottomControls.layoutParams as ViewGroup.MarginLayoutParams
        val baseBottom = bottomLp.bottomMargin
        val baseBottomRight = bottomLp.rightMargin

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )

            (binding.topControlBar.layoutParams as ViewGroup.MarginLayoutParams).apply {
                topMargin = baseTop + bars.top
                leftMargin = baseTopLeft + bars.left
                rightMargin = baseTopRight + bars.right
            }
            binding.topControlBar.requestLayout()

            (binding.bottomControls.layoutParams as ViewGroup.MarginLayoutParams).apply {
                bottomMargin = baseBottom + bars.bottom
                rightMargin = baseBottomRight + bars.right
            }
            binding.bottomControls.requestLayout()

            binding.mapView.setOverlayInsets(leftPx = bars.left, bottomPx = bars.bottom)
            windowInsets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    override fun onStart() {
        super.onStart()
        if (hasLocationPermission()) {
            mapController.locationTracker.startTracking()
        }
    }

    private fun setupControls() {
        binding.btnSelectMap.setOnClickListener {
            showMapSelectionDialog()
        }

        binding.btnImportPdf.setOnClickListener {
            openPdfLauncher.launch(arrayOf("application/pdf"))
        }

        binding.btnSimulateGps.setOnClickListener {
            mapController.toggleGpsSimulation()
        }

        binding.btnSettings.setOnClickListener {
            showSettingsAndManageDialog()
        }

        binding.fabZoomIn.setOnClickListener {
            mapController.zoomBy(1.45f)
        }

        binding.fabZoomOut.setOnClickListener {
            mapController.zoomBy(1f / 1.45f)
        }

        binding.fabRecenter.setOnClickListener {
            mapController.recenterOnGps()
        }

        binding.btnCancelDownload.setOnClickListener {
            mapController.cancelActiveDownload()
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mapController.uiState.collect { state ->
                    val mapTitle = state.activeMap?.title ?: getString(R.string.no_map_loaded)
                    binding.btnSelectMap.text = mapTitle
                    binding.tvStatusBanner.text = state.statusMessage

                    val dl = state.activeDownload
                    if (dl != null) {
                        binding.layoutDownloadProgress.visibility = View.VISIBLE
                        val pct = dl.progressPercent
                        if (pct != null) {
                            binding.progressDownload.isIndeterminate = false
                            binding.progressDownload.progress = pct
                        } else {
                            binding.progressDownload.isIndeterminate = true
                        }
                    } else {
                        binding.layoutDownloadProgress.visibility = View.GONE
                    }

                    // Highlight Re-center button when actively keeping GPS position centered
                    binding.fabRecenter.isSelected = state.isFollowingGps
                    binding.fabRecenter.text = if (state.isFollowingGps) {
                        getString(R.string.following_gps)
                    } else {
                        getString(R.string.recenter_gps)
                    }

                    val isSim = mapController.locationTracker.isSimulating
                    binding.btnSimulateGps.text = if (isSim) {
                        getString(R.string.stop_sim_gps)
                    } else {
                        getString(R.string.sim_gps)
                    }
                }
            }
        }
    }

    private sealed class PickerRow {
        data class ActiveDownloadRow(val dl: ActiveDownload) : PickerRow()
        data class LocalMap(val info: GeoPdfDocumentInfo) : PickerRow()
        data class RemoteMap(val entry: CatalogMapEntry, val isAtGps: Boolean) : PickerRow()
    }

    private fun showMapSelectionDialog() {
        val state = mapController.uiState.value
        val gps = state.gpsPosition
        val activeDl = state.activeDownload
        val rows = mutableListOf<PickerRow>()
        val labels = mutableListOf<String>()
        var activeIdx = -1

        if (activeDl != null) {
            val pctText = activeDl.progressPercent?.let { " $it%" } ?: ""
            rows.add(PickerRow.ActiveDownloadRow(activeDl))
            labels.add("⏳ ${activeDl.mapName} (Downloading$pctText — tap to cancel)")
        }

        for (info in state.availableMaps) {
            val isActive = info.file.absolutePath == state.activeMap?.file?.absolutePath
            if (isActive) activeIdx = rows.size
            val inside = gps != null && info.primaryViewport.containsLatLon(gps.latitude, gps.longitude, marginPoints = 100f)
            val tags = buildList {
                if (inside) add("GPS Here")
                if (mapController.isMapPinned(info)) add("Pinned")
                else if (mapController.isCacheManaged(info)) add("Auto")
            }
            val suffix = if (tags.isNotEmpty()) "  [${tags.joinToString(", ")}]" else ""
            rows.add(PickerRow.LocalMap(info))
            labels.add("${info.title}$suffix")
        }

        for (entry in state.missingMatchingCatalogMaps) {
            if (entry.id == activeDl?.mapId) continue
            rows.add(PickerRow.RemoteMap(entry, isAtGps = true))
            labels.add("⬇ ${entry.mapName} (${entry.fileSizeMbText()})  [At GPS]")
        }

        for (entry in state.nearbyMissingCatalogMaps.take(6)) {
            if (entry.id == activeDl?.mapId) continue
            val distKm = gps?.let { entry.bbox.distanceKmTo(it.latitude, it.longitude).roundToInt() } ?: 0
            rows.add(PickerRow.RemoteMap(entry, isAtGps = false))
            labels.add("⬇ ${entry.mapName} (${entry.fileSizeMbText()})  [${distKm} km away]")
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.select_geopdf_map)
            .setPositiveButton("All USFS (${state.catalog.maps.size})") { _, _ ->
                showCatalogBrowserDialog()
            }
            .setNeutralButton(R.string.settings) { _, _ ->
                showSettingsAndManageDialog()
            }
            .setNegativeButton("Close", null)

        if (rows.isEmpty()) {
            builder.setMessage(
                "No local GeoPDF maps found yet.\n\n" +
                    "Tap 'All USFS' to download Forest Series maps, or 'Import PDF' in the top bar."
            )
        } else {
            builder.setSingleChoiceItems(labels.toTypedArray(), activeIdx) { dialog, which ->
                when (val picked = rows[which]) {
                    is PickerRow.ActiveDownloadRow -> {
                        mapController.cancelActiveDownload()
                        Toast.makeText(this, "Cancelled ${picked.dl.mapName} download", Toast.LENGTH_SHORT).show()
                    }
                    is PickerRow.LocalMap -> mapController.selectMap(picked.info)
                    is PickerRow.RemoteMap -> mapController.downloadCatalogMap(picked.entry, isAutomatic = false, pinned = true)
                }
                dialog.dismiss()
            }
        }

        builder.show()
    }

    private fun showCatalogBrowserDialog() {
        val state = mapController.uiState.value
        val catalogMaps = state.catalog.maps
        if (catalogMaps.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("USFS Forest Series Catalog")
                .setMessage("Catalog is empty or failed to load.")
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val listHeightPx = min(
            (resources.displayMetrics.heightPixels * 0.48).toInt(),
            (400 * density).toInt()
        )

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * density).toInt(), pad, 0)
        }
        val searchInput = EditText(this).apply {
            hint = "Search forest, state, or unit..."
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                listHeightPx
            ).apply {
                topMargin = (8 * density).toInt()
            }
        }
        container.addView(searchInput)
        container.addView(listView)

        val gps = state.gpsPosition
        val sortedCatalog = if (gps != null) {
            catalogMaps.sortedBy { it.bbox.distanceKmTo(gps.latitude, gps.longitude) }
        } else {
            catalogMaps
        }

        var filtered = sortedCatalog
        var hasMatches = true
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf())
        listView.adapter = adapter

        fun refreshList(query: String) {
            val q = query.trim().lowercase(Locale.US)
            filtered = if (q.isEmpty()) {
                sortedCatalog
            } else {
                sortedCatalog.filter {
                    it.mapName.lowercase(Locale.US).contains(q) ||
                        it.commonName.lowercase(Locale.US).contains(q) ||
                        (it.adminUnit?.lowercase(Locale.US)?.contains(q) == true) ||
                        (it.state?.lowercase(Locale.US)?.contains(q) == true) ||
                        (it.region?.lowercase(Locale.US)?.contains(q) == true)
                }
            }

            adapter.clear()
            if (filtered.isEmpty()) {
                hasMatches = false
                adapter.add("No USFS maps match '$query'")
            } else {
                hasMatches = true
                val curState = mapController.uiState.value
                val currentLocal = curState.availableMaps
                val activeDl = curState.activeDownload
                val items = filtered.map { entry ->
                    val downloaded = mapController.isLocalMapDownloaded(entry, currentLocal)
                    val isDownloadingEntry = (activeDl?.mapId == entry.id)
                    val stateTag = entry.state?.let { " • $it" } ?: ""
                    val distTag = if (gps != null) {
                        val d = entry.bbox.distanceKmTo(gps.latitude, gps.longitude).roundToInt()
                        if (d == 0 && entry.containsPoint(gps.latitude, gps.longitude)) " • At GPS" else " • ${d} km"
                    } else {
                        ""
                    }
                    when {
                        downloaded -> "✓ ${entry.mapName}$stateTag  [Downloaded]"
                        isDownloadingEntry -> {
                            val pctText = activeDl?.progressPercent?.let { " $it%" } ?: ""
                            "⏳ ${entry.mapName}$stateTag  [Downloading$pctText]"
                        }
                        else -> "⬇ ${entry.mapName}$stateTag$distTag  (${entry.fileSizeMbText()})"
                    }
                }
                adapter.addAll(items)
            }
            adapter.notifyDataSetChanged()
        }

        refreshList("")
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshList(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle("USFS Forest Series (${catalogMaps.size} maps)")
            .setView(container)
            .setNegativeButton("Close", null)
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            if (!hasMatches) return@setOnItemClickListener
            val picked = filtered.getOrNull(position) ?: return@setOnItemClickListener
            val curState = mapController.uiState.value
            if (curState.activeDownload?.mapId == picked.id) {
                mapController.cancelActiveDownload()
                Toast.makeText(this, "Cancelled ${picked.mapName} download", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                return@setOnItemClickListener
            }
            val targetName = mapController.downloadManager.targetFileFor(picked).name
            val existingLocal = curState.availableMaps.firstOrNull {
                it.file.name.equals(picked.fileName, ignoreCase = true) ||
                    it.file.name.equals(targetName, ignoreCase = true)
            }
            if (existingLocal != null) {
                mapController.selectMap(existingLocal)
            } else {
                mapController.downloadCatalogMap(picked, isAutomatic = false, pinned = true)
            }
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showSettingsAndManageDialog() {
        val state = mapController.uiState.value
        val stats = mapController.getAutoCacheStats()
        val options = arrayOf(
            "Auto-download map for current GPS",
            "Download on Wi-Fi only"
        )
        val checked = booleanArrayOf(
            state.autoDownloadEnabled,
            state.wifiOnlyDownload
        )

        val builder = AlertDialog.Builder(this)
            .setTitle("Auto-Download & Cache")
            .setMultiChoiceItems(options, checked) { _, which, isChecked ->
                when (which) {
                    0 -> mapController.setAutoDownloadEnabled(isChecked)
                    1 -> mapController.setWifiOnlyDownload(isChecked)
                }
            }
            .setNeutralButton(R.string.rescan) { _, _ ->
                mapController.scanMapsDirectory(force = true)
            }
            .setPositiveButton("Done", null)

        if (state.availableMaps.isNotEmpty() || stats.partialCount > 0) {
            builder.setNegativeButton("Manage Maps") { _, _ ->
                showManageLocalMapsDialog()
            }
        }
        builder.show()
    }

    private fun showManageLocalMapsDialog() {
        val maps = mapController.uiState.value.availableMaps
        val stats = mapController.getAutoCacheStats()
        val autoMb = (stats.autoCachedBytes / (1024.0 * 1024.0)).roundToInt()
        val maxMb = (stats.maxAutoBytes / (1024.0 * 1024.0)).roundToInt()
        val partialMb = (stats.partialBytes / (1024.0 * 1024.0)).roundToInt()

        val title = if (partialMb > 0) {
            "Maps (Cache ${autoMb}/${maxMb} MB, +${partialMb} MB partial)"
        } else {
            "Maps (Auto-cache ${autoMb}/${maxMb} MB)"
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setNegativeButton("Close", null)

        if (stats.autoCachedBytes + stats.partialBytes > 0L) {
            val clearMb = autoMb + partialMb
            builder.setNeutralButton("Clear Cache (${clearMb} MB)") { _, _ ->
                confirmClearCache(clearMb)
            }
        }

        if (maps.isEmpty()) {
            builder.setMessage("No completed local PDF maps.\nPartial downloads awaiting resume: ${partialMb} MB.")
            builder.show()
            return
        }

        val labels = maps.map { info ->
            val sizeMb = (info.file.length() / (1024.0 * 1024.0)).roundToInt().coerceAtLeast(1)
            val status = when {
                mapController.isMapPinned(info) -> "Pinned (keep offline)"
                mapController.isCacheManaged(info) -> "Auto-cached"
                else -> "Imported / Local file"
            }
            "${info.title} (${sizeMb} MB) • $status"
        }.toTypedArray()

        builder.setItems(labels) { _, which ->
            val info = maps[which]
            val pinned = mapController.isMapPinned(info)
            val actions = mutableListOf<String>()
            val actionIds = mutableListOf<String>()

            if (mapController.isCacheManaged(info)) {
                actions.add(if (pinned) "Unpin (allow auto-eviction)" else "Pin (keep offline)")
                actionIds.add("toggle_pin")
            }
            if (mapController.canDeleteMap(info)) {
                actions.add("Delete from app storage")
                actionIds.add("delete")
            }

            if (actions.isEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle(info.title)
                    .setMessage("This map is stored in an external folder outside app storage, so it is never auto-evicted and must be deleted from the Files app.")
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        showManageLocalMapsDialog()
                    }
                    .show()
                return@setItems
            }

            AlertDialog.Builder(this)
                .setTitle(info.title)
                .setItems(actions.toTypedArray()) { _, actionIdx ->
                    when (actionIds[actionIdx]) {
                        "toggle_pin" -> {
                            val nowPinned = mapController.toggleMapPinned(info)
                            val msg = if (nowPinned) {
                                "Pinned ${info.title} for offline use"
                            } else {
                                "Unpinned ${info.title} (eligible for auto-eviction)"
                            }
                            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                            showManageLocalMapsDialog()
                        }
                        "delete" -> confirmDeleteLocalMap(info)
                    }
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    showManageLocalMapsDialog()
                }
                .show()
        }

        builder.show()
    }

    private fun confirmDeleteLocalMap(info: GeoPdfDocumentInfo) {
        val sizeMb = (info.file.length() / (1024.0 * 1024.0)).roundToInt().coerceAtLeast(1)
        AlertDialog.Builder(this)
            .setTitle("Delete offline map?")
            .setMessage("Remove ${info.title} (${sizeMb} MB) from app storage? You can re-download it anytime.")
            .setPositiveButton("Delete") { _, _ ->
                mapController.deleteDownloadedMap(info)
                Toast.makeText(this, "Removed ${info.title}", Toast.LENGTH_SHORT).show()
                if (mapController.uiState.value.availableMaps.isNotEmpty() ||
                    mapController.getAutoCacheStats().partialCount > 0
                ) {
                    showManageLocalMapsDialog()
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                showManageLocalMapsDialog()
            }
            .show()
    }

    private fun confirmClearCache(clearMb: Int) {
        AlertDialog.Builder(this)
            .setTitle("Clear auto-cache (${clearMb} MB)?")
            .setMessage("Removes unpinned auto-cached maps and partial downloads. Pinned, currently open, and imported local maps are kept.")
            .setPositiveButton("Clear") { _, _ ->
                mapController.clearAutoAndPartialCache { removedCount ->
                    Toast.makeText(
                        this,
                        "Cleared cache ($removedCount cached map(s) removed)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                showManageLocalMapsDialog()
            }
            .show()
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkAndRequestLocationPermissions() {
        if (hasLocationPermission()) {
            mapController.locationTracker.startTracking()
        } else {
            val perms = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }.toTypedArray()
            locationPermissionLauncher.launch(perms)
        }
    }

    private fun queryFileName(uri: Uri): String? {
        var name: String? = null
        val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    name = it.getString(index)
                }
            }
        }
        return name
    }
}
