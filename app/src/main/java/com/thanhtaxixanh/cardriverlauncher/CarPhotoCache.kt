package com.thanhtaxixanh.cardriverlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

/**
 * Decode the selected phone image only once, off the CarAppService UI thread.
 * Android Auto renders a static still from the persisted SAF Uri. No slideshow
 * transitions, gallery browsing, web images or interactive photo controls.
 */
internal class CarPhotoCache(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "CarDriverPhotoDecoder").apply { isDaemon = true }
    }
    private var loadedUri: String? = null
    private var requestedUri: String? = null
    private var pending = false
    private var image: Bitmap? = null
    private var closed = false

    fun current(): Bitmap? = image

    /** Must be called on the UI thread, along with drawDashboard(). */
    fun request(uri: String?, onLoaded: () -> Unit) {
        if (closed) return
        if (uri.isNullOrBlank()) {
            requestedUri = null
            loadedUri = null
            image = null
            return
        }
        if (uri == loadedUri || (uri == requestedUri && pending)) return
        requestedUri = uri
        pending = true
        executor.execute {
            val decoded = try {
                val parsed = Uri.parse(uri)
                // Only document/content URIs picked from the user's phone.
                if (parsed.scheme != "content") null
                else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(parsed)?.use {
                        BitmapFactory.decodeStream(it, null, bounds)
                    }
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
                    else {
                        var sample = 1
                        while (bounds.outWidth / sample > 1024 ||
                               bounds.outHeight / sample > 1024) sample *= 2
                        val opts = BitmapFactory.Options().apply {
                            inSampleSize = sample
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                        context.contentResolver.openInputStream(parsed)?.use {
                            BitmapFactory.decodeStream(it, null, opts)
                        }
                    }
                }
            } catch (t: Exception) {
                Log.w("CarDriverPhoto", "Could not read selected image", t)
                null
            } catch (t: OutOfMemoryError) {
                Log.e("CarDriverPhoto", "Image exceeds memory", t)
                null
            }
            main.post {
                if (closed) {
                    decoded?.recycle()
                    return@post
                }
                if (requestedUri == uri) {
                    pending = false
                    loadedUri = uri
                    val old = image
                    image = decoded
                    if (old !== decoded) old?.recycle()
                    onLoaded()
                } else decoded?.recycle()
            }
        }
    }

    fun close() {
        closed = true
        executor.shutdownNow()
        image?.recycle()
        image = null
    }
}
