package com.thanhtaxixanh.cardriverlauncher

import android.app.Application
import com.mapbox.common.MapboxOptions

class CarDriverApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.MAPBOX_PUBLIC_TOKEN.startsWith("pk.")) {
            MapboxOptions.accessToken = BuildConfig.MAPBOX_PUBLIC_TOKEN
        }
    }
}
