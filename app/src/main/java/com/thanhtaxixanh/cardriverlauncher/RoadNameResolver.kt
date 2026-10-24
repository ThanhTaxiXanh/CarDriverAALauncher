package com.thanhtaxixanh.cardriverlauncher

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.Locale

/**
 * Asynchronous reverse geocoding for a display-only street label.
 * Street names depend on the device's geocoder backend and may be absent.
 * Never block the Android Auto or Maps rendering thread on network lookups.
 */
class RoadNameResolver(
    context: Context,
    private val onName: (String) -> Unit
) {
    private val geocoder = Geocoder(context, Locale("vi", "VN"))
    private val mainHandler = Handler(Looper.getMainLooper())
    private var queriedLocation: Location? = null
    private var lastQueryAt = 0L
    @Volatile private var pending = false

    fun update(location: Location) {
        val elapsed = SystemClock.elapsedRealtime()
        if (pending || elapsed - lastQueryAt < 25000L) return
        if (queriedLocation?.distanceTo(location)?.let { it < 75f } == true) return
        lastQueryAt = elapsed
        queriedLocation = Location(location)
        pending = true
        val lat = location.latitude
        val lon = location.longitude

        if (Build.VERSION.SDK_INT >= 33) {
            try {
                geocoder.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<android.location.Address>) {
                        finish(addresses.firstOrNull()?.thoroughfare
                            ?: addresses.firstOrNull()?.featureName)
                    }
                    override fun onError(errorMessage: String?) {
                        finish(null)
                    }
                })
            } catch (e: Exception) { finish(null) }
        } else {
            Thread {
                val value = try {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(lat, lon, 1)
                    addresses?.firstOrNull()?.thoroughfare ?: addresses?.firstOrNull()?.featureName
                } catch (e: Exception) {
                    Log.d("RoadNameResolver", "Geocoder unavailable", e)
                    null
                }
                finish(value)
            }.start()
        }
    }

    private fun finish(name: String?) {
        mainHandler.post {
            pending = false
            if (!name.isNullOrBlank()) onName(name)
        }
    }
}
