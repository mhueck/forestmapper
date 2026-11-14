package com.mapper.geopdf.auto

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator

/**
 * Android Auto entry point service for rendering GeoPDF maps and real-time GPS
 * movement direction on the vehicle head unit display.
 */
class MapperCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        return if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        }
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        return MapperCarSession()
    }

    override fun onCreateSession(): Session {
        return MapperCarSession()
    }
}
