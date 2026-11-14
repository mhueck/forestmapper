package com.mapper.geopdf.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

data class GpsPosition(
    val latitude: Double,
    val longitude: Double,
    /** Movement direction in degrees clockwise from true North [0, 360). */
    val bearingDegrees: Float,
    val speedMetersPerSec: Float,
    val accuracyMeters: Float,
    val hasValidBearing: Boolean,
    val isSimulated: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Tracks real-time GPS location and movement direction (bearing) for both the phone UI
 * and Android Auto head unit screen.
 */
class LocationTracker(context: Context) {

    private val appContext = context.applicationContext
    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(appContext)
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _gpsPosition = MutableStateFlow<GpsPosition?>(null)
    val gpsPosition: StateFlow<GpsPosition?> = _gpsPosition.asStateFlow()

    private var lastLocation: Location? = null
    private var lastKnownBearing: Float = 0f
    private var hasEverHadBearing: Boolean = false
    private var isTracking = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var simulationJob: Job? = null

    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            if (simulationJob?.isActive == true) return
            onNewRawLocation(loc)
        }
    }

    private val fallbackListener = LocationListener { loc ->
        if (simulationJob?.isActive == true) return@LocationListener
        onNewRawLocation(loc)
    }

    @SuppressLint("MissingPermission")
    fun startTracking() {
        if (isTracking) return
        var startedAnyProvider = false

        try {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(500L)
                .setMinUpdateDistanceMeters(0.5f)
                .build()
            fusedClient.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper())
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && _gpsPosition.value == null) {
                    onNewRawLocation(loc)
                }
            }
            startedAnyProvider = true
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }

        // Also register standard GPS_PROVIDER as a reliable hardware fallback
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    0.5f,
                    fallbackListener,
                    Looper.getMainLooper()
                )
                startedAnyProvider = true
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }

        isTracking = startedAnyProvider
    }

    fun stopTracking() {
        isTracking = false
        try {
            fusedClient.removeLocationUpdates(fusedCallback)
        } catch (_: Exception) {
        }
        try {
            locationManager.removeUpdates(fallbackListener)
        } catch (_: Exception) {
        }
    }

    private fun onNewRawLocation(location: Location) {
        val prev = lastLocation
        var computedBearing = lastKnownBearing

        if (location.hasBearing() && location.speed >= 0.25f) {
            computedBearing = normalizeDegrees(location.bearing)
            hasEverHadBearing = true
        } else if (prev != null) {
            val dist = prev.distanceTo(location)
            if (dist >= 1.2f) {
                val rawBearing = normalizeDegrees(prev.bearingTo(location))
                computedBearing = if (hasEverHadBearing) {
                    smoothAngle(lastKnownBearing, rawBearing, 0.45f)
                } else {
                    rawBearing
                }
                hasEverHadBearing = true
            }
        }

        lastLocation = location
        lastKnownBearing = computedBearing

        _gpsPosition.value = GpsPosition(
            latitude = location.latitude,
            longitude = location.longitude,
            bearingDegrees = computedBearing,
            speedMetersPerSec = if (location.hasSpeed()) location.speed else 0f,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else 8f,
            hasValidBearing = hasEverHadBearing,
            isSimulated = false,
            timestampMs = System.currentTimeMillis()
        )
    }

    /**
     * Starts or stops a simulated GPS drive inside the given map bounds (useful for testing
     * when physically outside the map coverage area or in Android Auto Desktop Head Unit).
     */
    fun toggleSimulation(centerLat: Double, centerLon: Double) {
        if (simulationJob?.isActive == true) {
            stopSimulation()
            return
        }
        simulationJob = scope.launch {
            var lat = centerLat
            var lon = centerLon
            var headingDeg = 42f
            var tick = 0
            while (isActive) {
                // Smoothly curve heading to demonstrate the directional movement arrow and map follow
                headingDeg = normalizeDegrees(headingDeg + (sin(tick * 0.08) * 3.5).toFloat())
                val speedMps = 14.0f // ~31 mph forest road speed
                val dt = 0.25f // 4 Hz update rate for smooth follow
                val distMeters = speedMps * dt

                val headingRad = Math.toRadians(headingDeg.toDouble())
                val dLat = (distMeters * cos(headingRad)) / 111_132.0
                val dLon = (distMeters * sin(headingRad)) / (111_320.0 * cos(Math.toRadians(lat)))

                lat += dLat
                lon += dLon
                tick++

                _gpsPosition.value = GpsPosition(
                    latitude = lat,
                    longitude = lon,
                    bearingDegrees = headingDeg,
                    speedMetersPerSec = speedMps,
                    accuracyMeters = 4.5f,
                    hasValidBearing = true,
                    isSimulated = true,
                    timestampMs = System.currentTimeMillis()
                )
                delay(250L)
            }
        }
    }

    fun stopSimulation() {
        simulationJob?.cancel()
        simulationJob = null
    }

    val isSimulating: Boolean
        get() = simulationJob?.isActive == true

    private fun normalizeDegrees(deg: Float): Float {
        var d = deg % 360f
        if (d < 0f) d += 360f
        return d
    }

    private fun smoothAngle(current: Float, target: Float, alpha: Float): Float {
        var diff = (target - current + 540f) % 360f - 180f
        return normalizeDegrees(current + diff * alpha)
    }
}
