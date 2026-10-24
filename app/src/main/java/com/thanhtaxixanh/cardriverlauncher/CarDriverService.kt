package com.thanhtaxixanh.cardriverlauncher

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.AppManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import com.mapbox.common.MapboxOptions
import com.mapbox.geojson.Feature
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.ContextMode
import com.mapbox.maps.MapOptions
import com.mapbox.maps.MapInitOptions
import com.mapbox.maps.MapboxExperimental
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.Style
import com.mapbox.maps.extension.androidauto.MapboxCarMap
import com.mapbox.maps.extension.androidauto.MapboxCarMapObserver
import com.mapbox.maps.extension.androidauto.MapboxCarMapSurface
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import com.mapbox.maps.extension.style.layers.generated.symbolLayer
import com.mapbox.maps.extension.style.expressions.dsl.generated.get
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.style
import com.mapbox.maps.extension.style.sources.getSourceAs

/**
 * Mapbox Maps Android Auto Extension renders straight into the official host Surface.
 * The opt-in dashboard is a Mapbox native BitmapWidget, not a VirtualDisplay.
 * Photos, QR and media controls are disabled on the car for safety.
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
    private val renderer = HybridCarRenderer(carContext)
    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
        Telemetry.start(carContext)
    }

    override fun onGetTemplate(): Template =
        NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder()
                .addAction(Action.Builder()
                    .setTitle("Làm mới")
                    .setOnClickListener { renderer.refresh() }
                    .build())
                .build())
            .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
            .build()
}

/**
 * MapboxCarMap creates the MapSurface itself, without requiring an Activity lifecycle.
 * Optional dashboard drawing happens inside the map renderer and never takes
 * ownership of the host Surface or starts a second display.
 */
@OptIn(MapboxExperimental::class)
private class HybridCarRenderer(private val context: CarContext) : SurfaceCallback {
    private val main = Handler(Looper.getMainLooper())
    private val stableExperience = CarMapExperience(context)
    private val stableMap = MapboxCarMap().registerObserver(stableExperience)
    private val stableSurface: SurfaceCallback
    private var stableActive = false
    // The Mapbox renderer owns the only Android Auto Surface.
    private var currentSurface: SurfaceContainer? = null
    private var generation = 0

    init {
        if (BuildConfig.MAPBOX_PUBLIC_TOKEN.startsWith("pk.")) {
            MapboxOptions.accessToken = BuildConfig.MAPBOX_PUBLIC_TOKEN
        }
        stableSurface = stableMap.prepareSurfaceCallback(
            context,
            MapInitOptions(
                context = context,
                mapOptions = MapOptions.Builder().contextMode(ContextMode.SHARED).build()
            )
        )
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val id = ++generation
        main.post {
            if (id != generation) return@post
            close()
            currentSurface = surfaceContainer
            // The opt-in dashboard uses Mapbox BitmapWidgets on this Surface.
            // Never create VirtualDisplay/Presentation: that disconnected the car service.
            useStable(surfaceContainer)
        }
    }

    private fun useStable(container: SurfaceContainer) {
        if (!BuildConfig.MAPBOX_PUBLIC_TOKEN.startsWith("pk.")) {
            Log.w(TAG, "Mapbox public token missing; car map cannot load")
            return
        }
        try {
            stableActive = true
            stableSurface.onSurfaceAvailable(container)
        } catch (error: RuntimeException) {
            stableActive = false
            Log.e(TAG, "Official Mapbox car map initialization failed", error)
        } catch (error: LinkageError) {
            stableActive = false
            Log.e(TAG, "Official Mapbox extension unavailable", error)
        }
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        ++generation
        main.post { close(); currentSurface = null }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        if (stableActive) stableSurface.onVisibleAreaChanged(visibleArea)
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        if (stableActive) stableSurface.onStableAreaChanged(stableArea)
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        if (stableActive) stableSurface.onScroll(distanceX, distanceY)
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        if (stableActive) stableSurface.onFling(velocityX, velocityY)
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (stableActive) stableSurface.onScale(focusX, focusY, scaleFactor)
    }

    fun refresh() {
        main.post {
            val surface = currentSurface ?: return@post
            // A new phone configuration is reflected the next time the car surface is attached.
            stableExperience.refresh()
        }
    }

    private fun close() {
         if (stableActive) {
            currentSurface?.let { surface ->
                try { stableSurface.onSurfaceDestroyed(surface) }
                catch (error: RuntimeException) { Log.w(TAG, "Car MapSurface destroy failed", error) }
            }
            stableActive = false
        }
    }

    companion object { private const val TAG = "CarDriverAuto" }
}

/** Fully supported map rendering path (no Presentation, no View on car surface). */
private class CarMapExperience(context: Context) : MapboxCarMapObserver {
    private val handler = Handler(Looper.getMainLooper())
    private var map: MapboxMap? = null
    private var arrow: DriverArrow3D? = null
    private var panels: CarSurfacePanels? = null
    private var currentPoint: Point? = null
    private var roadLabel = "Đang xác định tên đường"
    private val streetResolver = RoadNameResolver(context.applicationContext) {
        roadLabel = it
        updateRoadText()
        panels?.updateStreet(it)
    }
    private val tick = object : Runnable {
        override fun run() {
            val location = Telemetry.lastLocation
            if (location != null) {
                currentPoint = Point.fromLngLat(location.longitude, location.latitude)
                arrow?.update(location)
                panels?.updatePosition(location)
                streetResolver.update(location)
                updateRoadText()
            }
            panels?.refresh()
            if (map != null) handler.postDelayed(this, 1800L)
        }
    }

    override fun onAttached(mapboxCarMapSurface: MapboxCarMapSurface) {
        val active = mapboxCarMapSurface.mapSurface.mapboxMap
        map = active
        try {
            panels = CarSurfacePanels(mapboxCarMapSurface.carContext, mapboxCarMapSurface)
                .also { it.attach() }
        } catch (error: Exception) {
            Log.w("CarDriverAuto", "Optional native dashboard could not attach", error)
            panels = null
        }
        try {
            active.loadStyle(Style.STANDARD) { style ->
                if (map !== active) return@loadStyle
                DrivingMapStyle.configure(style)
                arrow = DriverArrow3D(active) {
                    val settings = SettingsStore(context).load().normalized()
                    val surface = mapboxCarMapSurface.surfaceContainer
                    if (!settings.carDashboardEnabled) {
                        EdgeInsets(0.0, 0.0, 0.0, 0.0)
                    } else if (settings.mode == SplitMode.HORIZONTAL) {
                        EdgeInsets(0.0,
                            surface.width * (100 - settings.mapPercent) / 100.0,
                            0.0, 0.0)
                    } else {
                        EdgeInsets(0.0, 0.0,
                            surface.height * (100 - settings.mapPercent) / 100.0,
                            0.0)
                    }
                }.also { it.install(style) }
                val point = currentPoint ?: Point.fromLngLat(105.8342, 21.0278)
                try {
                    style.addSource(geoJsonSource(ROAD_SOURCE) {
                        feature(Feature.fromGeometry(point).also {
                            it.addStringProperty("roadName", roadLabel)
                        })
                    })
                    style.addLayer(symbolLayer(ROAD_LABEL_LAYER, ROAD_SOURCE) {
                        textField(get("roadName"))
                        textSize(17.0)
                        textColor("#FFFFFF")
                        textHaloColor("#173147")
                        textHaloWidth(2.5)
                        textOffset(listOf(0.0, 5.0))
                        textAllowOverlap(true)
                    })
                } catch (error: Exception) {
                    Log.w("CarMapExperience", "Street label layer unavailable", error)
                }
                val location = Telemetry.lastLocation
                if (location != null) arrow?.update(location)
                else active.setCamera(CameraOptions.Builder()
                    .center(point).zoom(16.3).pitch(65.0).build())
            }
        } catch (error: Exception) { Log.e("CarMapExperience", "Map style could not load", error) }
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onDetached(mapboxCarMapSurface: MapboxCarMapSurface) {
        handler.removeCallbacks(tick)
        map = null
        arrow = null
        panels = null
    }

    fun refresh() {
        panels?.refresh(force = true)
        val location = Telemetry.lastLocation
        if (location != null) {
            arrow?.update(location)
            panels?.updatePosition(location)
        }
    }

    private fun updateRoadText() {
        val point = currentPoint ?: return
        try {
            map?.getStyle()?.getSourceAs<GeoJsonSource>(ROAD_SOURCE)
                ?.feature(Feature.fromGeometry(point).also {
                    it.addStringProperty("roadName", roadLabel)
                })
        } catch (error: Exception) {
            Log.d("CarMapExperience", "Street label not ready", error)
        }
    }

    companion object {
        private const val ROAD_SOURCE = "car-driver-street-name"
        private const val ROAD_LABEL_LAYER = "car-driver-street-label"
    }
}
