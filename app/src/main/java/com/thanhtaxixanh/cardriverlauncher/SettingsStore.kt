package com.thanhtaxixanh.cardriverlauncher

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

// One process, one APK: SharedPreferences data is available both to mobile Activity and CarAppService.
enum class WidgetKind(val label: String) {
    LUNAR("Lịch Âm Dương"), PHOTO("Ảnh / slideshow"), MEDIA("Media"), TELEMETRY("Telemetry")
}
enum class SplitMode(val label: String) {
    HORIZONTAL("Trái / Phải"), VERTICAL("Trên / Dưới")
}
data class LayoutConfig(
    val mode: SplitMode = SplitMode.HORIZONTAL,
    val mapPercent: Int = 65,
    val rows: Int = 2,
    val columns: Int = 2,
    val cells: List<WidgetKind> = listOf(WidgetKind.LUNAR, WidgetKind.TELEMETRY, WidgetKind.MEDIA, WidgetKind.PHOTO),
    val imageUris: List<String> = emptyList(),
    /** Width of each widget column; all rows share the same grid columns. */
    val columnPercents: List<Int> = ColumnWidths.even(2),
    /** Height of each widget row; within a single column this is each widget's height. */
    val rowPercents: List<Int> = ColumnWidths.even(2),
    /** Experimental phone-like layout on Android Auto (may vary by host). */
    val carDashboardEnabled: Boolean = false
) {
    fun normalized(): LayoutConfig = copy(
        mapPercent = mapPercent.coerceIn(30, 80),
        rows = rows.coerceIn(1, 3), columns = columns.coerceIn(1, 3),
        cells = List(rows.coerceIn(1, 3) * columns.coerceIn(1, 3)) { i -> cells.getOrNull(i) ?: WidgetKind.LUNAR },
        columnPercents = ColumnWidths.normalize(columnPercents, columns.coerceIn(1, 3)),
        rowPercents = ColumnWidths.normalize(rowPercents, rows.coerceIn(1, 3))
    )
}

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("car_driver_settings_v1", Context.MODE_PRIVATE)

    fun load(): LayoutConfig = try {
        val j = JSONObject(prefs.getString("layout", "{}") ?: "{}")
        val arr = j.optJSONArray("cells") ?: JSONArray()
        val images = j.optJSONArray("photos") ?: JSONArray()
        LayoutConfig(
            mode = SplitMode.entries.firstOrNull { it.name == j.optString("mode") } ?: SplitMode.HORIZONTAL,
            mapPercent = j.optInt("ratio", 65),
            rows = j.optInt("rows", 2), columns = j.optInt("columns", 2),
            cells = (0 until arr.length()).map { i ->
                WidgetKind.entries.firstOrNull { it.name == arr.optString(i) } ?: WidgetKind.LUNAR
            },
            imageUris = (0 until images.length()).mapNotNull { i -> images.optString(i).takeIf { it.isNotBlank() } },
            columnPercents = j.optJSONArray("columnPercents")?.let { widths ->
                (0 until widths.length()).map { widths.optInt(it, 0) }
            } ?: ColumnWidths.even(j.optInt("columns", 2)),
            rowPercents = j.optJSONArray("rowPercents")?.let { heights ->
                (0 until heights.length()).map { heights.optInt(it, 0) }
            } ?: ColumnWidths.even(j.optInt("rows", 2)),
            carDashboardEnabled = j.optBoolean("carDashboardEnabled", false)
        ).normalized()
    } catch (_: Exception) { LayoutConfig() }

    fun save(config: LayoutConfig) {
        val c = config.normalized()
        val obj = JSONObject().apply {
            put("mode", c.mode.name); put("ratio", c.mapPercent)
            put("rows", c.rows); put("columns", c.columns)
            put("cells", JSONArray(c.cells.map { it.name }))
            put("photos", JSONArray(c.imageUris))
            put("columnPercents", JSONArray(c.columnPercents))
            put("rowPercents", JSONArray(c.rowPercents))
            put("carDashboardEnabled", c.carDashboardEnabled)
        }
        // Small configuration: synchronous commit prevents lost last slider setting on
        // process death or a rapid handoff from the phone to Android Auto.
        prefs.edit().putString("layout", obj.toString()).commit()
    }

    fun appendImages(uriStrings: List<String>) {
        val old = load()
        save(old.copy(imageUris = (old.imageUris + uriStrings).distinct().take(30)))
    }
}

data class RoadSign(val latitude: Double, val longitude: Double, val speedLimit: Int?, val danger: Boolean)

class RoadSignsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("car_driver_signs", Context.MODE_PRIVATE)
    fun all(): List<RoadSign> = try {
        val a = JSONArray(prefs.getString("signs", "[]"))
        (0 until a.length()).mapNotNull { i ->
            val j = a.getJSONObject(i)
            val lat = j.getDouble("lat"); val lng = j.getDouble("lng")
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) null else
                RoadSign(lat, lng, j.optInt("limit", 0).takeIf { it > 0 }, j.optBoolean("danger"))
        }
    } catch (_: Exception) { emptyList() }
    fun add(sign: RoadSign) {
        val all = all().takeLast(199) + sign
        val arr = JSONArray()
        all.forEach { s -> arr.put(JSONObject().put("lat", s.latitude).put("lng", s.longitude)
            .put("limit", s.speedLimit ?: 0).put("danger", s.danger)) }
        prefs.edit().putString("signs", arr.toString()).apply()
    }
    fun clear() { prefs.edit().remove("signs").apply() }
}
