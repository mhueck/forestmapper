package com.mapper.geopdf.auto

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.mapper.geopdf.state.MapController

class MapperCarSession : Session() {

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                val controller = MapController.getInstance(carContext)
                controller.locationTracker.startTracking()
                controller.scanMapsDirectory(force = false)
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen {
        return MapperCarScreen(carContext)
    }
}
