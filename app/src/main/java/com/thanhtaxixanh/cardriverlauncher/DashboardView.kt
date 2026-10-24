package com.thanhtaxixanh.cardriverlauncher

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mapbox.common.MapboxOptions
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** One layout renderer for the phone preview and experimental Auto Presentation Surface.
 * Auto projection DOES NOT guarantee custom dashboard UI approval or pixel-accurate allocation.
 */
class DashboardView(
    context: Context,
    private val config: LayoutConfig,
    private val onCarSurface: Boolean = false
) : LinearLayout(context) {
    private var map: MapView? = null
    private var widgetGrid: GridLayout? = null
    private var driverArrow: DriverArrow3D? = null
    private var roadName: RoadNameResolver? = null
    private var trafficOverlay: TrafficMapOverlay? = null
    private val handler = Handler(Looper.getMainLooper())
    private var alive = true
    private var slideIndex = 0
    private var started = false
    // ComponentActivity supplies a ViewTreeLifecycleOwner to Mapbox's lifecycle plugin.
    private val lifecycleManaged = context is LifecycleOwner
    private val tick = object : Runnable {
        override fun run() {
            if (!alive) return
            refreshTiles()
            handler.postDelayed(this, 2000L)
        }
    }
    private val refreshers = mutableListOf<() -> Unit>()

    init {
        orientation = if (config.mode == SplitMode.HORIZONTAL) HORIZONTAL else VERTICAL
        setBackgroundColor(Color.rgb(9, 23, 37))
        val horizontal = orientation == HORIZONTAL
        val mapBox = FrameLayout(context)
        mapBox.setBackgroundColor(Color.rgb(23, 48, 65))
        if (BuildConfig.MAPBOX_PUBLIC_TOKEN.startsWith("pk.")) {
            try {
                // Delay loading Mapbox native code until a map is explicitly requested.
                MapboxOptions.accessToken = BuildConfig.MAPBOX_PUBLIC_TOKEN
                val mapView = MapView(context)
                map = mapView
                mapBox.addView(mapView, FrameLayout.LayoutParams(-1, -1))
                mapView.mapboxMap.loadStyle(Style.STANDARD) { loadedStyle ->
                    DrivingMapStyle.configure(loadedStyle)
                    driverArrow = DriverArrow3D(mapView.mapboxMap).also {
                        it.install(loadedStyle)
                        Telemetry.lastLocation?.let(it::update)
                    }
                    // Coordinates below are a preview fallback, not inferred real GPS.
                    val initial = Telemetry.lastLocation
                    val point = if (initial == null) Point.fromLngLat(105.8342, 21.0278)
                    else Point.fromLngLat(initial.longitude, initial.latitude)
                    mapView.mapboxMap.setCamera(CameraOptions.Builder()
                        .center(point).zoom(17.0).pitch(65.0).build())
                    addRoadSigns(mapView)
                    trafficOverlay = TrafficMapOverlay(context, mapView.mapboxMap).also {
                        it.install(loadedStyle)
                        Telemetry.lastLocation?.let(it::update)
                    }
                }
            } catch (error: RuntimeException) {
                onMapInitFailure(mapBox, error)
            } catch (error: LinkageError) {
                onMapInitFailure(mapBox, error)
            }
        } else {
            mapBox.addView(textTile("Thiếu MAPBOX_PUBLIC_TOKEN\nCấu hình GitHub Secret để bật bản đồ 3D", 15f))
        }
        val topBadge = TextView(context).apply {
            text = "GPS: đang chờ   •   Giới hạn: —"
            setTextColor(Color.WHITE); textSize = 12f
            setBackgroundColor(0xC0002639.toInt()); setPadding(dp(9), dp(5), dp(9), dp(5))
        }
        mapBox.addView(topBadge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(8), dp(8), 0, 0)
        })
        val streetBadge = TextView(context).apply {
            text = "Đang xác định tên đường"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xD918314A.toInt())
            gravity = Gravity.CENTER
            textSize = 15f
            setPadding(dp(8), dp(7), dp(8), dp(7))
        }
        mapBox.addView(streetBadge, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(dp(16), 0, dp(16), dp(10))
        })
        roadName = RoadNameResolver(context.applicationContext) { street ->
            streetBadge.text = street
        }
        var lastCameraFix = 0L
        refreshers.add {
            val loc = Telemetry.lastLocation
            if (loc != null && loc.elapsedRealtimeNanos != lastCameraFix) {
                lastCameraFix = loc.elapsedRealtimeNanos
                driverArrow?.update(loc)
                trafficOverlay?.update(loc)
                if (driverArrow == null) {
                    map?.mapboxMap?.setCamera(CameraOptions.Builder()
                        .center(Point.fromLngLat(loc.longitude, loc.latitude))
                        .zoom(17.0).pitch(67.0)
                        .bearing(if (loc.hasBearing()) loc.bearing.toDouble() else 0.0)
                        .build())
                }
                roadName?.update(loc)
            }
            topBadge.text = if (loc == null) "GPS: chưa có tín hiệu   •   Giới hạn: —"
            else "GPS: ${"%.5f".format(Locale.US, loc.latitude)}, ${"%.5f".format(Locale.US, loc.longitude)}   •   Giới hạn: ${TrafficSnapshot.limitLabel()}"
        }
        val grid = GridLayout(context).apply {
            rowCount = config.rows; columnCount = config.columns
            setBackgroundColor(0xFF0A1828.toInt())
        }
        widgetGrid = grid
        val columnWidths = ColumnWidths.normalize(config.columnPercents, config.columns)
        val rowHeights = ColumnWidths.normalize(config.rowPercents, config.rows)
        for (index in 0 until config.rows * config.columns) {
            val kind = config.cells.getOrNull(index) ?: WidgetKind.LUNAR
            val cell = widget(kind)
            val columnIndex = index % config.columns
            val rowIndex = index / config.columns
            grid.addView(cell, GridLayout.LayoutParams(
                GridLayout.spec(rowIndex, 1, rowHeights[rowIndex].toFloat()),
                GridLayout.spec(columnIndex, 1, columnWidths[columnIndex].toFloat())
            ).apply {
                width = 0; height = 0; setMargins(dp(2), dp(2), dp(2), dp(2))
            })
        }
        val weight = config.mapPercent.toFloat()
        addView(mapBox, LayoutParams(if (horizontal) 0 else -1, if (horizontal) -1 else 0, weight))
        addView(grid, LayoutParams(if (horizontal) 0 else -1, if (horizontal) -1 else 0, (100 - weight)))
        handler.post(tick)
    }

    private fun onMapInitFailure(container: FrameLayout, error: Throwable) {
        android.util.Log.e("CarDriverLauncher", "Mapbox init failure", error)
        try { map?.onDestroy() } catch (_: Exception) { }
        map = null
        container.removeAllViews()
        container.addView(textTile("Bản đồ 3D không khả dụng.\\nVẫn có thể dùng các widget.", 15f))
    }
    private fun addRoadSigns(m: MapView) {
        try {
            val manager = m.annotations.createPointAnnotationManager()
            for (sign in RoadSignsStore(context).all()) {
                val text = if (sign.danger) "!" else (sign.speedLimit?.toString() ?: continue)
                manager.create(PointAnnotationOptions()
                    .withPoint(Point.fromLngLat(sign.longitude, sign.latitude))
                    .withIconImage(signBitmap(text, sign.danger)))
            }
        } catch (_: Exception) { /* annotations are optional if SDK styles not ready */ }
    }
    private fun signBitmap(value: String, danger: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE; canvas.drawCircle(48f, 48f, 39f, p)
        p.color = Color.RED; p.style = Paint.Style.STROKE; p.strokeWidth = 10f
        canvas.drawCircle(48f, 48f, 39f, p)
        p.style = Paint.Style.FILL; p.color = Color.BLACK
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = if (value.length > 2) 32f else 45f
        p.textAlign = Paint.Align.CENTER
        canvas.drawText(value, 48f, 61f, p)
        return bitmap
    }
    private fun widget(kind: WidgetKind): View {
        val outer = FrameLayout(context).apply {
            setBackgroundColor(0xFF153448.toInt()); setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        when (kind) {
            WidgetKind.LUNAR -> {
                val t = textTile("", 15f); outer.addView(t)
                refreshers.add {
                    val now = java.util.Calendar.getInstance()
                    val l = LunarCalendar.solarToLunar(now.get(java.util.Calendar.DAY_OF_MONTH),
                        now.get(java.util.Calendar.MONTH) + 1, now.get(java.util.Calendar.YEAR))
                    t.text = "☀ ${SimpleDateFormat("dd/MM/yyyy", Locale("vi", "VN")).format(Date())}\n" +
                        "☾ ${l.day}/${l.month}${if (l.leap) " nhuận" else ""}\n${LunarCalendar.canChi(l.year)}"
                }
            }
            WidgetKind.TELEMETRY -> {
                val t = textTile("", 16f); outer.addView(t)
                refreshers.add {
                    t.text = "${Telemetry.speedKmh?.toString() ?: "—"} km/h\n" +
                        "Giới hạn: ${TrafficSnapshot.limitLabel()}\n" +
                        "${"%.1f".format(Locale.US, Telemetry.distanceKm)} km · AVG ${Telemetry.averageKmh}\n" +
                        if (Telemetry.gpsAvailable) "GPS: OK" else "GPS: chưa sẵn sàng"
                }
            }
            WidgetKind.MEDIA -> {
                val container = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER }
                val t = textTile("", 13f)
                container.addView(t, LayoutParams(-1, 0, 1f))
                if (!onCarSurface) {
                    val bar = LinearLayout(context).apply { gravity = Gravity.CENTER }
                    listOf("⏮", "⏯", "⏭").forEachIndexed { idx, name ->
                        val b = Button(context).apply {
                            text = name; textSize = 13f
                            setOnClickListener {
                                when (idx) { 0 -> MediaSnapshot.previous(); 1 -> MediaSnapshot.toggle(); else -> MediaSnapshot.next() }
                            }
                        }
                        bar.addView(b, LayoutParams(0, dp(46), 1f))
                    }
                    container.addView(bar)
                }
                outer.addView(container)
                refreshers.add {
                    MediaSnapshot.refresh(context)
                    t.text = "♫ ${MediaSnapshot.title()}\n${MediaSnapshot.artist()}"
                }
            }
            WidgetKind.PHOTO -> {
                if (onCarSurface) {
                    outer.addView(textTile("Ảnh được quản lý trên điện thoại\n(ẩn khi lái xe)", 13f))
                } else {
                    val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                    val fallback = textTile("Chưa chọn ảnh", 13f)
                    outer.addView(image, FrameLayout.LayoutParams(-1, -1))
                    outer.addView(fallback, FrameLayout.LayoutParams(-1, -1))
                    refreshers.add {
                        if (config.imageUris.isNotEmpty()) {
                            try {
                                image.setImageURI(Uri.parse(config.imageUris[slideIndex % config.imageUris.size]))
                                fallback.visibility = GONE
                            } catch (_: Exception) { fallback.visibility = VISIBLE }
                        }
                    }
                    var nextSlideAt = android.os.SystemClock.elapsedRealtime() + 6000L
                    refreshers.add {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now >= nextSlideAt && config.imageUris.size > 1) {
                            slideIndex++; nextSlideAt = now + 6000L
                            try { image.setImageURI(Uri.parse(config.imageUris[slideIndex % config.imageUris.size])) }
                            catch (_: Exception) { fallback.visibility = VISIBLE }
                        }
                    }
                }
            }
        }
        return outer
    }
    private fun textTile(value: String, size: Float): TextView = TextView(context).apply {
        text = value; textSize = size; gravity = Gravity.CENTER
        setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun refreshTiles() { refreshers.forEach { it() } }

    /**
     * Apply row heights and column widths in place without recreating Mapbox.
     * For a 2 x 1 grid, rowPercents controls the height of each selected widget.
     */
    fun updateGridRatios(rowPercents: List<Int>, columnPercents: List<Int>) {
        val grid = widgetGrid ?: return
        val heights = ColumnWidths.normalize(rowPercents, config.rows)
        val widths = ColumnWidths.normalize(columnPercents, config.columns)
        for (index in 0 until grid.childCount) {
            val row = index / config.columns
            val column = index % config.columns
            val child = grid.getChildAt(index)
            child.layoutParams = GridLayout.LayoutParams(
                GridLayout.spec(row, 1, heights[row].toFloat()),
                GridLayout.spec(column, 1, widths[column].toFloat())
            ).apply {
                width = 0
                height = 0
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
        }
        grid.requestLayout()
    }
    fun resumeMap() {
        // Lifecycle plugin handles Activity-bound maps. Do not dispatch duplicate starts.
        if (lifecycleManaged) return
        if (!started) { map?.onStart(); started = true }
    }
    fun pauseMap() {
        if (lifecycleManaged) return
        if (started) { map?.onStop(); started = false }
    }
    fun dispose() {
        alive = false
        handler.removeCallbacks(tick)
        roadName = null
        driverArrow = null
        trafficOverlay = null
        pauseMap()
        map?.onDestroy()
        map = null
    }
}
