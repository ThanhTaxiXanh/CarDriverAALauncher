package com.thanhtaxixanh.cardriverlauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.location.Location
import android.util.Log
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.generated.SymbolLayer
import com.mapbox.maps.extension.style.layers.generated.symbolLayer
import com.mapbox.maps.extension.style.layers.properties.generated.IconAnchor
import com.mapbox.maps.extension.style.layers.properties.generated.IconPitchAlignment
import com.mapbox.maps.extension.style.layers.properties.generated.IconRotationAlignment
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import com.mapbox.maps.extension.style.sources.getSourceAs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * One road-anchored, premium 3D-looking chevron marker (blue body, white bevel).
 *
 * The icon is a stylized 2.5D bitmap inside Mapbox SymbolLayer, anchored to GPS.
 * Unlike a screen-space HUD, its location is tied to real-world coordinates.
 * Raw GPS cannot determine the occupied driving lane; no lane claim is made.
 */
class DriverArrow3D(
    private val map: MapboxMap,
    private val cameraPadding: () -> EdgeInsets = { EdgeInsets(0.0, 0.0, 0.0, 0.0) }
) {
    private var installed = false
    val isRegistered: Boolean get() = installed
    private var lastHeading = 0.0
    private var layer: SymbolLayer? = null

    fun install(style: Style) {
        installed = false
        try {
            // Non-SDF preserves the multi-color gradient, white trim and 3D shadow.
            val result = style.addImage(ICON_ID, chevron(), false)
            if (result.error != null) {
                Log.w(TAG, "Chevron bitmap rejected: ${result.error}")
                return
            }
            val current = Telemetry.lastLocation
                ?.let { Point.fromLngLat(it.longitude, it.latitude) }
                ?: Point.fromLngLat(105.8342, 21.0278)
            style.addSource(geoJsonSource(SOURCE_ID) {
                geometry(current)
            })
            val newLayer = symbolLayer(LAYER_ID, SOURCE_ID) {
                iconImage(ICON_ID)
                iconSize(0.62)
                iconAllowOverlap(true)
                iconIgnorePlacement(true)
                iconAnchor(IconAnchor.CENTER)
                iconPitchAlignment(IconPitchAlignment.MAP)
                iconRotationAlignment(IconRotationAlignment.MAP)
                iconRotate(lastHeading)
            }
            style.addLayer(newLayer)
            layer = newLayer
            installed = true
        } catch (error: Exception) {
            layer = null
            Log.e(TAG, "Failed to install anchored driver chevron", error)
        }
    }

    fun update(fix: Location, followCamera: Boolean = true) {
        if (!fix.hasAccuracy() || fix.accuracy > 80f) return
        val bearing = if (fix.hasBearing() && fix.speed > 1.2f) {
            val target = ((fix.bearing.toDouble() % 360) + 360) % 360
            val delta = (target - lastHeading + 540) % 360 - 180
            (lastHeading + delta * 0.45 + 360) % 360
        } else lastHeading
        lastHeading = bearing

        try {
            if (installed) {
                map.getStyle()?.getSourceAs<GeoJsonSource>(SOURCE_ID)
                    ?.geometry(Point.fromLngLat(fix.longitude, fix.latitude))
                layer?.iconRotate(bearing)
            }
            if (!followCamera) return
            // Target the road ahead so the car icon stays near the bottom of the map.
            // Padding removes the area covered by the right or lower widget dashboard.
            val ahead = moveForward(fix.latitude, fix.longitude, bearing, 25.0)
            map.setCamera(CameraOptions.Builder()
                .center(ahead)
                .bearing(bearing)
                .zoom(18.0)
                .pitch(64.0)
                .padding(cameraPadding())
                .build())
        } catch (error: Exception) {
            Log.w(TAG, "Driver position update skipped", error)
        }
    }

    private fun chevron(): Bitmap {
        val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val white = Path().apply {
            moveTo(96f, 17f)
            cubicTo(103f, 17f, 109f, 22f, 115f, 28f)
            lineTo(177f, 100f)
            quadTo(183f, 108f, 175f, 117f)
            lineTo(161f, 129f)
            quadTo(155f, 134f, 147f, 129f)
            lineTo(104f, 109f)
            quadTo(96f, 105f, 88f, 109f)
            lineTo(45f, 129f)
            quadTo(37f, 134f, 31f, 129f)
            lineTo(17f, 117f)
            quadTo(9f, 108f, 15f, 100f)
            lineTo(77f, 28f)
            cubicTo(83f, 22f, 89f, 17f, 96f, 17f)
            close()
        }
        // Thick dark underbody casts a short road shadow.
        p.color = Color.argb(105, 4, 14, 30)
        c.save(); c.translate(0f, 14f); c.drawPath(white, p); c.restore()
        p.color = Color.rgb(67, 86, 116)
        c.save(); c.translate(0f, 6f); c.drawPath(white, p); c.restore()
        p.color = Color.WHITE
        c.drawPath(white, p)

        val blue = Path().apply {
            moveTo(96f, 33f)
            quadTo(100f, 33f, 105f, 39f)
            lineTo(163f, 105f)
            quadTo(166f, 108f, 160f, 111f)
            lineTo(149f, 116f)
            lineTo(103f, 96f)
            quadTo(96f, 93f, 89f, 96f)
            lineTo(43f, 116f)
            lineTo(32f, 111f)
            quadTo(26f, 108f, 29f, 105f)
            lineTo(87f, 39f)
            quadTo(92f, 33f, 96f, 33f)
            close()
        }
        p.shader = LinearGradient(96f, 27f, 96f, 124f,
            intArrayOf(Color.rgb(90, 191, 255), Color.rgb(33, 103, 236),
                Color.rgb(12, 57, 187)), null, Shader.TileMode.CLAMP)
        c.drawPath(blue, p)
        p.shader = null
        p.color = Color.argb(125, 240, 251, 255)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f
        c.drawPath(blue, p)
        p.style = Paint.Style.FILL
        return bitmap
    }

    private fun moveForward(lat: Double, lon: Double, heading: Double, meters: Double): Point {
        val phi = Math.toRadians(lat)
        val lambda = Math.toRadians(lon)
        val theta = Math.toRadians(heading)
        val distance = meters / 6371000.0
        val nextPhi = asin(sin(phi) * cos(distance) + cos(phi) * sin(distance) * cos(theta))
        val nextLambda = lambda + atan2(
            sin(theta) * sin(distance) * cos(phi),
            cos(distance) - sin(phi) * sin(nextPhi)
        )
        return Point.fromLngLat(Math.toDegrees(nextLambda), Math.toDegrees(nextPhi))
    }

    companion object {
        private const val TAG = "DriverArrow3D"
        private const val ICON_ID = "driver-3d-chevron-icon"
        private const val SOURCE_ID = "driver-chevron-gps"
        private const val LAYER_ID = "driver-chevron-map"
    }
}
