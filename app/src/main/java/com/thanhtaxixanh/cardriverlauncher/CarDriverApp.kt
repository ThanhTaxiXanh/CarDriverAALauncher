package com.thanhtaxixanh.cardriverlauncher

import android.app.Application
import android.util.Log

/**
 * Mapbox is initialized lazily by DashboardView so a native SDK initialization
 * problem cannot prevent the phone settings screen from appearing at startup.
 * Crash details are stored only inside this app's private preferences.
 */
class CarDriverApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val systemHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                getSharedPreferences("car_driver_diagnostics", MODE_PRIVATE)
                    .edit()
                    .putString("last_crash", Log.getStackTraceString(error).take(12000))
                    .commit()
            } catch (_: Exception) { }
            if (systemHandler != null) {
                systemHandler.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }
}
