package com.thanhtaxixanh.cardriverlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.location.Location
import android.util.Log
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.Point
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.generated.symbolLayer
import com.mapbox.maps.extension.style.layers.properties.generated.IconAnchor
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import com.mapbox.maps.extension.style.sources.getSourceAs
import com.mapbox.maps.extension.style.expressions.dsl.generated.get

/**
 * Mapbox annotations from locally imported OSM positions and local unverified reports.
 * No fake sign is generated from BRF maxspeed_implicit or estimated driving speed.
 */
class TrafficMapOverlay(context: Context, private val map: MapboxMap) {
    private val db = TrafficDatabase.get(context)
    private var ready = false
    private var lastAt = 0L

    fun install(style: Style) {
        try {
            listOf("speed","sign","camera","service","lane").forEach { icon ->
                val result = style.addImage("traffic-$icon", marker(icon), false)
                if(result.error != null) Log.w("TrafficOverlay","Image $icon: ${result.error}")
            }
            style.addSource(geoJsonSource(SOURCE) {
                featureCollection(FeatureCollection.fromFeatures(emptyList<Feature>()))
            })
            style.addLayer(symbolLayer(LAYER,SOURCE) {
                iconImage(get("icon"))
                iconSize(0.52)
                iconAllowOverlap(true)
                textField(get("caption"))
                textSize(11.0)
                textColor("#FFFFFF")
                textHaloColor("#172C44")
                textHaloWidth(2.0)
                textOffset(listOf(0.0, 2.0))
                textAllowOverlap(false)
                iconAnchor(IconAnchor.CENTER)
            })
            ready = true
        } catch(error: Exception) {
            ready = false
            Log.e("TrafficOverlay","Failed to register local map annotations",error)
        }
    }

    /** Throttled update; safe when GPS absent, database empty, or offline. */
    fun update(location: Location) {
        val now = android.os.SystemClock.elapsedRealtime()
        if(now-lastAt < 1900L) return
        lastAt = now
        try {
            TrafficSnapshot.currentRoad = db.matchRoad(location)
            if (!ready) return
            val items = db.nearEvents(location, 500.0).map { event ->
                Feature.fromGeometry(Point.fromLngLat(event.lon,event.lat)).apply {
                    addStringProperty("icon", "traffic-" + when(event.type) {
                        "SPEED" -> "speed"
                        "CAMERA" -> "camera"
                        "SERVICE" -> "service"
                        "LANE" -> "lane"
                        else -> "sign"
                    })
                    val label = when(event.type) {
                        "SPEED" -> event.limit?.let { "$it" } ?: "Hết"
                        "CAMERA" -> "Camera"
                        "SERVICE" -> "Dịch vụ"
                        "LANE" -> "Làn"
                        else -> "Biển báo"
                    }
                    val lane = if(event.lane !in listOf("UNKNOWN","ALL","")) " · ${event.lane}" else ""
                    val draft = if(event.source=="USER_PENDING") " ?" else ""
                    addStringProperty("caption", label+lane+draft)
                }
            }
            map.getStyle()?.getSourceAs<GeoJsonSource>(SOURCE)
                ?.featureCollection(FeatureCollection.fromFeatures(items))
        } catch(error: Exception) {
            Log.w("TrafficOverlay","Traffic query failed",error)
        }
    }

    private fun marker(type: String): Bitmap {
        val bmp = Bitmap.createBitmap(92,100,Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val base = when(type) {
            "camera" -> Color.rgb(255,212,51)
            "service" -> Color.rgb(35,185,110)
            "lane" -> Color.rgb(46,210,225)
            "speed" -> Color.WHITE
            else -> Color.WHITE
        }
        p.color = Color.argb(105,0,0,0)
        c.drawCircle(46f,48f,42f,p)
        p.color = if(type=="speed"||type=="sign") Color.rgb(231,47,42) else Color.rgb(22,38,61)
        c.drawCircle(46f,44f,40f,p)
        p.color = base
        c.drawCircle(46f,44f,33f,p)
        p.color = Color.rgb(18,31,45)
        p.textSize = when(type) {
            "speed" -> 33f
            "camera" -> 27f
            "service" -> 32f
            "lane" -> 31f
            else -> 30f
        }
        p.typeface = Typeface.DEFAULT_BOLD
        p.textAlign = Paint.Align.CENTER
        val symbol = when(type) {
            "speed" -> "!"
            "camera" -> "CAM"
            "service" -> "P"
            "lane" -> "⇧"
            else -> "!"
        }
        if(type=="camera")p.textSize=22f
        c.drawText(symbol,46f,54f,p)
        return bmp
    }

    companion object {
        const val SOURCE = "traffic-reported-points"
        const val LAYER = "traffic-reported-icons"
    }
}

object TrafficSnapshot {
    @Volatile var currentRoad: RoadSpeedMatch? = null
        internal set
    fun limitLabel(): String = currentRoad?.let { road ->
        when {
            road.laneSpecificUnknown -> "Làn: chưa rõ"
            road.limit == null -> "—"
            else -> "${road.limit} km/h (OSM)"
        }
    } ?: "—"
}
