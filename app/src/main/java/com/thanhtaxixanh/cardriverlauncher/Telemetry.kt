package com.thanhtaxixanh.cardriverlauncher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import kotlin.math.roundToInt

// Android Auto projection uses the location permission granted to the phone APK.
object Telemetry {
    @Volatile var speedKmh: Int? = null; private set
    @Volatile var lastLocation: Location? = null; private set
    @Volatile var distanceKm: Double = 0.0; private set
    @Volatile var averageKmh: Int = 0; private set
    @Volatile var gpsAvailable: Boolean = false; private set
    private var previous: Location? = null
    private var startElapsedMs = 0L
    private var manager: LocationManager? = null
    private var registered = false
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            gpsAvailable = true
            speedKmh = if (location.hasSpeed()) (location.speed * 3.6f).roundToInt().coerceAtLeast(0) else null
            val last = previous
            if (last != null && location.elapsedRealtimeNanos > last.elapsedRealtimeNanos &&
                location.accuracy < 60f && last.accuracy < 60f) {
                val d = last.distanceTo(location).toDouble()
                if (d in 2.0..500.0) distanceKm += d / 1000.0
            }
            previous = Location(location)
            lastLocation = Location(location)
            if (startElapsedMs == 0L) startElapsedMs = SystemClock.elapsedRealtime()
            val hours = (SystemClock.elapsedRealtime() - startElapsedMs) / 3600000.0
            if (hours > 0) averageKmh = (distanceKm / hours).roundToInt()
        }
        override fun onProviderDisabled(provider: String) { gpsAvailable = false }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }
    @Synchronized fun start(context: Context) {
        if (registered) return
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        try {
            val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
            lm.requestLocationUpdates(provider, 1500L, 1f, listener)
            manager = lm; registered = true
        } catch (_: Exception) { gpsAvailable = false }
    }
    @Synchronized fun stop() {
        try { manager?.removeUpdates(listener) } catch (_: Exception) { }
        manager = null; registered = false
    }
}
