package com.thanhtaxixanh.cardriverlauncher

/**
 * Maps the user's supplied assets.zip (Flutter assets/alert, icons, lanes, hud)
 * to stable traffic report codes. Images are visuals, not georeferenced data.
 */
data class ReportChoice(val code: String, val title: String, val imageKey: String)

object TrafficSignCatalog {
    val speedLimits = listOf(30,40,50,60,70,80,90,100,110,120)
    val signs = listOf(
        ReportChoice("urban_start", "Vào khu dân cư", "alert/residential_area_in"),
        ReportChoice("urban_end", "Ra khỏi khu dân cư", "alert/residential_area_out"),
        ReportChoice("no_overtaking", "Cấm vượt", "alert/no_passing_in"),
        ReportChoice("end_no_overtaking", "Hết cấm vượt", "alert/no_passing_out"),
        ReportChoice("no_left_turn", "Cấm rẽ trái", "alert/no_left"),
        ReportChoice("no_right_turn", "Cấm rẽ phải", "alert/no_right"),
        ReportChoice("no_u_turn", "Cấm quay đầu", "alert/no_uleft"),
        ReportChoice("no_left_u_turn", "Cấm trái/quay đầu", "alert/no_left_uleft"),
        ReportChoice("no_right_u_turn", "Cấm phải/quay đầu", "alert/no_right_uright"),
        ReportChoice("no_parking", "Cấm đỗ xe", "alert/no_park"),
        ReportChoice("no_parking_even", "Cấm đỗ ngày chẵn", "alert/no_park_even"),
        ReportChoice("no_parking_odd", "Cấm đỗ ngày lẻ", "alert/no_park_odd"),
        ReportChoice("no_stopping", "Cấm dừng/đỗ", "alert/no_park_no_stop"),
        ReportChoice("no_cars", "Cấm ô tô", "alert/ban_car"),
        ReportChoice("no_motorcycles", "Cấm mô tô", "alert/ban_motorcycle"),
        ReportChoice("no_straight", "Cấm đi thẳng", "alert/no_straight"),
        ReportChoice("no_straight_left", "Cấm thẳng/rẽ trái", "alert/no_straight_left"),
        ReportChoice("no_straight_right", "Cấm thẳng/rẽ phải", "alert/no_straight_right"),
        ReportChoice("no_left_right_turn", "Cấm rẽ trái/phải", "alert/no_left_right"),
        ReportChoice("mandatory_straight", "Chỉ đi thẳng", "alert/only_straight"),
        ReportChoice("mandatory_straight_left", "Thẳng/rẽ trái", "alert/only_straight_left"),
        ReportChoice("mandatory_straight_right", "Thẳng/rẽ phải", "alert/only_straight_right"),
        ReportChoice("mandatory_left_right", "Chỉ rẽ trái/phải", "alert/only_left_right"),
        ReportChoice("mandatory_left", "Chỉ rẽ trái", "alert/only_left"),
        ReportChoice("mandatory_right", "Chỉ rẽ phải", "alert/only_right"),
        ReportChoice("railway", "Đường sắt", "alert/railway"),
        ReportChoice("tunnel", "Hầm đường bộ", "alert/tunnel"),
        ReportChoice("distance_safe", "Giữ khoảng cách", "alert/distance_safe"),
        ReportChoice("other", "Biển khác", "icons/warning")
    )
    val cameras = listOf(
        ReportChoice("speed_camera", "Bắn tốc độ", "icons/map-pin-camera-type-speed"),
        ReportChoice("red_light", "Đèn đỏ", "icons/map-pin-camera-type-red-light"),
        ReportChoice("ai_reported", "Camera AI", "icons/map-pin-camera-type-ai"),
        ReportChoice("speed_enforcement", "Phạt nguội", "icons/map-pin-camera-type-combo-general"),
        ReportChoice("other", "Camera khác", "icons/map-pin-camera-type-dummy")
    )
    val services = listOf(
        ReportChoice("rest_area", "Trạm nghỉ", "icons/stopandrest"),
        ReportChoice("toll_booth", "Thu phí", "icons/toll-booth")
    )
    val lanes = listOf(
        ReportChoice("lane_left", "Làn rẽ trái", "lanes/left"),
        ReportChoice("lane_right", "Làn rẽ phải", "lanes/right"),
        ReportChoice("lane_straight", "Làn đi thẳng", "lanes/straight"),
        ReportChoice("lane_mixed", "Thẳng / trái / phải", "lanes/straight_left_right"),
        ReportChoice("lane_left_right", "Trái / phải", "lanes/left_right"),
        ReportChoice("lane_straight_left", "Thẳng / trái", "lanes/straight_left"),
        ReportChoice("lane_straight_right", "Thẳng / phải", "lanes/straight_right"),
        ReportChoice("lane_slightleft", "Rẽ trái nhẹ", "lanes/slightleft"),
        ReportChoice("lane_slightright", "Rẽ phải nhẹ", "lanes/slightright"),
        ReportChoice("lane_uleft", "Quay đầu trái", "lanes/uleft"),
        ReportChoice("lane_left_uleft", "Trái / quay đầu", "lanes/left_uleft"),
        ReportChoice("lane_right_uright", "Phải / quay đầu", "lanes/right_uright"),
        ReportChoice("lane_straight_uleft", "Thẳng / quay đầu", "lanes/straight_uleft"),
        ReportChoice("lane_straight_uright", "Thẳng / quay phải", "lanes/straight_uright"),
        ReportChoice("lane_straight_slightright", "Thẳng / chếch phải", "lanes/straight_slightright"),
        ReportChoice("lane_straight_slightleft", "Thẳng / chếch trái", "lanes/straight_slightleft"),
        ReportChoice("lane_straight_left_uleft", "Thẳng / trái / quay", "lanes/straight_left_uleft"),
        ReportChoice("lane_straight_right_uleft", "Thẳng / phải / quay", "lanes/straight_right_uleft"),
        ReportChoice("lane_straight_left_right_uleft", "Đa hướng / quay trái", "lanes/straight_left_right_uleft")
    )
    fun imageKey(type: String, code: String, speed: Int? = null): String? =
        if (type == "SPEED" && speed != null)
            "hud/$speed".takeIf { speed in speedLimits }
        else when(type) {
            "SIGN" -> signs.firstOrNull { it.code == code }?.imageKey
            "CAMERA" -> cameras.firstOrNull { it.code == code }?.imageKey
            "SERVICE" -> services.firstOrNull { it.code == code }?.imageKey
            "LANE" -> lanes.firstOrNull { it.code == code }?.imageKey
            else -> null
        }
}
