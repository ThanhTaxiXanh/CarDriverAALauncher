package com.thanhtaxixanh.cardriverlauncher

import android.Manifest
import android.app.Activity
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
class MainActivity : Activity() {
    private val store by lazy { SettingsStore(this) }
    private var draft = LayoutConfig()
    private lateinit var body: LinearLayout
    private lateinit var previewArea: FrameLayout
    private lateinit var gridEditor: LinearLayout
    private var preview: DashboardView? = null
    private var isVisible = false
    private var suppressEvents = false

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
            draft = draft.copy(rows = pos + 1).normalized()
            rebuildCells(); saveAndPreview()
        }
        columns.onItemSelectedListener = changeListener { pos ->
            draft = draft.copy(columns = pos + 1).normalized()
            rebuildCells(); saveAndPreview()
        }
        split.addView(rows, LinearLayout.LayoutParams(0, -2, 1f))
        split.addView(columns, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(labeled("Grid (1–3 hàng × 1–3 cột)", split))
        gridEditor = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(gridEditor)
        rebuildCells()
        body.addView(button("Thêm ảnh từ điện thoại") { choosePhotos() })
        body.addView(button("Cấp quyền điều khiển media") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        body.addView(button("Gắn biển báo bằng tọa độ GPS") { addSignDialog() })
        body.addView(button("Xóa dữ liệu biển báo thủ công") {
            AlertDialog.Builder(this).setMessage("Xóa toàn bộ điểm biển báo đã nhập?")
                .setPositiveButton("Xóa") { _, _ -> RoadSignsStore(this).clear(); renderPreview() }
                .setNegativeButton("Hủy", null).show()
        })
        body.addView(note("Biển báo và giới hạn tốc độ chỉ hiển thị từ điểm tọa độ do bạn nhập; ứng dụng không tự suy đoán biển báo thực tế."))
        body.addView(title("Xem trước trên điện thoại", 18f))
        previewArea = FrameLayout(this).apply { setBackgroundColor(Color.DKGRAY) }
        body.addView(previewArea, LinearLayout.LayoutParams(-1, dp(370)))
        body.addView(note("Trong Android Auto, host có thể che vùng Surface. Bản preview không cam kết bố cục chính xác trên màn hình xe."))
        suppressEvents = false
        renderPreview()
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
    private fun saveAndPreview() {
        store.save(draft)
        if (::previewArea.isInitialized) renderPreview()
    }
    private fun renderPreview() {
        if (!::previewArea.isInitialized) return
        preview?.dispose()
        previewArea.removeAllViews()
        preview = DashboardView(this, draft.normalized()).also {
            previewArea.addView(it, FrameLayout.LayoutParams(-1, -1))
            if (isVisible) it.resumeMap()
        }
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
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) Telemetry.start(this)
    }
}
