package com.thanhtaxixanh.cardriverlauncher

import android.app.Presentation
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.validation.HostValidator

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
    private val renderer = SurfaceDashboard(carContext)
    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
        Telemetry.start(carContext)
    }
    override fun onGetTemplate(): Template {
        // NavigationTemplate grants a host-managed map Surface. The Activity never appears here.
        // This is a developer preview, not a completed turn-by-turn navigation app.
        return NavigationTemplate.Builder().setActionStrip(
            ActionStrip.Builder().addAction(Action.Builder()
                .setTitle("Làm mới")
                .setOnClickListener { renderer.refresh(); invalidate() }
                .build()).build()
        ).build()
    }
}

/** VirtualDisplay / Presentation draws Mapbox + read-only widgets to a Car App surface.
 * Platform-specific rendering and driving-distraction approval must be validated.
 */
private class SurfaceDashboard(private val context: CarContext) : SurfaceCallback {
    private val main = Handler(Looper.getMainLooper())
    private var display: VirtualDisplay? = null
    private var presentation: DashboardPresentation? = null

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        main.post {
            close()
            try {
                val dm = context.getSystemService(DisplayManager::class.java) ?: return@post
                display = dm.createVirtualDisplay("CarDriverMapSurface",
                    container.width, container.height, container.dpi, container.surface, 0)
                val d = display?.display ?: return@post
                presentation = DashboardPresentation(context, d).also { it.show() }
            } catch (_: Exception) { close() }
        }
    }
    override fun onSurfaceDestroyed(container: SurfaceContainer) { main.post { close() } }
    fun refresh() = main.post {
        // Re-read the configuration saved on the phone without rebooting the car.
        presentation?.rebuild()
    }
    private fun close() {
        presentation?.dismiss(); presentation = null
        display?.release(); display = null
    }
}

private class DashboardPresentation(context: CarContext, display: android.view.Display) : Presentation(context, display) {
    private var dashboard: DashboardView? = null
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        rebuild()
    }
    fun rebuild() {
        dashboard?.dispose()
        dashboard = DashboardView(context, SettingsStore(context).load(), onCarSurface = true)
        setContentView(dashboard)
        dashboard?.resumeMap()
    }
    override fun onStop() { dashboard?.dispose(); dashboard = null; super.onStop() }
}
