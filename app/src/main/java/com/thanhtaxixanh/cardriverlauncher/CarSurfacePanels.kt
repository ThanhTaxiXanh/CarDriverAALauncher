package com.thanhtaxixanh.cardriverlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.location.Location
import android.os.SystemClock
import android.util.Log
import com.mapbox.maps.MapSurface
import com.mapbox.maps.MapboxExperimental
import com.mapbox.maps.extension.androidauto.MapboxCarMapSurface
import com.mapbox.maps.renderer.widget.BitmapWidget
import com.mapbox.maps.renderer.widget.WidgetPosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Opt-in Android Auto dashboard, drawn in Mapbox's official MapSurface.
 * BitmapWidget is a *visual*, non-clickable overlay. The report control is a
 * genuine host-rendered icon in NavigationTemplate's map ActionStrip.
 *
 * Photos: one static, selected, downsampled phone image. No slideshow/interaction.
 * QR/photos on driving surfaces may not be accepted by production Android Auto
 * driver-distraction policy; keep this developer-only and test parked.
 */
@OptIn(MapboxExperimental::class)
internal class CarSurfacePanels(
    private val context: Context,
    private val car: MapboxCarMapSurface
) {
    private val surface: MapSurface = car.mapSurface
    private val surfaceWidth = car.surfaceContainer.width
    private val surfaceHeight = car.surfaceContainer.height
    private val photos = CarPhotoCache(context.applicationContext)
    private var config = SettingsStore(context).load().normalized()
    private var gridWidget: BitmapWidget? = null
    private var streetWidget: BitmapWidget? = null
    private var speedWidget: BitmapWidget? = null
    private var limitWidget: BitmapWidget? = null
    private var roadName: String = "Đang xác định tên đường"
    private var location: Location? = null
    private var lastDraw = 0L
    private var active = false

    fun attach() {
        if (surfaceWidth < 160 || surfaceHeight < 140) return
        active = true
        requestPhoto()
        installWidgets()
    }

    private fun requestPhoto() {
        if (config.cells.contains(WidgetKind.PHOTO)) {
            photos.request(config.imageUris.firstOrNull()) {
                if (active) refresh(force = true)
            }
        } else photos.request(null) {}
    }

    private fun installWidgets() {
        if (config.carDashboardEnabled) {
            val (width, height) = panelDimensions()
            if (width >= 64 && height >= 64) {
                val position = if (config.mode == SplitMode.HORIZONTAL)
                    widgetPosition(WidgetPosition.Horizontal.RIGHT, WidgetPosition.Vertical.TOP)
                else widgetPosition(WidgetPosition.Horizontal.LEFT, WidgetPosition.Vertical.BOTTOM)
                gridWidget = BitmapWidget(drawDashboard(width, height), position).also {
                    surface.addWidget(it)
                }
            }
        }
        val mapWidth = mapRegionWidth().coerceAtLeast(110)
        val mapHeight = mapRegionHeight().coerceAtLeast(110)
        val labelTop = (mapHeight - 50f).coerceAtLeast(8f)
        streetWidget = BitmapWidget(drawRoad(mapWidth), widgetPosition(
            WidgetPosition.Horizontal.LEFT, WidgetPosition.Vertical.TOP,
            4f, labelTop)).also { surface.addWidget(it) }

        // The two circles are constrained to the MAP portion; neither is placed
        // over the right-side widget column (55/45 etc.).
        val circleTop = 11f
        speedWidget = BitmapWidget(drawGauge(false), widgetPosition(
            WidgetPosition.Horizontal.LEFT, WidgetPosition.Vertical.TOP,
            (mapWidth - 148f).coerceAtLeast(5f), circleTop)).also {
            surface.addWidget(it)
        }
        limitWidget = BitmapWidget(drawGauge(true), widgetPosition(
            WidgetPosition.Horizontal.LEFT, WidgetPosition.Vertical.TOP,
            (mapWidth - 75f).coerceAtLeast(65f), circleTop)).also {
            surface.addWidget(it)
        }

    }

    private fun widgetPosition(
        h: WidgetPosition.Horizontal,
        v: WidgetPosition.Vertical,
        x: Float = 0f,
        y: Float = 0f
    ) = WidgetPosition.Builder()
        .setHorizontalAlignment(h).setVerticalAlignment(v)
        .setOffsetX(x).setOffsetY(y).build()

    private fun panelDimensions(): Pair<Int, Int> {
        val portion = (100 - config.mapPercent) / 100f
        return if (config.mode == SplitMode.HORIZONTAL)
            Pair((surfaceWidth * portion).roundToInt().coerceAtLeast(1), surfaceHeight)
        else Pair(surfaceWidth, (surfaceHeight * portion).roundToInt().coerceAtLeast(1))
    }

    private fun mapRegionWidth(): Int = if (config.carDashboardEnabled &&
        config.mode == SplitMode.HORIZONTAL) (surfaceWidth * config.mapPercent / 100f)
            .roundToInt() else surfaceWidth

    private fun mapRegionHeight(): Int = if (config.carDashboardEnabled &&
        config.mode == SplitMode.VERTICAL) (surfaceHeight * config.mapPercent / 100f)
            .roundToInt() else surfaceHeight

    fun updateStreet(name: String) {
        if (name == roadName || name.isBlank() || !active) return
        roadName = name
        streetWidget?.updateBitmap(drawRoad(mapRegionWidth().coerceAtLeast(110)))
    }

    fun updatePosition(loc: Location) {
        if (!active) return
        location = Location(loc)
        speedWidget?.updateBitmap(drawGauge(false))
        limitWidget?.updateBitmap(drawGauge(true))
    }

    fun refresh(force: Boolean = false) {
        if (!active) return
        val latest = SettingsStore(context).load().normalized()
        if (latest != config) {
            try {
                removeWidgets()
                config = latest
                requestPhoto()
                installWidgets()
            } catch (error: Exception) {
                Log.e("CarSurfacePanels", "Failed to rebuild native car widgets", error)
            }
        }
        val now = SystemClock.elapsedRealtime()
        if (force || now - lastDraw > 4500L) {
            val (w, h) = panelDimensions()
            gridWidget?.updateBitmap(drawDashboard(w, h))
            limitWidget?.updateBitmap(drawGauge(true))
            speedWidget?.updateBitmap(drawGauge(false))
            lastDraw = now
        }
    }

    private fun removeWidgets() {
        listOfNotNull(gridWidget, streetWidget, speedWidget, limitWidget)
            .forEach { widget ->
                try { surface.removeWidget(widget) }
                catch (error: RuntimeException) {
                    Log.d("CarSurfacePanels", "Widget already detached", error)
                }
            }
        gridWidget = null; streetWidget = null
        speedWidget = null; limitWidget = null
    }

    /** Must be called while Surface is attached; never create a second display. */
    fun close() {
        if (!active) return
        active = false
        removeWidgets()
        photos.close()
    }

    private fun drawRoad(width: Int): Bitmap {
        val w = width.coerceIn(110, 1920)
        val bitmap = Bitmap.createBitmap(w, 43, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.argb(220, 10, 32, 53)
        canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), 43f), 11f, 11f, p)
        p.textSize = 19f; p.isFakeBoldText = true
        p.color = Color.WHITE
        text(canvas, roadName, 14f, 28f, w - 28f, p)
        return bitmap
    }

    private fun drawGauge(limit: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(68, 84, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val number = if (limit) TrafficSnapshot.currentRoad?.limit?.toString() ?: "—"
            else Telemetry.speedKmh?.toString() ?: "—"
        p.color = Color.argb(120, 0, 0, 0)
        canvas.drawCircle(34f, 37f, 31f, p)
        p.color = if (limit) Color.rgb(224, 42, 39) else Color.rgb(40, 105, 219)
        canvas.drawCircle(34f, 33f, 30f, p)
        p.color = Color.WHITE
        canvas.drawCircle(34f, 33f, 24f, p)
        p.textAlign = Paint.Align.CENTER
        p.color = Color.rgb(20, 33, 48)
        p.isFakeBoldText = true
        p.textSize = if (number.length >= 3) 24f else 30f
        canvas.drawText(number, 34f, 43f, p)
        p.textSize = 11f; p.isFakeBoldText = true
        p.color = Color.WHITE
        canvas.drawText(if (limit) "GIỚI HẠN*" else "km/h", 34f, 76f, p)
        // Asterisk indicates the road match is OSM, not a certified legal sign.
        return bmp
    }

    private fun drawDashboard(w: Int, h: Int): Bitmap {
        val width = w.coerceIn(64, 1920)
        val height = h.coerceIn(64, 1440)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(8, 26, 42)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        val rows = config.rows.coerceIn(1, 3)
        val cols = config.columns.coerceIn(1, 3)
        val heights = ColumnWidths.normalize(config.rowPercents, rows)
        val widths = ColumnWidths.normalize(config.columnPercents, cols)
        val densityText = (width / (cols * 13.0f)).coerceIn(12f, 25f)
        val gap = 3f
        var top = 0f
        for (row in 0 until rows) {
            val bottom = if (row == rows - 1) height.toFloat()
                else top + height * heights[row] / 100f
            var left = 0f
            for (column in 0 until cols) {
                val right = if (column == cols - 1) width.toFloat()
                    else left + width * widths[column] / 100f
                paint.color = Color.rgb(22, 56, 78)
                val tile = RectF(left + gap, top + gap, right - gap, bottom - gap)
                if (tile.width() > 6f && tile.height() > 6f) {
                    canvas.drawRoundRect(tile, 8f, 8f, paint)
                    val index = row * cols + column
                    val widget = config.cells.getOrNull(index) ?: WidgetKind.LUNAR
                    if (widget == WidgetKind.PHOTO && photos.current() != null) {
                        drawPhoto(canvas, tile, photos.current()!!)
                    } else {
                        paint.color = Color.WHITE
                        paint.textSize = densityText.coerceAtMost(tile.height() * 0.21f)
                        paint.isFakeBoldText = true
                        val lines = widgetLines(widget)
                        val lineSpacing = (paint.textSize * 1.4f).coerceAtLeast(12f)
                        val maxLines = ((tile.height() - 12f) / lineSpacing).toInt().coerceAtLeast(1)
                        val startY = tile.centerY() -
                            (minOf(lines.size, maxLines) - 1) * lineSpacing / 2f
                        lines.take(maxLines).forEachIndexed { i, value ->
                            text(canvas, value, tile.left + 9f,
                                startY + i * lineSpacing, tile.width() - 18f, paint)
                        }
                    }
                }
                left = right
            }
            top = bottom
        }
        return bitmap
    }

    private fun drawPhoto(canvas: Canvas, tile: RectF, image: Bitmap) {
        if (image.isRecycled || image.width <= 0 || image.height <= 0) return
        // FIT_CENTER (not CENTER_CROP): preserve the complete photo/QR including
        // its quiet zone. Draw unscaled into a letterboxed area on dark background.
        val scale = kotlin.math.min(tile.width() / image.width,
            tile.height() / image.height)
        val dw = image.width * scale
        val dh = image.height * scale
        val rect = RectF(tile.centerX() - dw / 2, tile.centerY() - dh / 2,
            tile.centerX() + dw / 2, tile.centerY() + dh / 2)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.save()
        canvas.clipRect(tile)
        canvas.drawBitmap(image, null, rect, paint)
        canvas.restore()
    }

    private fun widgetLines(kind: WidgetKind): List<String> = when (kind) {
        WidgetKind.LUNAR -> {
            val now = Calendar.getInstance()
            val lunar = LunarCalendar.solarToLunar(now.get(Calendar.DAY_OF_MONTH),
                now.get(Calendar.MONTH) + 1, now.get(Calendar.YEAR))
            listOf(SimpleDateFormat("dd/MM/yyyy", Locale("vi", "VN")).format(Date()),
                "Âm: ${lunar.day}/${lunar.month}", LunarCalendar.canChi(lunar.year))
        }
        WidgetKind.PHOTO -> listOf("Ảnh tĩnh", "Chọn ảnh trên điện thoại")
        WidgetKind.MEDIA -> listOf("Media", "Điều khiển trên điện thoại")
        WidgetKind.TELEMETRY -> listOf(
            "${Telemetry.speedKmh ?: "—"} km/h",
            "Giới hạn: ${TrafficSnapshot.limitLabel()}",
            "GPS: " + if (Telemetry.gpsAvailable) "OK" else "đang chờ",
            "${"%.1f".format(Locale.US, Telemetry.distanceKm)} km"
        )
    }

    private fun text(c: Canvas, input: String, x: Float, y: Float,
                     maxWidth: Float, p: Paint) {
        if (maxWidth <= 0f) return
        var s = input
        while (s.length > 1 && p.measureText(s) > maxWidth) s = s.dropLast(1)
        if (s != input && s.length > 1) s = s.dropLast(1) + "…"
        c.drawText(s, x, y, p)
    }
}
