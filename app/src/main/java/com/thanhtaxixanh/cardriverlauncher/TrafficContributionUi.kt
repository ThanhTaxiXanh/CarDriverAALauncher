package com.thanhtaxixanh.cardriverlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.UUID

/**
 * Reports are collected on the PHONE while PARKED, saved locally as unverified.
 * The driver explicitly reviews and shares GPS coordinates; nothing is uploaded
 * automatically. They do not override real speed limits or map-matching results.
 */
class TrafficContributionUi(private val activity: Activity, private val changed: () -> Unit) {
    private data class Choice(val label: String, val code: String)
    private val kinds = listOf(
        Choice("Tốc độ giới hạn", "SPEED"),
        Choice("Biển báo giao thông", "SIGN"),
        Choice("Camera", "CAMERA"),
        Choice("Dịch vụ / Trạm", "SERVICE"),
        Choice("Thông tin làn đường", "LANE")
    )
    private val signCodes = listOf(
        Choice("Vào khu dân cư", "urban_start"),
        Choice("Ra khỏi khu dân cư", "urban_end"),
        Choice("Cấm vượt", "no_overtaking"),
        Choice("Hết cấm vượt", "end_no_overtaking"),
        Choice("Cấm rẽ trái", "no_left_turn"),
        Choice("Cấm rẽ phải", "no_right_turn"),
        Choice("Cấm quay đầu", "no_u_turn"),
        Choice("Cấm rẽ trái và quay đầu", "no_left_u_turn"),
        Choice("Cấm rẽ phải và quay đầu", "no_right_u_turn"),
        Choice("Cấm ô tô", "no_cars"),
        Choice("Cấm xe tải", "no_trucks"),
        Choice("Cấm xe máy", "no_motorcycles"),
        Choice("Đường cấm / Cấm đi ngược chiều", "no_entry"),
        Choice("Cấm dừng và đỗ", "no_stopping"),
        Choice("Cấm đỗ", "no_parking"),
        Choice("Hạn chế chiều cao", "maxheight"),
        Choice("Hạn chế chiều rộng", "maxwidth"),
        Choice("Hạn chế trọng lượng", "maxweight"),
        Choice("Cảnh báo nguy hiểm", "danger"),
        Choice("Biển khác", "other")
    )
    private val cameraCodes = listOf(
        Choice("Camera phạt nguội", "speed_enforcement"),
        Choice("Camera đèn đỏ", "red_light"),
        Choice("Camera AI (do người báo)", "ai_reported"),
        Choice("Camera bắn tốc độ", "speed_camera"),
        Choice("Camera giám sát khác", "other")
    )
    private val services = listOf(
        Choice("Trạm dừng nghỉ", "rest_area"),
        Choice("Trạm thu phí", "toll_booth")
    )
    private val laneCodes = listOf(
        Choice("Thông tin làn / hướng đi", "lane_guidance"),
        Choice("Làn cấm ô tô", "lane_no_cars"),
        Choice("Làn rẽ trái", "lane_left"),
        Choice("Làn đi thẳng", "lane_straight"),
        Choice("Làn rẽ phải", "lane_right"),
        Choice("Làn hỗn hợp", "lane_mixed"),
        Choice("Làn khác", "other")
    )
    private val speedCodes = listOf(
        Choice("Biển giới hạn tốc độ", "maxspeed"),
        Choice("Hết giới hạn tốc độ", "end_maxspeed")
    )
    private val lanes = listOf(
        Choice("Không rõ làn", "UNKNOWN"),
        Choice("Tất cả các làn", "ALL"),
        Choice("Làn trái", "LEFT"),
        Choice("Làn giữa", "CENTER"),
        Choice("Làn phải", "RIGHT")
    )
    private val sides = listOf(
        Choice("Không rõ phía lắp biển", "UNKNOWN"),
        Choice("Lề phải (theo chiều xe)", "RIGHT"),
        Choice("Lề trái (theo chiều xe)", "LEFT"),
        Choice("Trên cao / giá long môn", "OVERHEAD")
    )
    private val directionChoices = listOf(
        Choice("Chiều xe đang đi (GPS)", "CURRENT"),
        Choice("Nhập góc chiều xe (0–359°)", "MANUAL"),
        Choice("Cả hai chiều (chỉ khi chắc chắn)", "BOTH"),
        Choice("Chưa rõ chiều (không tự cảnh báo)", "UNKNOWN")
    )

    private val signImages = TrafficIconAtlas(activity)
    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    /** Screenshot-inspired dark tile grid, phone-only; Android Auto has no forms. */
    private fun tilePicker(title: String, options: List<Choice>, onPick: (Int) -> Unit) {
        val grid = GridLayout(activity).apply {
            columnCount = if(options.size <= 4) 2 else 3
            setPadding(dp(12),dp(12),dp(12),dp(12))
            setBackgroundColor(Color.rgb(15,17,25))
        }
        options.forEachIndexed { index, opt ->
            val emoji = when(opt.code) {
                "SPEED" -> "🚫"
                "SIGN" -> "🚸"
                "CAMERA" -> "📷"
                "SERVICE" -> "⛽"
                "LANE" -> "↗"
                "urban_start","urban_end" -> "🏙️"
                "no_overtaking","end_no_overtaking" -> "🚗"
                "no_left_turn","no_right_turn","no_u_turn",
                "no_left_u_turn","no_right_u_turn" -> "⤴"
                "speed_camera","speed_enforcement","red_light",
                "ai_reported" -> "📸"
                "maxspeed","end_maxspeed" -> "⭕"
                "rest_area" -> "☕"
                "toll_booth" -> "🛣️"
                else -> "⚠️"
            }
            val imageKey = when(opt.code) {
                "SPEED" -> "hud/50"
                "SIGN" -> "alert/residential_area_in"
                "CAMERA" -> "icons/map-pin-camera-type-speed"
                "SERVICE" -> "icons/toll-booth"
                "LANE" -> "lanes/straight"
                else -> (
                    TrafficSignCatalog.signs.firstOrNull { it.code == opt.code }?.imageKey
                    ?: TrafficSignCatalog.cameras.firstOrNull { it.code == opt.code }?.imageKey
                    ?: TrafficSignCatalog.services.firstOrNull { it.code == opt.code }?.imageKey
                    ?: TrafficSignCatalog.lanes.firstOrNull { it.code == opt.code }?.imageKey
                )
            }
            val tile = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(5),dp(5),dp(5),dp(7))
                minimumHeight = dp(112)
                background = GradientDrawable().apply {
                    setColor(Color.rgb(33,37,50))
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1),Color.rgb(75,82,99))
                }
            }
            val picture = signImages.get(imageKey)
            if (picture != null) {
                tile.addView(ImageView(activity).apply {
                    setImageBitmap(picture)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }, LinearLayout.LayoutParams(dp(62),dp(62)))
            } else {
                tile.addView(TextView(activity).apply {
                    text = emoji
                    textSize = 30f
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(-1,dp(61)))
            }
            tile.addView(TextView(activity).apply {
                text = opt.label
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 2
            }, LinearLayout.LayoutParams(-1,dp(43)))
            grid.addView(tile,GridLayout.LayoutParams(
                GridLayout.spec(index / grid.columnCount, 1, 1f),
                GridLayout.spec(index % grid.columnCount, 1, 1f)
            ).apply {
                width=0
                height=dp(112)
                setMargins(dp(3),dp(3),dp(3),dp(3))
            })
            tile.setOnClickListener { onPick(index) }
        }
        val scroll = ScrollView(activity).apply {
            addView(grid)
            isFillViewport = false
        }
        // A reference is required to close the dialog before opening the next.
        pickerDialog?.dismiss()
        pickerDialog = AlertDialog.Builder(activity).setTitle(title)
            .setView(scroll).setNegativeButton("Trở về",null).create()
        pickerDialog?.show()
    }
    private var pickerDialog: AlertDialog? = null

    fun chooseCategory(pinned: TrafficEvent? = null) {
        tilePicker("Đóng góp thông tin giao thông", kinds) { index ->
            val kind = kinds[index]
            pickerDialog?.dismiss()
            if (kind.code == "SPEED") {
                report(kind, pinned = pinned)
            } else {
                val choices = when(kind.code) {
                    "SIGN" -> signCodes
                    "CAMERA" -> cameraCodes
                    "SERVICE" -> services
                    else -> laneCodes
                }
                tilePicker("Đóng góp: ${kind.label}", choices) { subtype ->
                    pickerDialog?.dismiss()
                    report(kind, subtype, pinned)
                }
            }
        }
    }

    private fun spinner(values: List<Choice>): Spinner = Spinner(activity).also { s ->
        s.adapter = ArrayAdapter(activity,
            android.R.layout.simple_spinner_dropdown_item, values.map { it.label })
    }

    private fun report(kind: Choice, initialSubtype: Int = 0,
                       pinned: TrafficEvent? = null) {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(25, 10, 25, 10)
        }
        fun label(t: String) { content.addView(TextView(activity).apply {
            text=t; textSize=15f; setPadding(0,12,0,4)
        }) }
        fun input(value: String, numeric: Boolean = true): EditText = EditText(activity).apply {
            setText(value)
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                else InputType.TYPE_CLASS_TEXT
            content.addView(this, LinearLayout.LayoutParams(-1,-2))
        }

        val subTypes = when(kind.code) {
            "SPEED" -> speedCodes
            "SIGN" -> signCodes
            "CAMERA" -> cameraCodes
            "SERVICE" -> services
            else -> laneCodes
        }
        label("Loại thông tin")
        val subtype = spinner(subTypes)
        content.addView(subtype)
        subtype.setSelection(initialSubtype.coerceIn(0,subTypes.lastIndex))
        val fix = Telemetry.lastLocation
        label("Vĩ độ GPS")
        val lat = input((pinned?.lat ?: fix?.latitude)?.let {
            "%.7f".format(Locale.US,it) } ?: "")
        label("Kinh độ GPS")
        val lon = input((pinned?.lon ?: fix?.longitude)?.let {
            "%.7f".format(Locale.US,it) } ?: "")
        label("Tốc độ ghi trên biển (km/h, nếu có)")
        val speed = input(if(kind.code=="SPEED") "60" else "")
        if (kind.code == "SPEED") {
            label("Chọn nhanh tốc độ trên biển")
            val quickGrid = GridLayout(activity).apply { columnCount = 4 }
            listOf(30,40,50,60,70,80,90,100,120).forEachIndexed { i,value ->
                val btn = TextView(activity).apply {
                    text = value.toString()
                    textSize = 17f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        setColor(Color.rgb(34,45,63))
                        cornerRadius = dp(9).toFloat()
                    }
                    setOnClickListener { speed.setText(value.toString()) }
                }
                quickGrid.addView(btn, GridLayout.LayoutParams(
                    GridLayout.spec(i/4),GridLayout.spec(i%4)).apply {
                    width=dp(68); height=dp(48)
                    setMargins(dp(3),dp(3),dp(3),dp(3))
                })
            }
            content.addView(quickGrid)
        }

        label("Làn đường áp dụng")
        val lane = spinner(lanes)
        content.addView(lane)
        label("Vị trí biển / camera")
        val side = spinner(sides)
        content.addView(side)
        label("Hướng di chuyển được áp dụng")
        val direction = spinner(directionChoices)
        content.addView(direction)
        val userHeading = pinned?.drivingBearing ?:
            fix?.bearing?.toDouble()?.takeIf { fix.hasBearing() }
        val heading = input(userHeading?.let { "%.1f".format(Locale.US,it) } ?: "")
        label("Góc được tính theo CHIỀU xe chạy, không phải chiều ống kính camera hoặc mặt biển.")
        label("Độ chính xác GPS: " + (fix?.accuracy?.let { "%.0f m".format(Locale.US,it) } ?: "chưa rõ") +
            " • Dữ liệu chưa được xác minh.")
        val scroll = ScrollView(activity).apply {
            addView(content)
        }
        AlertDialog.Builder(activity)
            .setTitle("Báo cáo: ${kind.label}")
            .setView(scroll)
            .setPositiveButton("Lưu nháp") { _, _ ->
                val latitude = lat.text.toString().toDoubleOrNull()
                val longitude = lon.text.toString().toDoubleOrNull()
                if(latitude == null || longitude == null ||
                    latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
                    Toast.makeText(activity, "Tọa độ không hợp lệ",Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val speedValue = speed.text.toString().toIntOrNull()
                if(speedValue != null && speedValue !in 5..160) {
                    Toast.makeText(activity, "Tốc độ phải từ 5–160 km/h",Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val directionChoice = directionChoices[direction.selectedItemPosition].code
                val h = heading.text.toString().toDoubleOrNull()
                val travelBearing = when(directionChoice) {
                    "CURRENT", "MANUAL" -> h?.takeIf { it >= 0 && it < 360 }
                    else -> null
                }
                val code = subTypes[subtype.selectedItemPosition].code
                if(directionChoice in setOf("CURRENT","MANUAL") && travelBearing == null) {
                    Toast.makeText(activity, "Thiếu hướng xe 0–359°",Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if(kind.code=="SPEED" && code=="maxspeed" && speedValue==null) {
                    Toast.makeText(activity, "Cần nhập tốc độ trên biển",Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val state = if(directionChoice=="BOTH") "BOTH" else
                    if(directionChoice=="UNKNOWN") "UNKNOWN" else "DIRECTIONAL"
                val report = TrafficEvent(
                    "driver:" + UUID.randomUUID(), latitude, longitude,
                    kind.code, code, speedValue, travelBearing,
                    lanes[lane.selectedItemPosition].code, sides[side.selectedItemPosition].code,
                    "USER_PENDING", false, System.currentTimeMillis()
                )
                try {
                    val database = TrafficDatabase.get(activity)
                    database.addReport(report)
                    if (pinned?.code == "car_bookmark") database.removeCarBookmark(pinned.id)
                    changed()
                    Toast.makeText(activity,
                        "Đã lưu nháp chưa xác minh · hướng: $state",Toast.LENGTH_LONG).show()
                } catch(error: Exception) {
                    Toast.makeText(activity,"Lỗi lưu báo cáo: ${error.message}",Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Hủy",null).show()
    }

    /** Finish a one-tap Android Auto bookmark on the phone when parked. */
    fun reviewCarBookmarks() {
        val points = TrafficDatabase.get(activity).carBookmarks()
        if (points.isEmpty()) {
            Toast.makeText(activity, "Không có điểm chờ báo cáo từ Android Auto",
                Toast.LENGTH_SHORT).show()
            return
        }
        val labels = points.map { point ->
            java.text.SimpleDateFormat("dd/MM HH:mm", Locale("vi","VN"))
                .format(java.util.Date(point.updatedMs)) +
                " · ${"%.5f".format(Locale.US,point.lat)} / ${"%.5f".format(Locale.US,point.lon)}"
        }
        AlertDialog.Builder(activity).setTitle("Điểm đã đánh dấu từ màn hình xe")
            .setMessage("Hãy hoàn tất loại biển báo, hướng xe và làn đường khi đã đỗ xe.")
            .setItems(labels.toTypedArray()) { _, index ->
                chooseCategory(points[index])
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    fun shareReports() {
        val repo = TrafficDatabase.get(activity)
        if(repo.reportCount() == 0) {
            Toast.makeText(activity,"Chưa có báo cáo tự tạo",Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(activity).setTitle("Chia sẻ vị trí GPS?")
            .setMessage("Bản xuất chứa TỌA ĐỘ CHÍNH XÁC, hướng lái, thời gian và làn mà bạn đã nhập. " +
                "Không tải lên tự động. Chỉ chia sẻ sau khi xem xét quyền riêng tư. " +
                "Dữ liệu cộng đồng sẽ được đánh dấu chưa xác minh.")
            .setNegativeButton("Hủy",null)
            .setPositiveButton("Xem ứng dụng chia sẻ") { _, _ ->
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_SUBJECT,"Đóng góp giao thông VN — chưa xác minh")
                    putExtra(Intent.EXTRA_TEXT,repo.exportReports())
                }
                activity.startActivity(Intent.createChooser(intent,"Chọn nơi gửi báo cáo"))
            }.show()
    }
}
