package com.mapper.geopdf.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mapper.geopdf.state.MapController
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Android Auto screen allowing the user to select which GeoPDF map to display on the head unit
 * or toggle the simulated GPS drive mode for testing.
 */
class MapPickerCarScreen(carContext: CarContext) : Screen(carContext) {

    private val mapController = MapController.getInstance(carContext)
    private var lastPickerKey: String = ""

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                owner.lifecycleScope.launch {
                    owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        mapController.uiState.collect { state ->
                            val dl = state.activeDownload
                            val pctBucket = dl?.progressPercent?.let { (it / 10) * 10 }
                            val key = "${dl?.mapId}|$pctBucket|${state.availableMaps.size}|${state.activeMap?.file?.name}|${state.missingMatchingCatalogMaps.firstOrNull()?.id}"
                            if (lastPickerKey.isNotEmpty() && key != lastPickerKey) {
                                invalidate()
                            }
                            lastPickerKey = key
                        }
                    }
                }
            }
        })
    }

    override fun onGetTemplate(): Template {
        val state = mapController.uiState.value
        val itemListBuilder = ItemList.Builder()

        val gps = state.gpsPosition
        val activeDl = state.activeDownload
        var addedRows = 0
        val maxMapRows = 5

        if (activeDl != null) {
            val pctText = activeDl.progressPercent?.let { " $it%" } ?: ""
            itemListBuilder.addItem(
                Row.Builder()
                    .setTitle("Downloading$pctText: ${activeDl.mapName}")
                    .addText("Tap to cancel active download")
                    .setOnClickListener {
                        mapController.cancelActiveDownload()
                        screenManager.pop()
                    }
                    .build()
            )
            addedRows++
        }

        // Surface matching catalog downloads first if no local map covers GPS yet
        for (entry in state.missingMatchingCatalogMaps) {
            if (addedRows >= maxMapRows) break
            if (entry.id == activeDl?.mapId) continue
            itemListBuilder.addItem(
                Row.Builder()
                    .setTitle("Download: ${entry.mapName}")
                    .addText("At current GPS • ${entry.fileSizeMbText()}")
                    .setOnClickListener {
                        mapController.downloadCatalogMap(entry, isAutomatic = false, pinned = true)
                        screenManager.pop()
                    }
                    .build()
            )
            addedRows++
        }

        // Prioritize local maps covering current GPS first
        val sortedLocal = state.availableMaps.sortedByDescending { info ->
            val active = info.file.absolutePath == state.activeMap?.file?.absolutePath
            val atGps = gps != null && info.primaryViewport.containsLatLon(gps.latitude, gps.longitude, marginPoints = 100f)
            (if (atGps) 2 else 0) + (if (active) 1 else 0)
        }

        for (mapInfo in sortedLocal) {
            if (addedRows >= maxMapRows) break
            val vp = mapInfo.primaryViewport
            val isActive = mapInfo.file.absolutePath == state.activeMap?.file?.absolutePath
            val containsGps = gps != null && vp.containsLatLon(gps.latitude, gps.longitude, marginPoints = 100f)

            val subtitle = buildString {
                if (isActive) append("[ACTIVE] ")
                if (containsGps) append("[GPS INSIDE MAP] ")
                append(
                    String.format(
                        Locale.US,
                        "%.2f°N..%.2f°N, %.2f°W..%.2f°W",
                        vp.minLat,
                        vp.maxLat,
                        kotlin.math.abs(vp.maxLon),
                        kotlin.math.abs(vp.minLon)
                    )
                )
            }

            itemListBuilder.addItem(
                Row.Builder()
                    .setTitle(mapInfo.title)
                    .addText(subtitle)
                    .setOnClickListener {
                        mapController.selectMap(mapInfo)
                        screenManager.pop()
                    }
                    .build()
            )
            addedRows++
        }

        // Fill remaining space with nearby catalog maps
        for (entry in state.nearbyMissingCatalogMaps) {
            if (addedRows >= maxMapRows) break
            if (entry.id == activeDl?.mapId) continue
            itemListBuilder.addItem(
                Row.Builder()
                    .setTitle("Download: ${entry.mapName}")
                    .addText("Nearby USFS map • ${entry.fileSizeMbText()}")
                    .setOnClickListener {
                        mapController.downloadCatalogMap(entry, isAutomatic = false, pinned = true)
                        screenManager.pop()
                    }
                    .build()
            )
            addedRows++
        }

        if (addedRows == 0) {
            itemListBuilder.addItem(
                Row.Builder()
                    .setTitle("No GeoPDF maps found")
                    .addText("Enable internet for GPS auto-download or import PDFs on phone")
                    .build()
            )
        }

        // Also provide a row to toggle GPS simulation on the active map for testing in the car/DHU
        val simActive = mapController.locationTracker.isSimulating
        itemListBuilder.addItem(
            Row.Builder()
                .setTitle(if (simActive) "Stop Simulated GPS Drive" else "Start Simulated GPS Drive (Demo)")
                .addText("Simulates driving across the active map to test arrow & Re-center")
                .setOnClickListener {
                    mapController.toggleGpsSimulation()
                    screenManager.pop()
                }
                .build()
        )

        return ListTemplate.Builder()
            .setSingleList(itemListBuilder.build())
            .setTitle("Select GeoPDF Map")
            .setHeaderAction(Action.BACK)
            .build()
    }
}
