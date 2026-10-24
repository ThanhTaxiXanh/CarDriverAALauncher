package com.thanhtaxixanh.cardriverlauncher

import android.location.Location
import android.util.Log
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapboxExperimental
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.addSource
import com.mapbox.maps.extension.style.addLayer
import com.mapbox.maps.extension.style.getSourceAs
import com.mapbox.maps.extension.style.getLayerAs
import com.mapbox.maps.extension.style.layers.generated.ModelLayer
import com.mapbox.maps.extension.style.layers.generated.modelLayer
import com.mapbox.maps.extension.style.layers.properties.generated.ModelType
import com.mapbox.maps.extension.style.model.addModel
import com.mapbox.maps.extension.style.model.model
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * An actual glTF 3D arrow, sitting on road-level map geometry.
 *
 * Location is raw phone GPS, NOT lane-accurate positioning.
 * The camera target is shifted ahead of the arrow so it remains near the bottom.
 * The marker can rotate with device bearing; no lane is inferred from GPS.
 */
@OptIn(MapboxExperimental::class)
class DriverArrow3D(private val mapboxMap: MapboxMap) {
    private var installed = false
    private var lastBearing = 0.0

    fun install(style: Style) {
        installed = false
        try {
            style.addModel(model(MODEL_ID) { uri("asset://driver_arrow.gltf") })
            style.addSource(geoJsonSource(SOURCE_ID) {
                geometry(Point.fromLngLat(105.8342, 21.0278))
            })
            style.addLayer(modelLayer(LAYER_ID, SOURCE_ID) {
                modelId(MODEL_ID)
                modelType(ModelType.COMMON_3D)
                modelScale(listOf(1.0, 1.0, 1.0))
                modelRotation(listOf(0.0, 0.0, 0.0))
                modelAmbientOcclusionIntensity(0.7)
            })
            installed = true
        } catch (e: Exception) {
            Log.w("DriverArrow3D", "ModelLayer unsupported on this renderer", e)
        }
    }

    fun update(location: Location) {
        if (!location.hasAccuracy() || location.accuracy > 80f) return
        val bearing = if (location.hasBearing() && (location.speed > 1.0f)) {
            location.bearing.toDouble().also { lastBearing = it }
        } else lastBearing

        val gps = Point.fromLngLat(location.longitude, location.latitude)
        try {
            if (installed) {
                mapboxMap.getStyle()?.getSourceAs<GeoJsonSource>(SOURCE_ID)?.geometry(gps)
                mapboxMap.getStyle()?.getLayerAs<ModelLayer>(LAYER_ID)
                    ?.modelRotation(listOf(0.0, 0.0, bearing))
            }
            val ahead = destination(location.latitude, location.longitude, bearing, 46.0)
            mapboxMap.setCamera(CameraOptions.Builder()
                .center(ahead)
                .bearing(bearing)
                .zoom(17.4)
                .pitch(67.0)
                .build())
        } catch (e: Exception) {
            Log.w("DriverArrow3D", "Arrow/camera update skipped", e)
        }
    }

    private fun destination(lat: Double, lon: Double, bearing: Double, meters: Double): Point {
        val radLat = Math.toRadians(lat)
        val radLon = Math.toRadians(lon)
        val radBearing = Math.toRadians(bearing)
        val angular = meters / 6371000.0
        val targetLat = asin(sin(radLat) * cos(angular) +
            cos(radLat) * sin(angular) * cos(radBearing))
        val targetLon = radLon + atan2(
            sin(radBearing) * sin(angular) * cos(radLat),
            cos(angular) - sin(radLat) * sin(targetLat)
        )
        return Point.fromLngLat(Math.toDegrees(targetLon), Math.toDegrees(targetLat))
    }

    companion object {
        private const val SOURCE_ID = "car-driver-arrow-source"
        private const val LAYER_ID = "car-driver-arrow-layer"
        private const val MODEL_ID = "car-driver-arrow-model"
    }
}
