package com.monteslu.trailtracker.managers

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.*
import com.monteslu.trailtracker.data.GpsPoint
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class LocationManager(private val context: Context) {
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    
    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY,
        100L // 100ms interval for 5-10Hz updates
    ).build()

    @SuppressLint("MissingPermission")
    fun getLocationUpdates(compassProvider: () -> Float): Flow<GpsPoint> = callbackFlow {
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                super.onLocationResult(result)
                result.locations.forEach { location ->
                    val gpsPoint = createGpsPoint(location, compassProvider())
                    trySend(gpsPoint)
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            callback,
            Looper.getMainLooper()
        )

        awaitClose {
            fusedLocationClient.removeLocationUpdates(callback)
        }
    }

    private fun createGpsPoint(location: Location, compass: Float): GpsPoint {
        return GpsPoint(
            timestamp = System.currentTimeMillis(),
            lat = location.latitude,
            lon = location.longitude,
            alt = location.altitude,
            speed = location.speed,
            accuracy = location.accuracy,
            compass = compass
        )
    }
    
    fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(object : LocationCallback() {})
    }
}