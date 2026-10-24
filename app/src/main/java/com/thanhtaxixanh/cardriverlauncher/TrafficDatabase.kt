package com.thanhtaxixanh.cardriverlauncher

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.location.Location
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Offline regional OSM imports + local driver contributions.
 * BRF target/implicit speeds are deliberately excluded: they are routing/ETA speeds,
 * NOT verified posted speed limits. All imported OSM data is attributed and may be stale.
 */
data class TrafficEvent(
    val id: String, val lat: Double, val lon: Double,
    val type: String, val code: String, val limit: Int?,
    val drivingBearing: Double?, val lane: String, val side: String,
    val source: String, val verified: Boolean, val updatedMs: Long,
    val osmWayId: String? = null
)
data class RoadSpeedMatch(
    val wayId: String, val road: String, val limit: Int?,
    val distanceMeters: Double, val direction: String,
    val source: String = "OpenStreetMap",
    val laneSpecificUnknown: Boolean = false
)

/** Geometry is pure Kotlin for JVM unit tests (no Android GPS/map dependencies). */
object TrafficGeo {
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val d = Math.toRadians(lon2 - lon1)
        val y = sin(d) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * kotlin.math.cos(d)
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0)
    }
    fun angle(a: Double, b: Double): Double = abs((a - b + 540.0) % 360.0 - 180.0)
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dy = Math.toRadians(lat2 - lat1) * 6371000
        val dx = Math.toRadians(lon2 - lon1) * 6371000 * cos(Math.toRadians((lat1 + lat2) / 2))
        return kotlin.math.hypot(dx, dy)
    }
    fun pointSegmentDistance(lat: Double, lon: Double,
                             aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val sx = (bLon - aLon) * 111195.0 * cos(Math.toRadians(lat))
        val sy = (bLat - aLat) * 111195.0
        val px = (lon - aLon) * 111195.0 * cos(Math.toRadians(lat))
        val py = (lat - aLat) * 111195.0
        val denominator = sx * sx + sy * sy
        val t = if (denominator == 0.0) 0.0 else ((px * sx + py * sy) / denominator).coerceIn(0.0, 1.0)
        return kotlin.math.hypot(px - t * sx, py - t * sy)
    }
    /** OSM maxspeed may be km/h (bare), kmh, mph; symbolic values are unknown. */
    fun speed(value: String?): Int? {
        if (value.isNullOrBlank()) return null
        val s = value.trim().lowercase()
        val m = Regex("^(\\d{1,3})(?:\\s*(km/h|kmh|kph|mph))?$").matchEntire(s) ?: return null
        val n = m.groupValues[1].toIntOrNull() ?: return null
        if (n !in 5..160) return null
        return if (m.groupValues[2] == "mph") (n * 1.609344).toInt() else n
    }
    /** All lane values must agree for a lane-agnostic legal limit to be displayed. */
    fun uniformLaneLimit(s: String?): Int? {
        if (s.isNullOrBlank()) return null
        val values = s.split("|").map { speed(it) }
        return values.firstOrNull()?.takeIf { value -> values.all { it == value } }
    }
}

class TrafficDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "traffic_vn_offline_v1.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE roads(
            id TEXT PRIMARY KEY,osm_way_id TEXT NOT NULL,road TEXT NOT NULL,
            a_lat REAL NOT NULL,a_lon REAL NOT NULL,b_lat REAL NOT NULL,b_lon REAL NOT NULL,
            low_lat REAL NOT NULL,high_lat REAL NOT NULL,low_lon REAL NOT NULL,high_lon REAL NOT NULL,
            common_speed INTEGER,forward_speed INTEGER,backward_speed INTEGER,
            forward_lanes TEXT,backward_lanes TEXT,oneway INTEGER NOT NULL,
            source TEXT NOT NULL,updated_ms INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX idx_roads_lat ON roads(low_lat,high_lat)")
        db.execSQL("CREATE INDEX idx_roads_lon ON roads(low_lon,high_lon)")
        db.execSQL("""CREATE TABLE events(
            id TEXT PRIMARY KEY,lat REAL NOT NULL,lon REAL NOT NULL,
            type TEXT NOT NULL,code TEXT NOT NULL,speed INTEGER,
            bearing REAL,lane TEXT NOT NULL,side TEXT NOT NULL,
            source TEXT NOT NULL,verified INTEGER NOT NULL,updated_ms INTEGER NOT NULL,
            osm_way_id TEXT)""")
        db.execSQL("CREATE INDEX idx_events_lat ON events(lat)")
        db.execSQL("CREATE INDEX idx_events_lon ON events(lon)")
        db.execSQL("CREATE INDEX idx_events_source ON events(source)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** Accept either a single VN region JSON or the whole GitHub Artifact ZIP.
     * Decompression is bounded to prevent a malformed ZIP from exhausting memory.
     * Each valid region imports in a SQLite transaction, preserving driver reports.
     */
    fun importPackage(stream: InputStream): Triple<Int,Int,Int> {
        val buffered = java.io.BufferedInputStream(stream)
        buffered.mark(8)
        val header = ByteArray(4)
        val len = buffered.read(header)
        buffered.reset()
        val isZip = len == 4 && header[0] == 0x50.toByte() &&
            header[1] == 0x4B.toByte()
        if (!isZip) {
            val (roads,events) = importRegion(buffered)
            return Triple(1,roads,events)
        }
        var imported = 0
        var roads = 0
        var events = 0
        var aggregate = 0L
        java.util.zip.ZipInputStream(buffered).use { zip ->
            var entries = 0
            while (true) {
                val item = zip.nextEntry ?: break
                entries++
                require(entries <= 2500) { "Gói ZIP có quá nhiều tệp (>2500)." }
                val name = item.name.substringAfterLast('/')
                if (item.isDirectory || !name.startsWith("VN_") || !name.endsWith(".json")) {
                    zip.closeEntry()
                    continue
                }
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(32768)
                var size = 0
                while(true) {
                    val n = zip.read(buffer)
                    if(n < 0) break
                    size += n
                    require(size <= 18 * 1024 * 1024) { "Tệp $name quá lớn." }
                    aggregate += n
                    require(aggregate <= 250L * 1024 * 1024) {
                        "Dung lượng giải nén vượt 250 MB. Chia gói theo tỉnh."
                    }
                    out.write(buffer,0,n)
                }
                val (r,e) = importRegion(java.io.ByteArrayInputStream(out.toByteArray()))
                roads += r
                events += e
                imported++
                zip.closeEntry()
            }
        }
        require(imported > 0) { "ZIP không chứa gói VN_*.json hợp lệ." }
        return Triple(imported,roads,events)
    }

    /**
     * Regional JSON created by scripts/osm_traffic_extract.py.
     * Takes a capped, UTF-8 stream on a background thread. Import is transactional,
     * preserves community contributions, and never supplies guessed speed defaults.
     */
    fun importRegion(stream: InputStream): Pair<Int, Int> {
        val bytes = stream.use { input ->
            val limit = 18 * 1024 * 1024
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16384)
            var size = 0
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                size += n
                require(size <= limit) { "Vùng dữ liệu quá lớn (>18 MB). Hãy chia thành các ô nhỏ." }
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        require(root.optInt("schema") == 1 && root.optString("country") == "VN") {
            "Định dạng dữ liệu giao thông VN không hợp lệ"
        }
        require(root.optString("license") == "ODbL-1.0") { "Thiếu giấy phép OSM ODbL" }
        val roads = root.optJSONArray("roads") ?: JSONArray()
        val points = root.optJSONArray("points") ?: JSONArray()
        val updated = System.currentTimeMillis()
        var segmentsInserted = 0
        var pointsInserted = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (i in 0 until roads.length()) {
                val road = roads.getJSONObject(i)
                val coords = road.optJSONArray("coordinates") ?: continue
                val osmId = road.optString("id")
                val originalOsmId = road.optString("osm_way_id").ifBlank { osmId }
                if (osmId.isBlank() || coords.length() < 2) continue
                val name = road.optString("name").take(140)
                val oneway = when (road.optString("oneway")) {
                    "yes", "true", "1" -> 1
                    "-1" -> -1
                    else -> 0
                }
                val rawCommon = TrafficGeo.speed(road.optString("maxspeed"))
                val forward = TrafficGeo.speed(road.optString("maxspeed:forward"))
                val backward = TrafficGeo.speed(road.optString("maxspeed:backward"))
                val fLane = road.optString("maxspeed:lanes:forward").ifBlank {
                    if (oneway == 1) road.optString("maxspeed:lanes") else ""
                }
                val bLane = road.optString("maxspeed:lanes:backward").ifBlank {
                    if (oneway == -1) road.optString("maxspeed:lanes") else ""
                }
                for (index in 0 until coords.length() - 1) {
                    val a = coords.optJSONArray(index) ?: continue
                    val b = coords.optJSONArray(index + 1) ?: continue
                    if (a.length() < 2 || b.length() < 2) continue
                    val alon = a.optDouble(0); val alat = a.optDouble(1)
                    val blon = b.optDouble(0); val blat = b.optDouble(1)
                    if (!valid(alat, alon) || !valid(blat, blon) || TrafficGeo.distance(alat, alon, blat, blon) < 0.5) continue
                    val values = ContentValues().apply {
                        put("id", "$osmId:$index")
                        put("osm_way_id", originalOsmId)
                        put("road", name)
                        put("a_lat", alat); put("a_lon", alon)
                        put("b_lat", blat); put("b_lon", blon)
                        put("low_lat", min(alat, blat)); put("high_lat", max(alat, blat))
                        put("low_lon", min(alon, blon)); put("high_lon", max(alon, blon))
                        put("common_speed", rawCommon)
                        put("forward_speed", forward)
                        put("backward_speed", backward)
                        put("forward_lanes", fLane); put("backward_lanes", bLane)
                        put("oneway", oneway); put("source", "OSM"); put("updated_ms", updated)
                    }
                    db.insertWithOnConflict("roads", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                    segmentsInserted++
                }
            }
            for (i in 0 until points.length()) {
                val p = points.optJSONObject(i) ?: continue
                val lat = p.optDouble("lat"); val lon = p.optDouble("lon")
                if (!valid(lat, lon)) continue
                val type = p.optString("type").take(32)
                if (type !in setOf("SPEED", "SIGN", "CAMERA", "SERVICE", "LANE")) continue
                val event = TrafficEvent(
                    id = p.optString("id").take(90), lat = lat, lon = lon,
                    type = type, code = p.optString("code").take(80),
                    limit = TrafficGeo.speed(p.optString("maxspeed")),
                    drivingBearing = p.optDouble("driving_bearing").takeIf { !it.isNaN() && it in 0.0..360.0 },
                    lane = p.optString("lane", "UNKNOWN").ifBlank { "UNKNOWN" },
                    side = p.optString("side", "UNKNOWN").ifBlank { "UNKNOWN" },
                    source = "OSM", verified = false, updatedMs = updated,
                    osmWayId = p.optString("osm_way_id").takeIf { it.isNotBlank() }
                )
                if (event.id.isBlank()) continue
                db.insertWithOnConflict("events", null, row(event), SQLiteDatabase.CONFLICT_REPLACE)
                pointsInserted++
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return segmentsInserted to pointsInserted
    }

    /**
     * Returns unknown if no directional OSM value, heading, or unambiguous way.
     * No BRF implicit speed is accepted as an actual restriction.
     */
    fun matchRoad(location: Location): RoadSpeedMatch? {
        if (!location.hasAccuracy() || location.accuracy > 45f ||
            !location.hasBearing() || location.speed < 1.5f) return null
        val radiusM = max(13.0, location.accuracy.toDouble() + 10).coerceAtMost(45.0)
        val latRange = radiusM / 110540.0
        val lonRange = radiusM / (111320.0 * cos(Math.toRadians(location.latitude)).coerceAtLeast(0.2))
        val candidates = mutableListOf<RoadSpeedMatch>()
        readableDatabase.query("roads", arrayOf("osm_way_id","road","a_lat","a_lon",
            "b_lat","b_lon","common_speed","forward_speed","backward_speed",
            "forward_lanes","backward_lanes","oneway"),
            "low_lat<=? AND high_lat>=? AND low_lon<=? AND high_lon>=?",
            arrayOf((location.latitude+latRange).toString(),(location.latitude-latRange).toString(),
                (location.longitude+lonRange).toString(),(location.longitude-lonRange).toString()),
            null,null,null,"180").use { cursor ->
            while (cursor.moveToNext()) {
                val aLat = cursor.getDouble(2); val aLon = cursor.getDouble(3)
                val bLat = cursor.getDouble(4); val bLon = cursor.getDouble(5)
                val distance = TrafficGeo.pointSegmentDistance(location.latitude,location.longitude,aLat,aLon,bLat,bLon)
                if (distance > radiusM) continue
                val wayHeading = TrafficGeo.bearing(aLat,aLon,bLat,bLon)
                val forward = TrafficGeo.angle(wayHeading,location.bearing.toDouble()) <= 55
                val backward = TrafficGeo.angle((wayHeading+180)%360,location.bearing.toDouble()) <= 55
                if (!forward && !backward) continue
                val oneway = cursor.getInt(11)
                if ((forward && oneway == -1) || (backward && oneway == 1)) continue
                val shared = cursor.getIntOrNull(6)
                val directional = cursor.getIntOrNull(if (forward) 7 else 8)
                val rawLanes = cursor.getString(if (forward) 9 else 10)
                val laneLimit = TrafficGeo.uniformLaneLimit(rawLanes)
                val ambiguousLane = rawLanes.isNotEmpty() && laneLimit == null
                val limit = if (ambiguousLane) null else directional ?: laneLimit ?: shared
                candidates.add(RoadSpeedMatch(cursor.getString(0),cursor.getString(1),limit,
                    distance,if (forward) "FORWARD" else "BACKWARD",
                    laneSpecificUnknown = ambiguousLane))
            }
        }
        val ordered = candidates.sortedBy { it.distanceMeters }
        val selected = ordered.firstOrNull() ?: return null
        // Reject parallel ways too close to distinguish with GPS accuracy.
        if (ordered.drop(1).any { it.wayId != selected.wayId &&
                it.distanceMeters - selected.distanceMeters < max(5.0,location.accuracy*0.6) }) return null
        return selected
    }

    fun nearEvents(location: Location, maxMeters: Double = 350.0): List<TrafficEvent> {
        if (location.hasAccuracy() && location.accuracy > 75f) return emptyList()
        val latRange = maxMeters / 110540.0
        val lonRange = maxMeters / (111320.0 * cos(Math.toRadians(location.latitude)).coerceAtLeast(0.2))
        val found = mutableListOf<Pair<Double,TrafficEvent>>()
        readableDatabase.query("events",null,
            "lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
            arrayOf((location.latitude-latRange).toString(),(location.latitude+latRange).toString(),
                (location.longitude-lonRange).toString(),(location.longitude+lonRange).toString()),
            null,null,null,"150").use { cursor ->
            while (cursor.moveToNext()) {
                val event = fromRow(cursor)
                val meters = TrafficGeo.distance(location.latitude,location.longitude,event.lat,event.lon)
                if (meters > maxMeters) continue
                if (event.drivingBearing != null && location.hasBearing() &&
                    TrafficGeo.angle(event.drivingBearing,location.bearing.toDouble()) > 55) continue
                found.add(meters to event)
            }
        }
        return found.sortedBy { it.first }.take(50).map { it.second }
    }

    fun addReport(event: TrafficEvent) {
        require(valid(event.lat,event.lon) && event.source == "USER_PENDING")
        writableDatabase.insertWithOnConflict("events",null,row(event.copy(verified=false)),
            SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun reportCount(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM events WHERE source='USER_PENDING'",null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    fun exportReports(): String {
        val items = JSONArray()
        readableDatabase.query("events",null,"source=?",arrayOf("USER_PENDING"),
            null,null,"updated_ms DESC","1000").use { cursor ->
            while (cursor.moveToNext()) {
                val e = fromRow(cursor)
                items.put(JSONObject().apply {
                    put("id",e.id);put("lat",e.lat);put("lon",e.lon)
                    put("type",e.type);put("code",e.code);put("maxspeed",e.limit)
                    put("driving_bearing",e.drivingBearing);put("lane",e.lane)
                    put("side",e.side);put("osm_way_id",e.osmWayId)
                    put("source",e.source);put("verified",false);put("updated_ms",e.updatedMs)
                })
            }
        }
        return JSONObject().put("schema",1).put("country","VN")
            .put("note","User reports are UNVERIFIED; consent required before sharing exact GPS.")
            .put("reports",items).toString(2)
    }

    private fun row(e: TrafficEvent) = ContentValues().apply {
        put("id",e.id);put("lat",e.lat);put("lon",e.lon);put("type",e.type)
        put("code",e.code);put("speed",e.limit);put("bearing",e.drivingBearing)
        put("lane",e.lane);put("side",e.side);put("source",e.source)
        put("verified",if(e.verified) 1 else 0);put("updated_ms",e.updatedMs)
        put("osm_way_id",e.osmWayId)
    }
    private fun fromRow(c: android.database.Cursor): TrafficEvent {
        fun column(key: String) = c.getColumnIndexOrThrow(key)
        fun string(key: String) = c.getString(column(key)) ?: ""
        return TrafficEvent(string("id"),c.getDouble(column("lat")),c.getDouble(column("lon")),
            string("type"),string("code"),
            c.getIntOrNull(column("speed")),
            if(c.isNull(column("bearing")))null else c.getDouble(column("bearing")),
            string("lane"),string("side"),string("source"),c.getInt(column("verified")) == 1,
            c.getLong(column("updated_ms")),string("osm_way_id").takeIf { it.isNotBlank() })
    }
    private fun android.database.Cursor.getIntOrNull(index: Int): Int? =
        if(isNull(index)) null else getInt(index)
    private fun valid(lat: Double,lon: Double) =
        lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

    companion object {
        @Volatile private var instance: TrafficDatabase? = null
        fun get(context: Context): TrafficDatabase =
            instance ?: synchronized(this) {
                instance ?: TrafficDatabase(context).also { instance = it }
            }
    }
}
