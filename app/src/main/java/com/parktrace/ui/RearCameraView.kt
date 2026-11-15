package com.parktrace.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.parktrace.R

/**
 * Simulated rear-camera view for the in-app demo: a photo of an underground car park, shown as if it were the
 * picture from the rear camera, with the usual guide lines on top. It moves in slowly as the car backs in.
 *
 * No camera is opened. The photo is a fixed asset (res/drawable-nodpi/rear_camera_garage.jpg: "Parkhaus
 * Rheinauhafen" by H005, public domain, Wikimedia Commons). The view says on screen that it is a simulation,
 * because it stands in for the car's real camera view.
 */
class RearCameraView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val photo: Bitmap? = runCatching {
        BitmapFactory.decodeResource(resources, R.drawable.rear_camera_garage, BitmapFactory.Options().apply { inScaled = false })
    }.getOrNull()

    private val dp = resources.displayMetrics.density
    private val image = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; isFakeBoldText = true }
    private val matrix = Matrix()
    private val rect = RectF()

    private var startedAt = 0L
    private var durationMs = 3_000L

    /** Starts the backing-in movement; it takes [duration] ms and then holds the last picture. */
    fun play(duration: Long) {
        durationMs = duration.coerceAtLeast(1L)
        startedAt = android.os.SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        val now = android.os.SystemClock.uptimeMillis()
        val t = ((now - startedAt).toDouble() / durationMs).coerceIn(0.0, 1.0)
        val k = (t * t * (3 - 2 * t)).toFloat() // ease in and out

        canvas.drawColor(Color.BLACK)
        val bmp = photo
        if (bmp != null) {
            // fill the view, then move in slowly towards the far end of the aisle: the car is backing in
            val scale = maxOf(w / bmp.width, h / bmp.height) * (1f + ZOOM * k)
            val pw = bmp.width * scale; val ph = bmp.height * scale
            val left = (w / 2 - FOCUS_U * pw).coerceIn(w - pw, 0f)
            val top = (h * 0.52f - FOCUS_V * ph).coerceIn(h - ph, 0f)
            matrix.setScale(scale, scale); matrix.postTranslate(left, top)
            canvas.drawBitmap(bmp, matrix, image)
        }

        // soft dark edges, like a wide-angle lens
        fill.color = Color.BLACK
        fill.shader = RadialGradient(w / 2, h / 2, maxOf(w, h) * 0.75f, intArrayOf(0, 0, VIGNETTE), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null

        // guide lines: fixed to the car, so they do not move with the picture
        line.strokeWidth = 6 * dp
        val steps = listOf(1.00f to GUIDE_NEAR, 0.86f to GUIDE_MID, 0.74f to GUIDE_FAR, 0.66f to 0)
        for (i in 0 until steps.size - 1) {
            val (y0f, color) = steps[i]; val y1f = steps[i + 1].first
            line.color = color
            for (side in intArrayOf(-1, 1)) {
                canvas.drawLine(w / 2 + side * guideHalf(w, y0f), h * y0f, w / 2 + side * guideHalf(w, y1f), h * y1f, line)
            }
            canvas.drawLine(w / 2 - guideHalf(w, y1f), h * y1f, w / 2 + guideHalf(w, y1f), h * y1f, line)
        }

        // label: this is a simulation, not the car's camera
        val label = "후방 카메라 · 시연용 가상 화면"
        text.textSize = 24 * dp; text.color = Color.WHITE; text.textAlign = Paint.Align.CENTER
        val tw = text.measureText(label) + 40 * dp; val th = 50 * dp
        rect.set(w / 2 - tw / 2, 28 * dp, w / 2 + tw / 2, 28 * dp + th)
        fill.color = CHIP
        canvas.drawRoundRect(rect, th / 2, th / 2, fill)
        canvas.drawText(label, rect.centerX(), rect.bottom - 16 * dp, text)

        if (t < 1.0) postInvalidateOnAnimation()
    }

    /** Half width of the guide-line trapezoid at a height given as a fraction of the view (narrower further away). */
    private fun guideHalf(w: Float, yFraction: Float): Float = w * (0.46f - (1f - yFraction) * 0.85f)

    companion object {
        /** How far the picture moves in while backing (fraction of its size). */
        private const val ZOOM = 0.16f
        /** Point of the photo the picture moves in on: the far end of the aisle. */
        private const val FOCUS_U = 0.49f
        private const val FOCUS_V = 0.60f

        private val GUIDE_FAR = Color.parseColor("#E63DDC6A")
        private val GUIDE_MID = Color.parseColor("#E6F2C230")
        private val GUIDE_NEAR = Color.parseColor("#E6E5484D")
        private val VIGNETTE = Color.parseColor("#8C000000")
        private val CHIP = Color.parseColor("#99000000")
    }
}
