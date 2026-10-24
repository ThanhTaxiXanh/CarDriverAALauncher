package com.thanhtaxixanh.cardriverlauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.location.Location
import android.os.SystemClock
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.core.graphics.drawable.IconCompat
import java.util.UUID

/**
 * Host-rendered traffic report template (not an Android Dialog/Window).
 *
 * Speed example: Report icon -> Speed -> 50 => writes GPS + reported speed 50.
 * The host may limit or refuse multi-choice interaction in driving mode. We
 * respect those restrictions, never try to bypass the car's distraction lock.
 *
 * All reports stay USER_PENDING/unverified; choosing "50" does NOT certify an
 * official limit or change OSM way speed. Captures GPS at the FINAL selection.
 */
class CarTrafficReportScreen(
    carContext: CarContext,
    private val onReported: () -> Unit
) : Screen(carContext) {
    private enum class Page { ROOT, SPEED, SIGN, CAMERA, SERVICE, LANE }
    private var page = Page.ROOT
    private var offset = 0
    private var lastClickTime = 0L
    private val assets = TrafficIconAtlas(carContext.applicationContext)

    override fun onGetTemplate(): Template {
        val (title, list) = when(page) {
            Page.ROOT -> "Đóng góp giao thông" to listOf(
                ReportChoice("SPEED", "Tốc độ", "hud/50"),
                ReportChoice("SIGN", "Biển báo", "alert/residential_area_in"),
                ReportChoice("CAMERA", "Camera", "icons/map-pin-camera-type-speed"),
                ReportChoice("SERVICE", "Dịch vụ", "icons/toll-booth"),
                ReportChoice("LANE", "Làn đường", "lanes/straight")
            )
            Page.SPEED -> "Biển tốc độ" to (
                TrafficSignCatalog.speedLimits.map { ReportChoice(
                    it.toString(), "$it km/h", "hud/$it") } +
                ReportChoice("end_maxspeed", "Hết giới hạn", "alert/no_passing_out")
            )
            Page.SIGN -> "Biển báo" to TrafficSignCatalog.signs
            Page.CAMERA -> "Camera" to TrafficSignCatalog.cameras
            Page.SERVICE -> "Dịch vụ" to TrafficSignCatalog.services
            Page.LANE -> "Làn đường" to TrafficSignCatalog.lanes
        }
        val hasMore = list.size - offset > 6
        val shown = if (hasMore) list.drop(offset).take(5) else list.drop(offset).take(6)
        val items = ItemList.Builder()
        shown.forEach { choice ->
            val item = GridItem.Builder()
                .setTitle(choice.title)
                .setImage(CarIcon.Builder(IconCompat.createWithBitmap(
                    assets.get(choice.imageKey)
                        ?: fallbackIcon(choice.title, page == Page.SPEED)
                )).build())
                .setOnClickListener {
                    if (page == Page.ROOT) {
                        page = when (choice.code) {
                            "SPEED" -> Page.SPEED
                            "SIGN" -> Page.SIGN
                            "CAMERA" -> Page.CAMERA
                            "SERVICE" -> Page.SERVICE
                            else -> Page.LANE
                        }
                        offset = 0
                        invalidate()
                    } else {
                        saveReport(choice)
                    }
                }
                .build()
            items.addItem(item)
        }
        if (hasMore) {
            items.addItem(GridItem.Builder()
                .setTitle("Xem thêm »")
                .setImage(CarIcon.Builder(IconCompat.createWithBitmap(
                    fallbackIcon("…", false))).build())
                .setOnClickListener { offset += 5; invalidate() }
                .build())
        }
        val grid = GridTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .setSingleList(items.build()).build()

        // MapWithContentTemplate overlays a real host grid over the existing
        // Mapbox Surface, but Car API <7 can only use the standalone GridTemplate.
        return if (carContext.carAppApiLevel >= 7) {
            try {
                MapWithContentTemplate.Builder()
                    .setContentTemplate(grid)
                    .build()
            } catch (e: RuntimeException) {
                Log.w("CarTrafficReport", "Map grid unsupported on host", e)
                grid
            }
        } else grid
    }

    /** Captures real-time GPS at the click, rather than location when opened. */
    private fun saveReport(choice: ReportChoice) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastClickTime < 900L) return
        lastClickTime = now
        val location = Telemetry.lastLocation
        if (location == null || !location.hasAccuracy() ||
            location.accuracy > 75f || !isFresh(location)) {
            CarToast.makeText(carContext,
                "GPS chưa sẵn sàng; không lưu điểm sai", CarToast.LENGTH_SHORT).show()
            return
        }
        val type = when(page) {
            Page.SPEED -> "SPEED"
            Page.SIGN -> "SIGN"
            Page.CAMERA -> "CAMERA"
            Page.SERVICE -> "SERVICE"
            Page.LANE -> "LANE"
            Page.ROOT -> return
        }
        val speed = if (type == "SPEED" && choice.code != "end_maxspeed")
            choice.code.toIntOrNull()
        else null
        val heading = location.bearing.toDouble().takeIf {
            location.hasBearing() && location.speed > 1.0f
        }
        val event = TrafficEvent(
            id = "car:quick:" + UUID.randomUUID().toString(),
            lat = location.latitude,
            lon = location.longitude,
            type = type,
            code = if (type == "SPEED" && speed != null) "maxspeed" else choice.code,
            limit = speed,
            drivingBearing = heading,
            lane = "UNKNOWN",
            side = "UNKNOWN",
            source = "USER_PENDING",
            verified = false,
            updatedMs = System.currentTimeMillis()
        )
        try {
            TrafficDatabase.get(carContext).addReport(event)
            onReported()
            CarToast.makeText(carContext,
                "Đã ghim ${choice.title} theo GPS · chưa xác minh",
                CarToast.LENGTH_SHORT).show()
            screenManager.pop()
        } catch(e: Exception) {
            Log.e("CarTrafficReport", "Could not save report", e)
            CarToast.makeText(carContext, "Không lưu được báo cáo",
                CarToast.LENGTH_SHORT).show()
        }
    }

    private fun isFresh(location: Location): Boolean {
        val ts = location.elapsedRealtimeNanos
        if (ts <= 0L) return false
        val elapsed = SystemClock.elapsedRealtimeNanos() - ts
        return elapsed >= 0L && elapsed <= 15_000_000_000L
    }

    /** Used when an asset is unavailable. */
    private fun fallbackIcon(label: String, circle: Boolean): Bitmap {
        val result = Bitmap.createBitmap(94, 94, Bitmap.Config.ARGB_8888)
        val c = Canvas(result)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = if(circle) Color.rgb(229, 38, 45) else Color.rgb(25, 79, 119)
        c.drawCircle(47f, 47f, 43f, p)
        p.color = Color.WHITE
        c.drawCircle(47f, 47f, 33f, p)
        p.color = Color.rgb(22, 38, 56)
        p.typeface = Typeface.DEFAULT_BOLD
        p.textAlign = Paint.Align.CENTER
        p.textSize = 23f
        val text = when {
            label.contains("km/h") -> label.substringBefore(" ")
            label.length <= 3 -> label
            else -> "!"
        }
        c.drawText(text, 47f, 55f, p)
        return result
    }
}
