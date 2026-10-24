package com.thanhtaxixanh.cardriverlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View

/** Visible driver heading cue. This 2D safety fallback is not lane-level GPS positioning. */
class DriverArrowCue(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shape = Path()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = width.coerceAtMost(height).toFloat() / 100f
        if (scale <= 0f) return
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(scale, scale)
        shape.reset()
        shape.moveTo(0f, -44f)
        shape.lineTo(41f, 35f)
        shape.lineTo(8f, 21f)
        shape.lineTo(0f, 27f)
        shape.lineTo(-8f, 21f)
        shape.lineTo(-41f, 35f)
        shape.close()
        paint.shader = null
        paint.color = Color.argb(115, 0, 0, 0)
        canvas.save()
        canvas.translate(0f, 7f)
        canvas.drawPath(shape, paint)
        canvas.restore()
        paint.shader = LinearGradient(-22f, -44f, 20f, 34f,
            Color.rgb(24, 205, 255), Color.rgb(18, 65, 211), Shader.TileMode.CLAMP)
        canvas.drawPath(shape, paint)
        paint.shader = null
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        canvas.drawPath(shape, paint)
        paint.style = Paint.Style.FILL
        canvas.restore()
    }
}
