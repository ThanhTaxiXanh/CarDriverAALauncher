package com.thanhtaxixanh.cardriverlauncher

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator

/**
 * CarAppService uses only supported host-managed templates.
 *
 * The previous VirtualDisplay/Presentation attempted to instantiate a Mapbox
 * MapView without an Activity/Fragment LifecycleOwner; this can crash the
 * application's shared process and disconnect Android Auto.
 *
 * This build deliberately uses a lightweight map Surface placeholder.
 * Full 3D mapping on Android Auto must be implemented with a supported,
 * lifecycle-safe car rendering integration and a genuine navigation flow.
 */
class CarDriverService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(this)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = DriverSession()
}

class DriverSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = DriverScreen(carContext)
}

class DriverScreen(carContext: CarContext) : Screen(carContext) {
    private val renderer = StableCarSurface()

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
        Telemetry.start(carContext)
    }

    override fun onGetTemplate(): Template =
        NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder().addAction(
                    Action.Builder()
                        .setTitle("Làm mới")
                        .setOnClickListener { renderer.redraw() }
                        .build()
                ).build()
            ).build()
}

/** Keeps the connection alive without allocating a secondary display. */
private class StableCarSurface : SurfaceCallback {
    @Volatile private var latest: SurfaceContainer? = null

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        latest = surfaceContainer
        draw(surfaceContainer)
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        latest = null
    }

    fun redraw() {
        latest?.let(::draw)
    }

    private fun draw(container: SurfaceContainer) {
        var canvas: Canvas? = null
        try {
            if (!container.surface.isValid) return
            canvas = container.surface.lockCanvas(null)
            val c = canvas
            c.drawColor(Color.rgb(11, 29, 43))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.DEFAULT_BOLD
                textSize = (container.dpi / 160f * 23f).coerceAtLeast(20f)
            }
            val x = (c.width * 0.06f).coerceAtLeast(20f)
            val titleY = (c.height * 0.44f).coerceAtLeast(55f)
            c.drawText("CarDriverLauncher", x, titleY, paint)
            paint.typeface = Typeface.DEFAULT
            paint.textSize *= 0.65f
            paint.color = Color.LTGRAY
            c.drawText("Bản đồ xe đang trong giai đoạn thử nghiệm", x, titleY + paint.textSize * 2f, paint)
            c.drawText("Cấu hình và xem trước Mapbox trên điện thoại", x, titleY + paint.textSize * 4f, paint)
        } catch (e: Exception) {
            Log.e("CarDriverLauncher", "Car Surface drawing failed", e)
        } finally {
            if (canvas != null) {
                try { container.surface.unlockCanvasAndPost(canvas) }
                catch (e: Exception) { Log.e("CarDriverLauncher", "Surface unlock failed", e) }
            }
        }
    }
}
