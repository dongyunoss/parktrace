package com.parktrace.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * Simulated rear-camera picture for the in-app demo: backing into a bay of an underground car park.
 *
 * Everything is drawn here (no camera, no photo): a back wall, bay lines, the two pillars, the cars in the
 * neighbouring bays and the usual guide lines. The picture moves as the car backs in. It is labelled as a
 * simulation on screen, because it stands in for the car's real camera view.
 */
class RearCameraView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val path = Path()
    private val rect = RectF()
    private val a = FloatArray(2); private val b = FloatArray(2); private val c = FloatArray(2); private val d = FloatArray(2)

    private var startedAt = 0L
    private var durationMs = 3_000L

    // camera: at the rear of the car, 1 m up, looking back
    private var focal = 1f; private var midX = 0f; private var horizon = 0f

    /** Starts the backing-in movement; it takes [duration] ms and then holds the last picture. */
    fun play(duration: Long) {
        durationMs = duration.coerceAtLeast(1L)
        startedAt = android.os.SystemClock.uptimeMillis()
        invalidate()
    }

    /** Car park point (x = to the right, dist = behind the car, z = up, metres) -> screen. */
    private fun project(x: Double, dist: Double, z: Double, out: FloatArray) {
        val dd = maxOf(dist, NEAR)
        out[0] = (midX + focal * x / dd).toFloat()
        out[1] = (horizon + focal * (CAM_H - z) / dd).toFloat()
    }

    private fun quad(canvas: Canvas, color: Int) {
        path.rewind()
        path.moveTo(a[0], a[1]); path.lineTo(b[0], b[1]); path.lineTo(c[0], c[1]); path.lineTo(d[0], d[1]); path.close()
        fill.shader = null; fill.color = color
        canvas.drawPath(path, fill)
    }

    /** Line on the floor between two distances, at lateral position x. */
    private fun floorLine(canvas: Canvas, x: Double, from: Double, to: Double, color: Int, width: Float) {
        if (to <= NEAR) return
        project(x, maxOf(from, NEAR), 0.0, a); project(x, to, 0.0, b)
        line.color = color; line.strokeWidth = width
        canvas.drawLine(a[0], a[1], b[0], b[1], line)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        val now = android.os.SystemClock.uptimeMillis()
        val t = ((now - startedAt).toDouble() / durationMs).coerceIn(0.0, 1.0)
        val k = t * t * (3 - 2 * t) // ease in and out
        // distance from the camera to the back wall: backs in from 7.4 m to 1.5 m
        val wall = START_DIST - (START_DIST - END_DIST) * k
        focal = w * 0.52f; midX = w / 2f; horizon = h * 0.34f

        // ceiling and far darkness (opaque: the paint's alpha would otherwise let the app show through)
        fill.color = Color.BLACK
        fill.shader = LinearGradient(0f, 0f, 0f, h, CEILING_TOP, CEILING_BOTTOM, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, fill)

        // ceiling lights: two strips above the bay
        for (side in doubleArrayOf(-1.9, 1.9)) {
            val l0 = wall - 4.6; val l1 = wall - 1.2
            if (l1 > NEAR + 0.4) {
                project(side - 0.35, maxOf(l0, NEAR + 0.4), CEIL_H, a); project(side + 0.35, maxOf(l0, NEAR + 0.4), CEIL_H, b)
                project(side + 0.35, l1, CEIL_H, c); project(side - 0.35, l1, CEIL_H, d)
                quad(canvas, LIGHT)
            }
        }

        // back wall with a painted band
        project(-WALL_HALF, wall, CEIL_H, a); project(WALL_HALF, wall, CEIL_H, b)
        project(WALL_HALF, wall, 0.0, c); project(-WALL_HALF, wall, 0.0, d)
        quad(canvas, WALL)
        project(-WALL_HALF, wall, 1.25, a); project(WALL_HALF, wall, 1.25, b)
        project(WALL_HALF, wall, 0.95, c); project(-WALL_HALF, wall, 0.95, d)
        quad(canvas, WALL_BAND)

        // floor: from the wall base to the bottom of the picture
        project(0.0, wall, 0.0, a)
        val floorTop = a[1]
        fill.color = Color.BLACK
        fill.shader = LinearGradient(0f, floorTop, 0f, h, FLOOR_FAR, FLOOR_NEAR, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, floorTop, w, h, fill)
        fill.shader = null

        // bay lines: ours and the two neighbours, open towards the aisle (towards the camera)
        val bayFront = wall - BAY_DEPTH
        for (i in -3..2) floorLine(canvas, (i + 0.5) * BAY_W, bayFront, wall - 0.15, BAY_LINE, 5 * dp)
        // our bay is tinted
        project(-BAY_W / 2, maxOf(bayFront, NEAR), 0.0, a); project(BAY_W / 2, maxOf(bayFront, NEAR), 0.0, b)
        project(BAY_W / 2, wall - 0.15, 0.0, c); project(-BAY_W / 2, wall - 0.15, 0.0, d)
        quad(canvas, MINE_BAY)
        // wheel stop
        project(-0.9, wall - 0.95, 0.14, a); project(0.9, wall - 0.95, 0.14, b)
        project(0.9, wall - 0.95, 0.0, c); project(-0.9, wall - 0.95, 0.0, d)
        quad(canvas, STOPPER)

        // neighbouring cars (plain boxes: the side facing our bay, the end facing the camera, the roof)
        for (side in intArrayOf(-1, 1)) {
            val inner = side * (BAY_W / 2 + 0.38); val outer = side * (BAY_W / 2 + 0.38 + CAR_W)
            val near = maxOf(wall - 4.7, NEAR); val far = wall - 0.7
            if (far <= near + 0.2) continue
            // roof
            project(inner, near, CAR_H, a); project(outer, near, CAR_H, b); project(outer, far, CAR_H, c); project(inner, far, CAR_H, d)
            quad(canvas, CAR_ROOF)
            // side towards our bay
            project(inner, near, CAR_H, a); project(inner, far, CAR_H, b); project(inner, far, 0.25, c); project(inner, near, 0.25, d)
            quad(canvas, CAR_SIDE)
            // window band and wheels on that side (only the part still ahead of the lens)
            val g0 = maxOf(wall - 3.9, NEAR); val g1 = wall - 1.5
            if (g1 > g0 + 0.2) {
                project(inner, g0, CAR_H - 0.14, a); project(inner, g1, CAR_H - 0.14, b)
                project(inner, g1, 1.02, c); project(inner, g0, 1.02, d)
                quad(canvas, CAR_GLASS)
            }
            for (wheel in doubleArrayOf(wall - 3.75, wall - 1.45)) {
                val w0 = maxOf(wheel - 0.34, NEAR); val w1 = wheel + 0.34
                if (w1 <= w0 + 0.1) continue
                project(inner, w0, 0.66, a); project(inner, w1, 0.66, b); project(inner, w1, 0.0, c); project(inner, w0, 0.0, d)
                quad(canvas, CAR_WHEEL)
            }
            // end towards the camera, only while it is still ahead of the lens
            if (wall - 4.7 > NEAR) {
                project(inner, near, CAR_H, a); project(outer, near, CAR_H, b); project(outer, near, 0.25, c); project(inner, near, 0.25, d)
                quad(canvas, CAR_END)
                project(inner + side * 0.15, near, 0.72, a); project(outer - side * 0.15, near, 0.72, b)
                project(outer - side * 0.15, near, 0.58, c); project(inner + side * 0.15, near, 0.58, d)
                quad(canvas, CAR_LAMP)
            }
        }

        // pillars at the back corners of our bay, with their signs
        for ((side, label) in listOf(-1 to "C-14", 1 to "C-15")) {
            val x0 = side * (BAY_W / 2 - 0.05); val x1 = side * (BAY_W / 2 + 0.55)
            val pd = wall - 0.75
            project(x0, pd, CEIL_H, a); project(x1, pd, CEIL_H, b); project(x1, pd, 0.0, c); project(x0, pd, 0.0, d)
            quad(canvas, PILLAR)
            // sign plate
            project(x0, pd, 2.0, a); project(x1, pd, 2.0, b); project(x1, pd, 1.45, c); project(x0, pd, 1.45, d)
            quad(canvas, if (side == 1) SIGN_MINE else SIGN)
            val plateH = d[1] - a[1]
            text.textSize = plateH * 0.56f
            text.color = Color.WHITE
            canvas.drawText(label, (a[0] + b[0]) / 2, a[1] + plateH * 0.70f, text)
            // hazard stripes at the foot
            project(x0, pd, 0.9, a); project(x1, pd, 0.9, b); project(x1, pd, 0.0, c); project(x0, pd, 0.0, d)
            quad(canvas, HAZARD)
        }

        // guide lines: fixed to the car, so they do not move with the picture
        val gw = 6 * dp
        for (side in doubleArrayOf(-1.0, 1.0)) {
            floorLine(canvas, side * GUIDE_HALF, 2.4, 4.2, GUIDE_FAR, gw)
            floorLine(canvas, side * GUIDE_HALF, 1.3, 2.4, GUIDE_MID, gw)
            floorLine(canvas, side * GUIDE_HALF, NEAR, 1.3, GUIDE_NEAR, gw)
        }
        for ((dist, col) in listOf(4.2 to GUIDE_FAR, 2.4 to GUIDE_MID, 1.3 to GUIDE_NEAR)) {
            project(-GUIDE_HALF, dist, 0.0, a); project(GUIDE_HALF, dist, 0.0, b)
            line.color = col; line.strokeWidth = gw
            canvas.drawLine(a[0], a[1], b[0], b[1], line)
        }

        // soft dark edges, like a wide-angle lens
        fill.color = Color.BLACK
        fill.shader =android.graphics.RadialGradient(w / 2, h / 2, maxOf(w, h) * 0.72f, intArrayOf(0, 0, VIGNETTE), floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null

        // label: this is a simulation, not the car's camera
        val label = "후방 카메라 · 시연용 가상 화면"
        text.textSize = 24 * dp; text.color = Color.WHITE
        val tw = text.measureText(label) + 40 * dp; val th = 50 * dp
        // top centre: clear of the pillar signs, which end up in the top corners
        rect.set(w / 2 - tw / 2, 28 * dp, w / 2 + tw / 2, 28 * dp + th)
        fill.color = CHIP
        canvas.drawRoundRect(rect, th / 2, th / 2, fill)
        canvas.drawText(label, rect.centerX(), rect.bottom - 16 * dp, text)

        if (t < 1.0) postInvalidateOnAnimation()
    }

    companion object {
        private const val NEAR = 0.75
        private const val CAM_H = 1.0
        private const val CEIL_H = 2.7
        private const val WALL_HALF = 14.0
        private const val BAY_W = 2.5
        private const val BAY_DEPTH = 5.1
        private const val CAR_W = 1.75
        private const val CAR_H = 1.5
        private const val GUIDE_HALF = 0.98
        private const val START_DIST = 7.4
        /** Stops this far from the back wall, so the pillars and their signs stay in the picture. */
        private const val END_DIST = 2.7

        private val CEILING_TOP = Color.parseColor("#15171B")
        private val CEILING_BOTTOM = Color.parseColor("#2A2E35")
        private val LIGHT = Color.parseColor("#E8EEF7")
        private val WALL = Color.parseColor("#8B929C")
        private val WALL_BAND = Color.parseColor("#3D6FB5")
        private val FLOOR_FAR = Color.parseColor("#4B5059")
        private val FLOOR_NEAR = Color.parseColor("#6A707A")
        private val BAY_LINE = Color.parseColor("#F2F4F7")
        private val MINE_BAY = Color.parseColor("#332F80FF")
        private val STOPPER = Color.parseColor("#E8B33A")
        private val CAR_ROOF = Color.parseColor("#C9CED6")
        private val CAR_SIDE = Color.parseColor("#AEB5BF")
        private val CAR_END = Color.parseColor("#BFC5CE")
        private val CAR_GLASS = Color.parseColor("#2B313B")
        private val CAR_WHEEL = Color.parseColor("#22262D")
        private val CAR_LAMP =Color.parseColor("#D64545")
        private val PILLAR = Color.parseColor("#A4ABB5")
        private val SIGN = Color.parseColor("#4A5361")
        private val SIGN_MINE = Color.parseColor("#2F80FF")
        private val HAZARD = Color.parseColor("#E8B33A")
        private val GUIDE_FAR = Color.parseColor("#E63DDC6A")
        private val GUIDE_MID = Color.parseColor("#E6F2C230")
        private val GUIDE_NEAR = Color.parseColor("#E6E5484D")
        private val VIGNETTE = Color.parseColor("#99000000")
        private val CHIP = Color.parseColor("#99000000")
    }
}
