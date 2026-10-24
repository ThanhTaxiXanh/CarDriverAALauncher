package com.thanhtaxixanh.cardriverlauncher

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** Phone-only layout editor. Android Auto never launches this Activity on the car. */
class MainActivity : ComponentActivity() {
    private val store by lazy { SettingsStore(this) }
    private var draft = LayoutConfig()
    private lateinit var body: LinearLayout
    private lateinit var previewArea: FrameLayout
    private lateinit var gridEditor: LinearLayout
    private lateinit var columnEditor: LinearLayout
    private lateinit var rowEditor: LinearLayout
    private var preview: DashboardView? = null
    private var isVisible = false
    private var suppressEvents = false
    private var previewEnabled = false
    private val regionPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            Thread {
                try {
                    val input = contentResolver.openInputStream(uri)
                        ?: error("Không đọc được dữ liệu vùng")
                    val (roads, points) = TrafficDatabase.get(this).importRegion(input)
                    runOnUiThread {
                        Toast.makeText(this,
                            "Đã nhập $roads đoạn đường và $points biển báo/camera từ OpenStreetMap",
                            Toast.LENGTH_LONG).show()
                        if (previewEnabled) renderPreview()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this,"Lỗi nhập dữ liệu: ${e.message}",Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        draft = store.load()
        buildUi()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 100)
    }
    private fun buildUi() {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(18, 16, 18, 24)
            setBackgroundColor(0xFFF5F7FA.toInt())
        }
        scroll.addView(body)
        setContentView(scroll)
        body.addView(title("CarDriverLauncher", 25f))
        body.addView(note("Cấu hình trên điện thoại · Bản xem trước split-map và widget"))
        body.addView(title("Bố cục", 18f))
        val betaCarLayout = CheckBox(this).apply {
            text = "Hiển thị Widget trên Android Auto bằng Mapbox (Beta)"
            isChecked = draft.carDashboardEnabled
            setOnCheckedChangeListener { _, enabled ->
                draft = draft.copy(carDashboardEnabled = enabled)
                store.save(draft)
            }
        }
        body.addView(betaCarLayout)
        body.addView(note("Bật: vẽ bảng Widget theo tỷ lệ đã lưu trực tiếp trong Mapbox Surface của xe, " +
            "không dùng màn hình ảo. Ảnh, mã QR và nút media không hiển thị khi lái xe. " +
            "Tỷ lệ thực tế còn phụ thuộc vùng hiển thị mà Android Auto cấp."))

        val modeSpinner = spinner(SplitMode.entries.map { it.label })
        modeSpinner.setSelection(draft.mode.ordinal)
        modeSpinner.onItemSelectedListener = changeListener { position ->
            draft = draft.copy(mode = SplitMode.entries[position]); saveAndPreview()
        }
        body.addView(labeled("Kiểu chia", modeSpinner))
        val ratio = TextView(this)
        body.addView(ratio)
        val seek = SeekBar(this).apply { max = 50; progress = draft.mapPercent - 30 }
        ratio.text = "Bản đồ ${draft.mapPercent}% · Widget ${100 - draft.mapPercent}%"
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                draft = draft.copy(mapPercent = p + 30)
                ratio.text = "Bản đồ ${draft.mapPercent}% · Widget ${100 - draft.mapPercent}%"
            }
            override fun onStartTrackingTouch(s: SeekBar?) = Unit
            override fun onStopTrackingTouch(s: SeekBar?) { saveAndPreview() }
        })
        body.addView(seek)
        val split = LinearLayout(this)
        val rows = spinner((1..3).map { "$it hàng" })
        val columns = spinner((1..3).map { "$it cột" })
        rows.setSelection(draft.rows - 1); columns.setSelection(draft.columns - 1)
        rows.onItemSelectedListener = changeListener { pos ->
            draft = draft.copy(
                rows = pos + 1,
                rowPercents = ColumnWidths.even(pos + 1)
            ).normalized()
            rebuildCells()
            rebuildRowEditor()
            saveAndPreview()
        }
        columns.onItemSelectedListener = changeListener { pos ->
            // A column count change starts with a balanced layout.
            draft = draft.copy(
                columns = pos + 1,
                columnPercents = ColumnWidths.even(pos + 1)
            ).normalized()
            rebuildCells()
            rebuildColumnEditor()
            rebuildRowEditor()
            saveAndPreview()
        }
        split.addView(rows, LinearLayout.LayoutParams(0, -2, 1f))
        split.addView(columns, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(labeled("Grid (1–3 hàng × 1–3 cột)", split))
        body.addView(title("Tỷ lệ từng cột Widget (%)", 18f))
        body.addView(note("Kéo thanh trượt để đổi độ rộng. Tổng các cột luôn là 100%; áp dụng cho mọi hàng."))
        columnEditor = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(columnEditor)
        rebuildColumnEditor()
        gridEditor = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(gridEditor)
        rebuildCells()
        body.addView(title("Tỷ lệ chiều cao từng ô / hàng (%)", 18f))
        body.addView(note("Với 1 cột: chỉnh trực tiếp chiều cao từng ô đã chọn bên trên. " +
            "Với nhiều cột: chỉnh chiều cao của cả hàng. Tổng chiều cao luôn 100%."))
        rowEditor = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(rowEditor)
        rebuildRowEditor()
        body.addView(button("Thêm ảnh từ điện thoại") { choosePhotos() })
        body.addView(button("Cấp quyền điều khiển media") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        body.addView(title("Dữ liệu giao thông Việt Nam (OpenStreetMap)", 18f))
        body.addView(note("Tốc độ pháp lý chỉ lấy từ thẻ maxspeed thực tế theo chiều đường; " +
            "tốc độ ước tính của BRF chỉ dùng tính đường/ETA. Biển báo và camera OSM " +
            "có thể thiếu hoặc lỗi thời. © OpenStreetMap contributors (ODbL)."))
        body.addView(button("Nhập gói dữ liệu giao thông theo khu vực (JSON)") {
            regionPicker.launch(arrayOf("application/json", "*/*"))
        })
        body.addView(note("Tệp JSON tạo bằng GitHub Actions · Update Vietnam Traffic OSM. " +
            "Mỗi khu vực được tải từng gói để tránh quá tải và nhập ngoại tuyến."))
        body.addView(title("Đóng góp thông tin giao thông theo GPS", 18f))
        body.addView(button("Báo cáo tốc độ / biển báo / camera / dịch vụ / làn đường") {
            TrafficContributionUi(this) { if (previewEnabled) renderPreview() }.chooseCategory()
        })
        body.addView(button("Xuất / chia sẻ báo cáo chưa xác minh") {
            TrafficContributionUi(this) {}.shareReports()
        })
        body.addView(note("Chỉ thao tác khi xe đang đỗ. Báo cáo lưu trên điện thoại, " +
            "không tự đăng tải và không ghi đè giới hạn tốc độ OSM. " +
            "Lựa chọn hướng xe, làn áp dụng, vị trí biển và độ chính xác GPS trước khi lưu."))
        body.addView(button("Gắn biển báo đơn giản (tương thích dữ liệu cũ)") { addSignDialog() })
        body.addView(button("Xóa dữ liệu biển báo thủ công") {
            AlertDialog.Builder(this).setMessage("Xóa toàn bộ điểm biển báo đã nhập?")
                .setPositiveButton("Xóa") { _, _ -> RoadSignsStore(this).clear(); renderPreview() }
                .setNegativeButton("Hủy", null).show()
        })
        body.addView(note("Chưa xác minh: ký hiệu dấu hỏi. Biển báo thực tế trên đường " +
            "luôn có ưu tiên cao hơn thông tin đóng góp hoặc dữ liệu ứng dụng."))
        body.addView(title("Xem trước trên điện thoại", 18f))
        body.addView(button("Bật / cập nhật bản xem trước 3D (Mapbox)") {
            previewEnabled = true
            renderPreview()
        })
        body.addView(note("Bản đồ 3D chỉ khởi tạo khi nhấn nút, tránh lỗi SDK làm thoát màn hình cấu hình."))
        val previousCrash = getSharedPreferences("car_driver_diagnostics", MODE_PRIVATE)
            .getString("last_crash", null)
        if (!previousCrash.isNullOrBlank()) {
            body.addView(note("Có bản ghi lỗi lần trước trên thiết bị. Có thể sao chép để phân tích."))
            body.addView(button("Sao chép log lỗi lần trước") {
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                    "CarDriverLauncher crash", previousCrash
                ))
                Toast.makeText(this, "Đã sao chép log lỗi", Toast.LENGTH_SHORT).show()
            })
        }
        previewArea = FrameLayout(this).apply { setBackgroundColor(Color.DKGRAY) }
        body.addView(previewArea, LinearLayout.LayoutParams(-1, dp(370)))
        body.addView(note("Trong Android Auto, host có thể che vùng Surface. Bản preview không cam kết bố cục chính xác trên màn hình xe."))
        suppressEvents = false
        renderPreview()
    }
    /** Updates the horizontal proportions without regenerating Mapbox on every slider tick. */
    private fun rebuildColumnEditor() {
        if (!::columnEditor.isInitialized) return
        columnEditor.removeAllViews()
        val cfg = draft.normalized()
        draft = cfg

        val summary = note("Tổng: 100% • " + cfg.columnPercents.joinToString(" / ") { "$it%" })
        columnEditor.addView(summary)

        for (index in 0 until cfg.columns) {
            val label = note("Cột ${index + 1}: ${cfg.columnPercents[index]}%")
            columnEditor.addView(label)
            if (cfg.columns == 1) continue

            val seek = SeekBar(this).apply {
                min = ColumnWidths.MIN_PERCENT
                max = 100 - ColumnWidths.MIN_PERCENT * (cfg.columns - 1)
                progress = cfg.columnPercents[index]
            }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    draft = draft.copy(
                        columnPercents = ColumnWidths.update(draft.columnPercents, index, progress)
                    )
                    label.text = "Cột ${index + 1}: ${draft.columnPercents[index]}%"
                    summary.text = "Tổng: 100% • " +
                        draft.columnPercents.joinToString(" / ") { "$it%" }
                    preview?.updateGridRatios(draft.rowPercents, draft.columnPercents)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    saveGridRatios()
                    // Update other slider positions after redistributing their percentages.
                    rebuildColumnEditor()
                }
            })
            columnEditor.addView(seek)
        }

        val presets: List<List<Int>> = when (cfg.columns) {
            2 -> listOf(listOf(50, 50), listOf(40, 60), listOf(60, 40))
            3 -> listOf(listOf(25, 50, 25), listOf(30, 40, 30), listOf(20, 60, 20))
            else -> emptyList()
        }
        if (presets.isNotEmpty()) {
            columnEditor.addView(note("Tỷ lệ mẫu:"))
            val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            presets.forEach { preset ->
                val presetButton = button(preset.joinToString("/") + "%") {
                    draft = draft.copy(columnPercents = preset).normalized()
                    rebuildColumnEditor()
                    saveGridRatios()
                }
                presetButton.textSize = 12f
                presetRow.addView(presetButton, LinearLayout.LayoutParams(0, -2, 1f))
            }
            columnEditor.addView(presetRow)
        }
    }

    /**
     * A 2-row, 1-column layout has one independent height slider for each
     * highlighted widget (row 1 / row 2). All rows always total 100%.
     */
    private fun rebuildRowEditor() {
        if (!::rowEditor.isInitialized) return
        rowEditor.removeAllViews()
        val cfg = draft.normalized()
        draft = cfg

        val summary = note("Chiều cao: " + cfg.rowPercents.joinToString(" / ") { "$it%" } +
            "  •  Tổng: 100%")
        rowEditor.addView(summary)

        for (index in 0 until cfg.rows) {
            val labelPrefix = if (cfg.columns == 1) "Ô số ${index + 1}"
                else "Hàng ${index + 1} (ô ${index * cfg.columns + 1}–${(index + 1) * cfg.columns})"
            val label = note("$labelPrefix: ${cfg.rowPercents[index]}%")
            rowEditor.addView(label)
            if (cfg.rows == 1) continue

            val seek = SeekBar(this).apply {
                min = ColumnWidths.MIN_PERCENT
                max = 100 - ColumnWidths.MIN_PERCENT * (cfg.rows - 1)
                progress = cfg.rowPercents[index]
            }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    draft = draft.copy(
                        rowPercents = ColumnWidths.update(draft.rowPercents, index, progress)
                    )
                    label.text = "$labelPrefix: ${draft.rowPercents[index]}%"
                    summary.text = "Chiều cao: " +
                        draft.rowPercents.joinToString(" / ") { "$it%" } + "  •  Tổng: 100%"
                    preview?.updateGridRatios(draft.rowPercents, draft.columnPercents)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    saveGridRatios()
                    rebuildRowEditor()
                }
            })
            rowEditor.addView(seek)
        }

        val presets: List<List<Int>> = when (cfg.rows) {
            2 -> listOf(listOf(50, 50), listOf(30, 70), listOf(70, 30),
                listOf(25, 75), listOf(75, 25))
            3 -> listOf(listOf(25, 50, 25), listOf(30, 40, 30), listOf(20, 60, 20))
            else -> emptyList()
        }
        if (presets.isNotEmpty()) {
            rowEditor.addView(note("Chiều cao mẫu:"))
            // Keep buttons small enough to use without horizontal overflow on phones.
            presets.chunked(3).forEach { group ->
                val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                group.forEach { preset ->
                    val presetButton = button(preset.joinToString("/") + "%") {
                        draft = draft.copy(rowPercents = preset).normalized()
                        rebuildRowEditor()
                        saveGridRatios()
                    }
                    presetButton.textSize = 12f
                    presetRow.addView(presetButton, LinearLayout.LayoutParams(0, -2, 1f))
                }
                rowEditor.addView(presetRow)
            }
        }
    }

    private fun rebuildCells() {
        if (!::gridEditor.isInitialized) return
        gridEditor.removeAllViews()
        val cfg = draft.normalized()
        draft = cfg
        repeat(cfg.rows * cfg.columns) { index ->
            val select = spinner(WidgetKind.entries.map { it.label })
            select.setSelection(cfg.cells[index].ordinal)
            select.onItemSelectedListener = changeListener { p ->
                val list = draft.cells.toMutableList()
                if (index in list.indices) {
                    list[index] = WidgetKind.entries[p]
                    draft = draft.copy(cells = list)
                    saveAndPreview()
                }
            }
            gridEditor.addView(labeled("Ô số ${index + 1}", select))
        }
    }
    private fun changeListener(block: (Int) -> Unit) = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
            if (!suppressEvents) block(pos)
        }
    }
    /** Keep the existing Mapbox instance/camera when changing only grid percentages. */
    private fun saveGridRatios() {
        draft = draft.normalized()
        store.save(draft)
        preview?.updateGridRatios(draft.rowPercents, draft.columnPercents)
    }

    private fun saveAndPreview() {
        store.save(draft)
        if (::previewArea.isInitialized) renderPreview()
    }
    private fun renderPreview() {
        if (!::previewArea.isInitialized) return
        preview?.dispose()
        previewArea.removeAllViews()
        if (!previewEnabled) {
            previewArea.addView(TextView(this).apply {
                text = "Bản xem trước Mapbox đang tắt.\nNhấn nút phía trên để bật khi cần."
                gravity = android.view.Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }, FrameLayout.LayoutParams(-1, -1))
            return
        }
        try {
            preview = DashboardView(this, draft.normalized()).also {
                previewArea.addView(it, FrameLayout.LayoutParams(-1, -1))
                if (isVisible) it.resumeMap()
            }
        } catch (error: RuntimeException) {
            showPreviewError(error)
        } catch (error: LinkageError) {
            showPreviewError(error)
        }
    }
    private fun showPreviewError(error: Throwable) {
        preview = null
        previewArea.removeAllViews()
        android.util.Log.e("CarDriverLauncher", "Map preview failed", error)
        previewArea.addView(TextView(this).apply {
            text = "Bản đồ không khởi tạo được. Cấu hình khác vẫn hoạt động.\n" +
                (error.message ?: error.javaClass.simpleName)
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }, FrameLayout.LayoutParams(-1, -1))
    }
    private fun addSignDialog() {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(30, 12, 30, 0) }
        fun field(hint: String, value: String = ""): EditText = EditText(this).apply {
            this.hint = hint; setText(value); inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            container.addView(this)
        }
        val loc = Telemetry.lastLocation
        val lat = field("Vĩ độ (-90..90)", loc?.latitude?.toString() ?: "")
        val lng = field("Kinh độ (-180..180)", loc?.longitude?.toString() ?: "")
        val speed = field("Tốc độ giới hạn (ví dụ 60), hoặc 0 để cảnh báo", "60")
        AlertDialog.Builder(this).setTitle("Nhập tọa độ biển báo").setView(container)
            .setPositiveButton("Lưu") { _, _ ->
                val latitude = lat.text.toString().toDoubleOrNull()
                val longitude = lng.text.toString().toDoubleOrNull()
                val limit = speed.text.toString().toIntOrNull()
                if (latitude == null || latitude !in -90.0..90.0 || longitude == null || longitude !in -180.0..180.0 ||
                    limit == null || limit !in 0..200) {
                    Toast.makeText(this, "Tọa độ hoặc giới hạn không hợp lệ", Toast.LENGTH_LONG).show()
                } else {
                    RoadSignsStore(this).add(RoadSign(latitude, longitude, limit.takeIf { it > 0 }, limit == 0))
                    renderPreview()
                }
            }.setNegativeButton("Hủy", null).show()
    }
    private fun choosePhotos() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(i, 200)
    }
    @Deprecated("Legacy Activity Result API used to minimize project dependencies")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 200 || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<android.net.Uri>()
        data.data?.let(uris::add)
        data.clipData?.let { clip -> repeat(clip.itemCount) { uris.add(clip.getItemAt(it).uri) } }
        for (uri in uris) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: Exception) { }
        }
        store.appendImages(uris.map { it.toString() })
        draft = store.load(); renderPreview()
    }
    private fun labeled(name: String, v: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(note(name)); addView(v)
    }
    private fun spinner(values: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, values)
    }
    private fun title(text: String, size: Float) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(0xFF152F43.toInt()); setPadding(0, dp(10), 0, dp(6))
    }
    private fun note(text: String) = TextView(this).apply {
        this.text = text; textSize = 13f; setTextColor(0xFF475569.toInt()); setPadding(0, dp(3), 0, dp(9))
    }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; setOnClickListener { action() }
    }
    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    override fun onStart() { super.onStart(); isVisible = true; Telemetry.start(this); preview?.resumeMap() }
    override fun onStop() { isVisible = false; preview?.pauseMap(); super.onStop() }
    override fun onDestroy() { preview?.dispose(); super.onDestroy() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) Telemetry.start(this)
    }
}
