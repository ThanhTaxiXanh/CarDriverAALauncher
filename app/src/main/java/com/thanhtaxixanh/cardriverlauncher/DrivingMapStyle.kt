package com.thanhtaxixanh.cardriverlauncher

import android.util.Log
import com.mapbox.bindgen.Value
import com.mapbox.maps.Style

/**
 * Enable Mapbox Standard's real 3D buildings, landmarks and HD road geometry.
 *
 * 3D building heights are shown only where the map dataset contains them.
 * showHdRoads only reveals Mapbox's supported coverage and DOES NOT manufacture
 * lane geometries or provide lane-accurate GPS in unsupported countries.
 */
object DrivingMapStyle {
    fun configure(style: Style) {
        val options = listOf(
            "show3dObjects" to true,
            "show3dBuildings" to true,
            "show3dLandmarks" to true,
            "show3dFacades" to true,
            "showHdRoads" to true,
            "showRoadLabels" to true
        )
        options.forEach { (key, enabled) ->
            try {
                val result = style.setStyleImportConfigProperty(
                    "basemap", key, Value.valueOf(enabled)
                )
                if (result.error != null) {
                    Log.w("DrivingMapStyle", "Unsupported configuration $key: ${result.error}")
                }
            } catch (error: Exception) {
                Log.d("DrivingMapStyle", "Could not enable $key", error)
            }
        }
        try {
            style.setStyleImportConfigProperty("basemap", "lightPreset", Value.valueOf("day"))
        } catch (error: Exception) {
            Log.d("DrivingMapStyle", "Daylight preset unavailable", error)
        }
    }
}
