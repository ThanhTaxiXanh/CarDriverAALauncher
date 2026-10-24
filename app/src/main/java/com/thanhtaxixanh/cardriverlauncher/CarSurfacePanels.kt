package com.thanhtaxixanh.cardriverlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.location.Location
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
 * Native screen-space Mapbox widgets on Android Auto. No VirtualDisplay.
 * The widget portion follows the phone's split/rows/columns percentages.
 * Rendering is read-only, with photos, QR codes and interactive media hidden.
 *
 * This is a prototype overlay within the host-provided Surface. It is NOT a
 * replacement for the host's navigation templates or a guarantee of approval.
 */
@OptIn(MapboxExperimental::class)
internal class CarSurfacePanels(
    private val context: Context,
    private val car: MapboxCarMapSurface
) {
    private val surface: MapSurface = car.mapSurface
    private val surfaceWidth = car.surfaceContainer.width
    private val surfaceHeight = car.surfaceContainer.height
    private var config = SettingsStore(context).load().normalized()
    private var gridWidget: BitmapWidget? = null
    private var streetWidget: BitmapWidget? = null
    private var arrowWidget: BitmapWidget? = null
    private var roadName: String = "Đang xác định tên đường"
    private var location: Location? = null
    private var lastDraw = 0L

    fun attach() {
        if (surfaceWidth < 160 || surfaceHeight < 140) return
        installWidgets()
    }

    private fun installWidgets() {
        if (config.carDashboardEnabled) {
            val (width, height) = panelDimensions()
            if (width > 0 && height > 0) {
                val position = if (config.mode == SplitMode.HORIZONTAL)
                    widgetPosition(WidgetPosition.Horizontal.RIGHT, WidgetPosition.Vertical.TOP)
                else widgetPosition(WidgetPosition.Horizontal.LEFT, WidgetPosition.Vertical.BOTTOM)
                gridWidget = BitmapWidget(drawDashboard(width, height), position).also {
                    surface.addWidget(it)
                }
            }
        }
        val labelWidth = mapRegionWidth().coerceAtLeast(110)
        val labelTop = if (config.carDashboardEnabled && config.mode == SplitMode.VERTICAL)
            mapRegionHeight() - 54f else surfaceHeight - 68f
        streetWidget = BitmapWidget(drawRoad(labelWidth), widgetPosition(
            WidgetPosition.Horizontal.LEFT, WidgetPosition.Vertical.TOP, 8f,
            labelTop.coerceAtLeast(8f))).also { surface.addWidget(it) }

        arrowWidget = BitmapWidget(drawArrow(location != null), widgetPosition(
            WidgetPosition.Horizontal.LEFT, WidgetPosition.Vertical.TOP,
            (mapRegionWidth() / 2f - 44f).coerceAtLeast(8f),
            (mapRegionHeight() * 0.67f).coerceAtLeast(35f)
        )).also { surface.addWidget(it) }
    }

    private fun widgetPosition(
        h: WidgetPosition.Horizontal, v: WidgetPosition.Vertical,
        x: Float = 0f, y: Float = 0f
    ): WidgetPosition = WidgetPosition.Builder()
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
        if (name == roadName || name.isBlank()) return
        roadName = name
        streetWidget?.updateBitmap(drawRoad(mapRegionWidth().coerceAtLeast(110)))
    }

    fun updatePosition(loc: Location) {
        location = Location(loc)
        arrowWidget?.updateBitmap(drawArrow(true))
    }

    fun refresh(force: Boolean = false) {
        val latest = SettingsStore(context).load().normalized()
        if (latest != config) {
            try {
                listOfNotNull(gridWidget, streetWidget, arrowWidget).forEach(surface::removeWidget)
                gridWidget = null; streetWidget = null; arrowWidget = null
                config = latest
                installWidgets()
            } catch (error: Exception) {
                Log.e("CarSurfacePanels", "Failed to rebuild car panels", error)
            }
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (force || now - lastDraw > 4500L) {
            gridWidget?.updateBitmap(drawDashboard(panelDimensions().first, panelDimensions().second))
            lastDraw = now
        }
    }

    private fun drawRoad(width: Int): Bitmap {
        val w = width.coerceAtLeast(110)
        val bitmap = Bitmap.createBitmap(w, 42, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.argb(215, 10, 32, 53)
        canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), 42f), 12f, 12f, paint)
        paint.textSize = 20f
        paint.isFakeBoldText = true
        paint.color = Color.WHITE
        text(canvas, roadName, 14f, 28f, w - 30f, paint)
        return bitmap
    }

    /** Draw a screen-space heading cue, not a GPS-matched lane selection. */
    private fun drawArrow(visible: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(88, 95, Bitmap.Config.ARGB_8888)
        if (!visible) return bitmap
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val body = Path().apply {
            moveTo(44f, 3f); lineTo(82f, 79f); lineTo(53f, 63f)
            lineTo(44f, 74f); lineTo(35f, 63f); lineTo(6f, 79f); close()
        }
        paint.color = Color.argb(115, 0, 0, 0)
        canvas.save(); canvas.translate(0f, 7f); canvas.drawPath(body, paint); canvas.restore()
        paint.shader = LinearGradient(15f, 10f, 75f, 79f,
            Color.rgb(28, 203, 253), Color.rgb(16, 65, 220), Shader.TileMode.CLAMP)
        canvas.drawPath(body, paint)
        paint.shader = null; paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f
        canvas.drawPath(body, paint)
        return bitmap
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
            val bottom = if (row == rows - 1) height.toFloat() else top + height * heights[row] / 100f
            var left = 0f
            for (column in 0 until cols) {
                val right = if (column == cols - 1) width.toFloat() else left + width * widths[column] / 100f
                paint.color = Color.rgb(22, 56, 78)
                val tile = RectF(left + gap, top + gap, right - gap, bottom - gap)
                if (tile.width() > 6f && tile.height() > 6f) {
                    canvas.drawRoundRect(tile, 8f, 8f, paint)
                    val index = row * cols + column
                    val widget = config.cells.getOrNull(index) ?: WidgetKind.LUNAR
                    paint.color = Color.WHITE
                    paint.textSize = densityText.coerceAtMost(tile.height() * 0.21f)
                    paint.isFakeBoldText = true
                    val padding = 9f
                    val lines = widgetLines(widget)
                    val lineSpacing = (paint.textSize * 1.4f).coerceAtLeast(12f)
                    val maxLines = ((tile.height() - 12f) / lineSpacing).toInt().coerceAtLeast(1)
                    val startY = tile.centerY() - (minOf(lines.size, maxLines) - 1) * lineSpacing / 2f
                    lines.take(maxLines).forEachIndexed { i, value ->
                        text(canvas, value, tile.left + padding,
                            startY + i * lineSpacing, tile.width() - padding * 2, paint)
                    }
                }
                left = right
            }
            top = bottom
        }
        return bitmap
    }

    private fun widgetLines(kind: WidgetKind): List<String> = when (kind) {
        WidgetKind.LUNAR -> {
            val now = Calendar.getInstance()
            val lunar = LunarCalendar.solarToLunar(now.get(Calendar.DAY_OF_MONTH),
                now.get(Calendar.MONTH) + 1, now.get(Calendar.YEAR))
            listOf(SimpleDateFormat("dd/MM/yyyy", Locale("vi", "VN")).format(Date()),
                "Âm: ${lunar.day}/${lunar.month}",
                LunarCalendar.canChi(lunar.year))
        }
        WidgetKind.PHOTO -> listOf("Ảnh / QR", "Chỉ xem trên điện thoại")
        WidgetKind.MEDIA -> listOf("Media", "Điều khiển trên điện thoại")
        WidgetKind.TELEMETRY -> listOf(
            "${Telemetry.speedKmh ?: "—"} km/h",
            "GPS: " + if (Telemetry.gpsAvailable) "OK" else "đang chờ",
            "${"%.1f".format(Locale.US, Telemetry.distanceKm)} km"
        )
    }

    private fun text(c: Canvas, input: String, x: Float, y: Float, maxWidth: Float, p: Paint) {
        if (maxWidth <= 0f) return
        var s = input
        while (s.length > 1 && p.measureText(s) > maxWidth) s = s.dropLast(1)
        if (s != input && s.length > 1) s = s.dropLast(1) + "…"
        c.drawText(s, x, y, p)
    }
}
