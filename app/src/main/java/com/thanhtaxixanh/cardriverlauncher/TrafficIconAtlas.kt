package com.thanhtaxixanh.cardriverlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log

/**
 * Color sign assets supplied by the user in assets.zip (flutter_assets/assets).
 * Cropped into a 48x48 WebP atlas solely to keep the Android APK and Git repo
 * small. 68 supplied traffic / camera / lane / speed images are included.
 * Do not confuse these images with physical GPS traffic-sign datasets.
 */
class TrafficIconAtlas(context: Context) {
    private val app = context.applicationContext

    fun get(key: String?): Bitmap? {
        val index = NAMES.indexOf(key ?: return null)
        if (index < 0) return null
        synchronized(lock) {
            icons[index]?.let { if (!it.isRecycled) return it }
            val whole = atlas ?: try {
                app.assets.open("traffic_signs_atlas.webp").use { stream ->
                    BitmapFactory.decodeStream(stream)
                }?.also { atlas = it }
            } catch (error: Exception) {
                Log.w("TrafficAtlas", "Bundled traffic icons unavailable", error)
                null
            } ?: return null
            val x = (index % COLUMNS) * TILE
            val y = (index / COLUMNS) * TILE
            if (x + TILE > whole.width || y + TILE > whole.height) return null
            val icon = Bitmap.createBitmap(whole, x, y, TILE, TILE)
            icons[index] = icon
            return icon
        }
    }

    companion object {
        private const val TILE = 48
        private const val COLUMNS = 8
        private val lock = Any()
        private var atlas: Bitmap? = null
        private val icons = mutableMapOf<Int, Bitmap>()
        private val NAMES = listOf(
            "alert/ban_car",
            "alert/ban_motorcycle",
            "alert/distance_safe",
            "alert/no_left",
            "alert/no_left_right",
            "alert/no_left_uleft",
            "alert/no_park",
            "alert/no_park_even",
            "alert/no_park_no_stop",
            "alert/no_park_odd",
            "alert/no_passing_in",
            "alert/no_passing_out",
            "alert/no_right",
            "alert/no_right_uright",
            "alert/no_straight",
            "alert/no_straight_left",
            "alert/no_straight_right",
            "alert/no_uleft",
            "alert/no_uright",
            "alert/only_left",
            "alert/only_left_right",
            "alert/only_right",
            "alert/only_straight",
            "alert/only_straight_left",
            "alert/only_straight_right",
            "alert/railway",
            "alert/residential_area_in",
            "alert/residential_area_out",
            "alert/tunnel",
            "hud/100", "hud/110", "hud/120", "hud/30", "hud/40", "hud/50",
            "hud/60", "hud/70", "hud/80", "hud/90",
            "icons/map-pin-camera-type-ai",
            "icons/map-pin-camera-type-combo-general",
            "icons/map-pin-camera-type-combo-speed-and-redlight",
            "icons/map-pin-camera-type-dummy",
            "icons/map-pin-camera-type-red-light",
            "icons/map-pin-camera-type-speed",
            "icons/railway-crossing",
            "icons/stopandrest",
            "icons/toll-booth",
            "icons/warning",
            "lanes/left",
            "lanes/left_right",
            "lanes/left_uleft",
            "lanes/right",
            "lanes/right_uright",
            "lanes/slightleft",
            "lanes/slightright",
            "lanes/straight",
            "lanes/straight_left",
            "lanes/straight_left_right",
            "lanes/straight_left_right_uleft",
            "lanes/straight_left_uleft",
            "lanes/straight_right",
            "lanes/straight_right_uleft",
            "lanes/straight_slightleft",
            "lanes/straight_slightright",
            "lanes/straight_uleft",
            "lanes/straight_uright",
            "lanes/uleft"
        )
    }
}
