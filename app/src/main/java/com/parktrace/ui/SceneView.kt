package com.parktrace.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.parktrace.core.ParkingScene
import com.parktrace.sdk.SimulatedSurroundVision.Companion.BAY_D
import com.parktrace.sdk.SimulatedSurroundVision.Companion.BAY_W
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 3D view of where the car is parked: driven path, our bay, neighbouring cars and pillars.
 *
 * Plain Canvas + perspective projection (no OpenGL): it only redraws on state changes or drags,
 * which keeps the head unit idle. Drag horizontally to orbit, vertically to tilt.
 */
class SceneView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var scene: ParkingScene? = null
    private var carLabel: String = ""

    private var yawOffset = Math.toRadians(-32.0)
    private var pitch = Math.toRadians(50.0)
    private val dist = DIST

    // per-frame camera
    private var tx = 0.0; private var ty = 0.0
    private var cosY = 1.0; private var sinY = 0.0
    private var cosP = 0.0; private var sinP = 1.0
    private var focal = 1.0; private var midX = 0f; private var midY = 0f

    private val dp = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; textSize = 24 * dp }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED; textSize = 19 * dp }
    private val path = Path()
    private val p = FloatArray(3)
    private val rect = RectF()

    // ---- motion: the car glides from where it is drawn to each new position; the camera turns after it
    private var hasPose = false
    private var curX = 0.0; private var curY = 0.0; private var curA = 0.0
    private var fromX = 0.0; private var fromY = 0.0; private var fromA = 0.0
    private var toX = 0.0; private var toY = 0.0; private var toA = 0.0
    private var animStart = 0L; private var animDur = 0L; private var lastChange = 0L
    private var camYaw = 0.0; private var lastFrame = 0L; private var lastDrawMs = 0L
    private var way: List<Pair<Double, Double>> = emptyList()
    private var wayLen = DoubleArray(0)

    private fun angDiff(d: Double): Double {
        var x = d
        while (x > Math.PI) x -= 2 * Math.PI
        while (x < -Math.PI) x += 2 * Math.PI
        return x
    }

    /** Camera side: behind the car while it drives (chase view), in front of it for the parked result. */
    private var frontView = false
    private fun camTarget(carAngle: Double) = if (frontView) carAngle + Math.PI else carAngle

    /** @param front look at the car from its front (the saved parking position) instead of from behind */
    fun setScene(s: ParkingScene?, label: String, front: Boolean = false) {
        scene = s; carLabel = label; frontView = front
        if (s == null) { hasPose = false; invalidate(); return }
        val a = atan2(s.car.hx, s.car.hy)
        val now = android.os.SystemClock.uptimeMillis()
        if (!hasPose || Math.hypot(s.car.x - curX, s.car.y - curY) > JUMP_METERS) {
            // first scene, or a different place altogether: no animation
            hasPose = true
            curX = s.car.x; curY = s.car.y; curA = a
            fromX = curX; fromY = curY; fromA = a; toX = curX; toY = curY; toA = a
            camYaw = camTarget(a); animDur = 0L; lastChange = now
        } else if (Math.abs(s.car.x - toX) > 0.01 || Math.abs(s.car.y - toY) > 0.01 || Math.abs(angDiff(a - toA)) > 0.002) {
            fromX = curX; fromY = curY; fromA = curA
            toX = s.car.x; toY = s.car.y; toA = a
            // way points: the part of the driven path between where the car is drawn now and its new place
            val pts = s.path
            var near = pts.size - 1; var best = Double.MAX_VALUE
            for (i in pts.indices.reversed()) {
                val d = Math.hypot(pts[i].first - curX, pts[i].second - curY)
                if (d < best) { best = d; near = i }
                if (pts.size - i > 40) break
            }
            val w = ArrayList<Pair<Double, Double>>()
            w += curX to curY
            if (best < 3.0) for (i in near + 1 until pts.size) w += pts[i]
            w += toX to toY
            way = w
            wayLen = DoubleArray(w.size).also { for (i in 1 until w.size) it[i] = it[i - 1] + Math.hypot(w[i].first - w[i - 1].first, w[i].second - w[i - 1].second) }
            // take as long as the updates are apart, so the motion is continuous
            animDur = (now - lastChange).coerceIn(250L, 1200L); animStart = now; lastChange = now
        }
        invalidate()
    }

    // ------------------------------------------------------------------ touch

    private var lastX = 0f; private var lastY = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> {
                yawOffset -= (e.x - lastX) * 0.008
                pitch = (pitch + (e.y - lastY) * 0.004).coerceIn(Math.toRadians(22.0), Math.toRadians(82.0))
                lastX = e.x; lastY = e.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    // ------------------------------------------------------------------ projection

    /** World (m) -> screen. Returns false when the point is behind / too close to the camera. */
    private fun project(x: Double, y: Double, z: Double, out: FloatArray = p): Boolean {
        val dx = x - tx; val dy = y - ty
        val rx = dx * cosY - dy * sinY
        val ry = dx * sinY + dy * cosY
        val vy = ry + dist * cosP
        val vz = z - dist * sinP
        val depth = vy * cosP - vz * sinP
        if (depth < 0.6) return false
        val up = vy * sinP + vz * cosP
        out[0] = (midX + focal * rx / depth).toFloat()
        out[1] = (midY - focal * up / depth).toFloat()
        out[2] = depth.toFloat()
        return true
    }

    private fun depthOf(x: Double, y: Double, z: Double): Double {
        val dx = x - tx; val dy = y - ty
        val ry = dx * sinY + dy * cosY
        return (ry + dist * cosP) * cosP - (z - dist * sinP) * sinP
    }

    // ------------------------------------------------------------------ drawing

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgRect = RectF()
    private val clip = android.graphics.Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bg.shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), BG_TOP, BG_BOTTOM, android.graphics.Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bgRect.set(0f, 0f, width.toFloat(), height.toFloat())
        clip.rewind(); clip.addRoundRect(bgRect, 36 * dp, 36 * dp, android.graphics.Path.Direction.CW)
        canvas.clipPath(clip)
        canvas.drawRect(bgRect, bg)
        val full = scene ?: return
        val now = android.os.SystemClock.uptimeMillis()
        val k = if (animDur <= 0L) 1.0 else ((now - animStart).toDouble() / animDur).coerceIn(0.0, 1.0)
        val total = if (wayLen.isEmpty()) 0.0 else wayLen[wayLen.size - 1]
        if (k >= 1.0 || total < 1e-6) { curX = toX; curY = toY } else {
            val d = total * k
            var i = 1
            while (i < way.size - 1 && wayLen[i] < d) i++
            val seg = wayLen[i] - wayLen[i - 1]
            val f = if (seg < 1e-9) 1.0 else (d - wayLen[i - 1]) / seg
            curX = way[i - 1].first + (way[i].first - way[i - 1].first) * f
            curY = way[i - 1].second + (way[i].second - way[i - 1].second) * f
        }
        curA = fromA + angDiff(toA - fromA) * k
        val dt = (now - lastFrame).coerceIn(0L, 200L) / 1000.0; lastFrame = now
        // parked: the camera swings round to the front of the car
        val target = camTarget(curA)
        camYaw += angDiff(target - camYaw) * (1 - Math.exp(-dt / CAM_TAU))
        val moving = k < 1.0 || Math.abs(angDiff(target - camYaw)) > 0.004
        // the route line stops at the car while it is still on its way to the newest point
        val route = if (k >= 1.0) full.path else {
            val rest = total * (1 - k)
            var acc = 0.0; var keep = full.path.size
            while (keep > 1 && acc < rest) { acc += Math.hypot(full.path[keep - 1].first - full.path[keep - 2].first, full.path[keep - 1].second - full.path[keep - 2].second); keep-- }
            full.path.subList(0, keep) + (curX to curY)
        }
        // the whole level is in the scene; only what is near the car is worth drawing
        val s = full.copy(
            car = ParkingScene.Pose(curX, curY, sin(curA), cos(curA)), path = route,
            bays = full.bays.filter { Math.hypot(it.x - curX, it.y - curY) < DRAW_RADIUS },
        )
        setupCamera(s)
        drawFloor(canvas, s)
        drawGarageFloor(canvas, s)
        drawPath(canvas, s)
        drawBays(canvas, s)
        drawShadows(canvas, s)
        drawObjects(canvas, s)
        lastDrawMs = android.os.SystemClock.uptimeMillis() - now
        // leave at least half of the time to the rest of the app
        if (moving) postInvalidateDelayed(maxOf(FRAME_MS, lastDrawMs))
    }

    private fun setupCamera(s: ParkingScene) {
        tx = s.car.x; ty = s.car.y
        val a = camYaw + yawOffset
        cosY = cos(a); sinY = sin(a)
        cosP = cos(pitch); sinP = sin(pitch)
        focal = minOf(width, height) * 1.35
        midX = width / 2f; midY = height * 0.56f
    }

    private fun drawFloor(c: Canvas, s: ParkingScene) {
        line.strokeWidth = 1f; line.color = GRID
        val r = 22.0; val step = 2.5
        val gx = Math.floor(s.car.x / step) * step; val gy = Math.floor(s.car.y / step) * step
        var v = -r
        while (v <= r + 1e-6) {
            seg(c, gx - r, gy + v, gx + r, gy + v)
            seg(c, gx + v, gy - r, gx + v, gy + r)
            v += step
        }
    }

    /** Floor line, clipped crudely by subdividing so segments that pass behind the camera still draw. */
    private fun seg(c: Canvas, x1: Double, y1: Double, x2: Double, y2: Double, z: Double = 0.0) {
        val n = 8
        var hasPrev = false; var px = 0f; var py = 0f
        for (i in 0..n) {
            val t = i.toDouble() / n
            if (project(x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, z)) {
                if (hasPrev) c.drawLine(px, py, p[0], p[1], line)
                px = p[0]; py = p[1]; hasPrev = true
            } else hasPrev = false
        }
    }

    /** Ramp surface between the walls, aisle asphalt with a dashed centre line and direction arrows. */
    private fun drawGarageFloor(c: Canvas, s: ParkingScene) {
        if (s.walls.size == 2) {
            val l = s.walls[0]; val r = s.walls[1]
            fill.color = RAMP_FLOOR
            for (i in 0 until minOf(l.size, r.size) - 1) {
                val xs = doubleArrayOf(l[i].first, l[i + 1].first, r[i + 1].first, r[i].first)
                val ys = doubleArrayOf(l[i].second, l[i + 1].second, r[i + 1].second, r[i].second)
                if (polygon(xs, ys, 0.0)) c.drawPath(path, fill)
            }
            // dashed lane centre line between the walls
            line.color = LANE; line.strokeWidth = 3 * dp
            for (i in 0 until minOf(l.size, r.size) - 1 step 3) {
                val j = minOf(i + 1, l.size - 1, r.size - 1)
                seg(c, (l[i].first + r[i].first) / 2, (l[i].second + r[i].second) / 2,
                    (l[j].first + r[j].first) / 2, (l[j].second + r[j].second) / 2, 0.006)
            }
        }
        // floor slab of the level, in tiles so the part behind the camera clips cleanly
        s.floor?.let { f ->
            val rx = f.uy; val ry = -f.ux
            fill.color = SLAB
            var t = 0.0
            while (t < f.length) {
                val t2 = minOf(f.length, t + SLAB_TILE)
                var w = 0.0
                while (w < f.width) {
                    val w2 = minOf(f.width, w + SLAB_TILE)
                    val mx = f.x + f.ux * (t + t2) / 2 + rx * (w + w2) / 2; val my = f.y + f.uy * (t + t2) / 2 + ry * (w + w2) / 2
                    if (Math.hypot(mx - s.car.x, my - s.car.y) < DRAW_RADIUS + SLAB_TILE) {
                        val xs = doubleArrayOf(f.x + f.ux * t + rx * w, f.x + f.ux * t2 + rx * w, f.x + f.ux * t2 + rx * w2, f.x + f.ux * t + rx * w2)
                        val ys = doubleArrayOf(f.y + f.uy * t + ry * w, f.y + f.uy * t2 + ry * w, f.y + f.uy * t2 + ry * w2, f.y + f.uy * t + ry * w2)
                        if (polygon(xs, ys, 0.002)) c.drawPath(path, fill)
                    }
                    w = w2
                }
                t = t2
            }
        }
        for (l in s.lanes) lane(c, l.first, l.second, arrows = false)
        s.aisle?.let { lane(c, it.first, it.second, arrows = true) }
    }

    /** One driving lane: asphalt band, dashed centre line, and (on the lane the car used) painted arrows. */
    private fun lane(c: Canvas, from: Pair<Double, Double>, to: Pair<Double, Double>, arrows: Boolean) {
        val (x0, y0) = from; val (x1, y1) = to
        val len = Math.hypot(x1 - x0, y1 - y0); if (len < 1.0) return
        val ux = (x1 - x0) / len; val uy = (y1 - y0) / len
        val rx = uy; val ry = -ux
        val hw = com.parktrace.sdk.SimulatedSurroundVision.AISLE / 2
        // drawn in 2.5 m slices so the part behind the camera clips cleanly
        fill.color = AISLE_FLOOR
        var t = 0.0
        while (t < len) {
            val t2 = minOf(len, t + 2.5)
            val xs = doubleArrayOf(x0 + ux * t - rx * hw, x0 + ux * t2 - rx * hw, x0 + ux * t2 + rx * hw, x0 + ux * t + rx * hw)
            val ys = doubleArrayOf(y0 + uy * t - ry * hw, y0 + uy * t2 - ry * hw, y0 + uy * t2 + ry * hw, y0 + uy * t + ry * hw)
            if (polygon(xs, ys, 0.004)) c.drawPath(path, fill)
            t = t2
        }
        line.color = LANE; line.strokeWidth = 3 * dp
        t = 0.5
        while (t < len - 1.0) {
            seg(c, x0 + ux * t, y0 + uy * t, x0 + ux * (t + 1.2), y0 + uy * (t + 1.2), 0.008)
            t += 2.6
        }
        if (!arrows) return
        // painted arrows every 10 m on the right half, pointing the way the car drove in
        fill.color = ARROW
        t = 4.0
        while (t < len - 2.0) {
            val bx = x0 + ux * t + rx * 1.5; val by = y0 + uy * t + ry * 1.5
            val xs = doubleArrayOf(bx + ux * 1.4, bx + rx * 0.6, bx + rx * 0.22, bx - ux * 1.2 + rx * 0.22, bx - ux * 1.2 - rx * 0.22, bx - rx * 0.22, bx - rx * 0.6)
            val ys = doubleArrayOf(by + uy * 1.4, by + ry * 0.6, by + ry * 0.22, by - uy * 1.2 + ry * 0.22, by - uy * 1.2 - ry * 0.22, by - ry * 0.22, by - ry * 0.6)
            if (polygon(xs, ys, 0.01)) c.drawPath(path, fill)
            t += 10.0
        }
    }

    private fun drawPath(c: Canvas, s: ParkingScene) {
        if (s.path.size < 2) return
        line.color = PATH; line.strokeWidth = 5 * dp
        path.reset()
        var started = false
        for ((x, y) in s.path) {
            if (!project(x, y, 0.03)) { started = false; continue }
            if (!started) { path.moveTo(p[0], p[1]); started = true } else path.lineTo(p[0], p[1])
        }
        c.drawPath(path, line)
        val (ex, ey) = s.path.first()
        if (project(ex, ey, 0.05)) {
            fill.color = ENTRY; c.drawCircle(p[0], p[1], 6 * dp, fill)
        }
    }

    private fun drawBays(c: Canvas, s: ParkingScene) {
        for (b in s.bays) {
            val px = b.hy; val py = -b.hx
            val hw = BAY_W / 2; val hd = BAY_D / 2
            val xs = doubleArrayOf(b.x + b.hx * hd + px * hw, b.x + b.hx * hd - px * hw, b.x - b.hx * hd - px * hw, b.x - b.hx * hd + px * hw)
            val ys = doubleArrayOf(b.y + b.hy * hd + py * hw, b.y + b.hy * hd - py * hw, b.y - b.hy * hd - py * hw, b.y - b.hy * hd + py * hw)
            if (b.mine && polygon(xs, ys, 0.01)) { fill.color = MINE_BAY; c.drawPath(path, fill) }
            line.color = if (b.mine) MINE else BAY_LINE; line.strokeWidth = (if (b.mine) 3f else 2f) * dp
            // bay lines: two sides + back (open towards the aisle)
            seg(c, xs[0], ys[0], xs[3], ys[3], 0.01)
            seg(c, xs[1], ys[1], xs[2], ys[2], 0.01)
            seg(c, xs[0], ys[0], xs[1], ys[1], 0.01)
        }
    }

    private fun polygon(xs: DoubleArray, ys: DoubleArray, z: Double): Boolean {
        path.reset()
        for (i in xs.indices) {
            if (!project(xs[i], ys[i], z)) return false
            if (i == 0) path.moveTo(p[0], p[1]) else path.lineTo(p[0], p[1])
        }
        path.close(); return true
    }

    // ------------------------------------------------------------------ meshes (own low-poly models, Tesla-style)

    /** One flat face in object-local coords (f = forward, r = right, z = up); [ref] is the inside point of its part. */
    private class Face(val pts: Array<DoubleArray>, val role: Int, val ref: DoubleArray)

    private class Drawn(val xs: FloatArray, val ys: FloatArray, val color: Int, val depth: Double)

    /** A placed object: either a Kenney car model or one of the simple meshes (pillars, fallback car). */
    private class Placed(
        val x: Double, val y: Double, val hx: Double, val hy: Double,
        val mesh: List<Face>?, val model: CarModel?, val body: Int,
        /** Tesla-style detected vehicle: one matte grey, no lights or trim colours, fades with distance. */
        val mono: Boolean = false,
    )

    private val models: Map<String, CarModel> by lazy { runCatching { CarModels.get(context) }.getOrDefault(emptyMap()) }

    // triangle batch (screen xy, colour, depth), drawn far-to-near with one drawVertices call
    private var tXY = FloatArray(0); private var tCol = IntArray(0); private var tDepth = FloatArray(0); private var tN = 0
    private var outXY = FloatArray(0); private var outCol = IntArray(0); private var keys = LongArray(0)

    private fun addTri(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, color: Int, depth: Float) {
        if (tN == tDepth.size) {
            val cap = maxOf(1024, tN * 2)
            tXY = tXY.copyOf(cap * 6); tCol = tCol.copyOf(cap); tDepth = tDepth.copyOf(cap)
        }
        val o = tN * 6
        tXY[o] = x0; tXY[o + 1] = y0; tXY[o + 2] = x1; tXY[o + 3] = y1; tXY[o + 4] = x2; tXY[o + 5] = y2
        tCol[tN] = color; tDepth[tN] = depth; tN++
    }

    private fun pickModel(b: ParkingScene.Bay): CarModel? {
        if (models.isEmpty()) return null
        // detected vehicles use a few plain body classes, like Tesla's sedan / SUV / van silhouettes
        val names = DETECTED_CLASSES.filter { it in models }.ifEmpty { CarModels.NAMES.filter { it in models } }
        val h = ((b.x * 7.3 + b.y * 3.1).toLong() + b.shade * 11L).let { if (it < 0) -it else it }
        return models[names[(h % names.size).toInt()]]
    }

    private fun drawObjects(c: Canvas, s: ParkingScene) {
        val objs = mutableListOf<Placed>()
        val margin = width * 0.22f
        for (b in s.bays) if (b.occupied && !b.mine) {
            // off-screen cars cost as much as visible ones: skip them
            if (Math.hypot(b.x - s.car.x, b.y - s.car.y) > CAR_RADIUS) continue
            if (!project(b.x, b.y, 0.7) || p[0] < -margin || p[0] > width + margin || p[1] < -margin || p[1] > height + margin) continue
            objs += Placed(b.x, b.y, b.hx, b.hy, CAR.takeIf { models.isEmpty() }, pickModel(b), NEIGHBOURS[b.shade % NEIGHBOURS.size], mono = true)
        }
        val pillarObjs = s.pillars.map { pl -> pl to Placed(pl.x, pl.y, 1.0, 0.0, PILLAR_MESH, null, if (pl.mine) PILLAR_MINE else PILLAR) }
        objs += pillarObjs.map { it.second }
        val gateObjs = mutableListOf<Placed>()
        s.gate?.let { g ->
            val rx = g.hy; val ry = -g.hx
            // booth on the right of the lane, barrier post on the left with the arm raised
            gateObjs += Placed(g.x + rx * 3.9, g.y + ry * 3.9, g.hx, g.hy, BOOTH_MESH, null, BOOTH)
            gateObjs += Placed(g.x - rx * 3.4, g.y - ry * 3.4, g.hx, g.hy, BARRIER_MESH, null, BARRIER)
        }
        objs += gateObjs
        val myModel = models[CarModels.MINE]
        // our car keeps full detail and the brand blue so it stands out among the grey detected vehicles
        val me = Placed(s.car.x, s.car.y, s.car.hx, s.car.hy, CAR.takeIf { myModel == null }, myModel, MINE)
        objs += me

        // camera position in world coords (for back-face culling)
        val ry = -dist * cosP
        val camX = tx + ry * sinY; val camY = ty + ry * cosY; val camZ = dist * sinP

        tN = 0
        val bounds = HashMap<Placed, RectF>()
        val sx = FloatArray(8); val sy = FloatArray(8)
        for (o in objs) {
            val px = o.hy; val py = -o.hx
            val r = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            val model = o.model
            if (model != null) {
                val v = model.v
                for (t in 0 until model.triangles) {
                    val i = t * 9
                    // local (f, r, z) -> world
                    val ax = o.x + o.hx * v[i] + px * v[i + 1]; val ay = o.y + o.hy * v[i] + py * v[i + 1]; val az = v[i + 2].toDouble()
                    val bx = o.x + o.hx * v[i + 3] + px * v[i + 4]; val by = o.y + o.hy * v[i + 3] + py * v[i + 4]; val bz = v[i + 5].toDouble()
                    val cx = o.x + o.hx * v[i + 6] + px * v[i + 7]; val cy = o.y + o.hy * v[i + 6] + py * v[i + 7]; val cz = v[i + 8].toDouble()
                    val ux = bx - ax; val uy = by - ay; val uz = bz - az
                    val wx = cx - ax; val wy = cy - ay; val wz = cz - az
                    var nx = uy * wz - uz * wy; var ny = uz * wx - ux * wz; var nz = ux * wy - uy * wx
                    val len = sqrt(nx * nx + ny * ny + nz * nz); if (len < 1e-12) continue
                    nx /= len; ny /= len; nz /= len
                    val mx = (ax + bx + cx) / 3; val my = (ay + by + cy) / 3; val mz = (az + bz + cz) / 3
                    if (nx * (camX - mx) + ny * (camY - my) + nz * (camZ - mz) <= 0) continue // back face
                    if (!project(ax, ay, az)) continue; val x0 = p[0]; val y0 = p[1]
                    if (!project(bx, by, bz)) continue; val x1 = p[0]; val y1 = p[1]
                    if (!project(cx, cy, cz)) continue; val x2 = p[0]; val y2 = p[1]
                    val raw = model.color[t]
                    if (o.mono) {
                        val k = when {
                            raw == TYRE -> 0.62f
                            model.paint[t] -> 1f
                            isDarkish(raw) -> 0.86f
                            else -> 0.97f
                        }
                        val lit = (0.64 + 0.32 * maxOf(0.0, nx * LX + ny * LY + nz * LZ) + 0.06 * nz).coerceAtMost(1.0).toFloat()
                        val d = depthOf(mx, my, mz)
                        val fade = ((d - dist * 0.85) / 26.0).coerceIn(0.0, 0.62).toFloat()
                        addTri(x0, y0, x1, y1, x2, y2, mix(shade(MONO, k * lit), FADE, fade), d.toFloat())
                        r.union(x0, y0); r.union(x1, y1); r.union(x2, y2)
                        continue
                    }
                    val base = when {
                        model.paint[t] -> tint(o.body, (raw and 0xFF) / 128f)
                        raw == TYRE -> raw
                        // dark grey trim (bumpers, sills) takes most of the body colour: cars read as white like Pleos'
                        isDarkGrey(raw) -> mix(raw, o.body, 0.7f)
                        else -> raw
                    }
                    val lit = (0.5 + 0.5 * maxOf(0.0, nx * LX + ny * LY + nz * LZ)).toFloat()
                    addTri(x0, y0, x1, y1, x2, y2, shade(base, lit), depthOf(mx, my, mz).toFloat())
                    r.union(x0, y0); r.union(x1, y1); r.union(x2, y2)
                }
            } else {
                for (f in o.mesh.orEmpty()) {
                    val n = f.pts.size
                    val X = DoubleArray(n) { o.x + o.hx * f.pts[it][0] + px * f.pts[it][1] }
                    val Y = DoubleArray(n) { o.y + o.hy * f.pts[it][0] + py * f.pts[it][1] }
                    val Z = DoubleArray(n) { f.pts[it][2] }
                    val refX = o.x + o.hx * f.ref[0] + px * f.ref[1]; val refY = o.y + o.hy * f.ref[0] + py * f.ref[1]
                    val ax = X[1] - X[0]; val ay = Y[1] - Y[0]; val az = Z[1] - Z[0]
                    val bx = X[2] - X[0]; val by = Y[2] - Y[0]; val bz = Z[2] - Z[0]
                    var nx = ay * bz - az * by; var ny = az * bx - ax * bz; var nz = ax * by - ay * bx
                    val len = sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 1e-9 } ?: continue
                    nx /= len; ny /= len; nz /= len
                    val cx = X.average(); val cy = Y.average(); val cz = Z.average()
                    if (nx * (cx - refX) + ny * (cy - refY) + nz * (cz - f.ref[2]) < 0) { nx = -nx; ny = -ny; nz = -nz }
                    if (nx * (camX - cx) + ny * (camY - cy) + nz * (camZ - cz) <= 0) continue
                    var ok = true
                    for (i in 0 until n) { if (!project(X[i], Y[i], Z[i])) { ok = false; break }; sx[i] = p[0]; sy[i] = p[1] }
                    if (!ok) continue
                    val lit = (0.42 + 0.58 * maxOf(0.0, nx * LX + ny * LY + nz * LZ)).toFloat()
                    val col = shade(roleColor(f.role, o.body), lit)
                    val d = depthOf(cx, cy, cz).toFloat()
                    for (i in 1 until n - 1) addTri(sx[0], sy[0], sx[i], sy[i], sx[i + 1], sy[i + 1], col, d)
                    for (i in 0 until n) r.union(sx[i], sy[i])
                }
            }
            if (r.left <= r.right) bounds[o] = r
        }

        // ramp / entrance side walls: two-sided vertical strips, lit by their facing
        for (w in s.walls + s.structure) for (i in 0 until w.size - 1) {
            if (Math.hypot(w[i].first - s.car.x, w[i].second - s.car.y) > DRAW_RADIUS + 8) continue
            val (ax, ay) = w[i]; val (bx, by) = w[i + 1]
            if (Math.hypot(bx - ax, by - ay) < 1e-3) continue
            if (!project(ax, ay, 0.0)) continue; val x0 = p[0]; val y0 = p[1]
            if (!project(bx, by, 0.0)) continue; val x1 = p[0]; val y1 = p[1]
            if (!project(bx, by, WALL_H)) continue; val x2 = p[0]; val y2 = p[1]
            if (!project(ax, ay, WALL_H)) continue; val x3 = p[0]; val y3 = p[1]
            val nx = -(by - ay); val ny = bx - ax; val nl = Math.hypot(nx, ny)
            val lit = (0.62 + 0.38 * Math.abs((nx * LX + ny * LY) / nl)).toFloat()
            val col = shade(WALL, lit)
            val d = depthOf((ax + bx) / 2, (ay + by) / 2, WALL_H / 2).toFloat()
            addTri(x0, y0, x1, y1, x2, y2, col, d); addTri(x0, y0, x2, y2, x3, y3, col, d)
            // top edge stripe (yellow-black safety paint)
            if (project(ax, ay, WALL_H) && project(bx, by, WALL_H)) {
                if (project(bx, by, WALL_H + 0.08)) { val x4 = p[0]; val y4 = p[1]
                    if (project(ax, ay, WALL_H + 0.08)) addTri(x3, y3, x2, y2, x4, y4, if (i % 2 == 0) SAFETY else INK, d - 0.01f)
                }
            }
        }

        // far-to-near, one batched call
        if (keys.size < tN) { keys = LongArray(tN); outXY = FloatArray(tN * 6); outCol = IntArray(tN * 3) }
        for (i in 0 until tN) keys[i] = (java.lang.Float.floatToRawIntBits(tDepth[i]).toLong() shl 32) or i.toLong()
        java.util.Arrays.sort(keys, 0, tN)
        var k = 0
        for (j in tN - 1 downTo 0) {
            val i = (keys[j] and 0xFFFFFFFFL).toInt()
            System.arraycopy(tXY, i * 6, outXY, k * 6, 6)
            outCol[k * 3] = tCol[i]; outCol[k * 3 + 1] = tCol[i]; outCol[k * 3 + 2] = tCol[i]
            k++
        }
        if (tN > 0) {
            fill.shader = null; fill.color = Color.WHITE
            c.drawVertices(Canvas.VertexMode.TRIANGLES, tN * 6, outXY, 0, null, 0, outCol, 0, null, 0, 0, fill)
        }

        // labels (always on top)
        for ((pl, o) in pillarObjs) {
            val r = bounds[o] ?: continue
            pl.label?.let { pill(c, it, r.centerX(), r.top - 8 * dp, if (pl.mine) MINE else CHIP, if (pl.mine) Color.WHITE else INK) }
        }
        gateObjs.firstOrNull()?.let { o -> bounds[o]?.let { r -> pill(c, "P 입구", r.centerX(), r.top - 8 * dp, ENTRY, Color.WHITE) } }
        bounds[me]?.let { r -> pill(c, carLabel.ifBlank { "내 차" }, r.centerX(), r.top - 10 * dp, MINE, Color.WHITE) }
    }

    private fun isDarkish(c: Int): Boolean = (Color.red(c) + Color.green(c) + Color.blue(c)) / 3 < 110

    /** Soft contact shadow under every car (two stacked rounded footprints), drawn on the floor before the meshes. */
    private fun drawShadows(c: Canvas, s: ParkingScene) {
        // our spot first: a wide blue glow so the eye lands on it
        run {
            val k = doubleArrayOf(s.car.x, s.car.y, s.car.hx, s.car.hy)
            for ((grow, col) in listOf(1.6 to MINE_GLOW_OUT, 0.9 to MINE_GLOW_MID, 0.35 to MINE_GLOW_IN)) {
                val hl = 2.3 + grow; val hw = 0.95 + grow; val cut = 0.5 + grow
                val fr = listOf(hl to hw - cut, hl - cut to hw, -hl + cut to hw, -hl to hw - cut, -hl to -hw + cut, -hl + cut to -hw, hl - cut to -hw, hl to -hw + cut)
                val px = k[3]; val py = -k[2]
                val xs = DoubleArray(8) { k[0] + k[2] * fr[it].first + px * fr[it].second }
                val ys = DoubleArray(8) { k[1] + k[3] * fr[it].first + py * fr[it].second }
                fill.color = col
                if (polygon(xs, ys, 0.011)) c.drawPath(path, fill)
            }
        }
        val cars = s.bays.filter { it.occupied && !it.mine }.map { doubleArrayOf(it.x, it.y, it.hx, it.hy) } +
            listOf(doubleArrayOf(s.car.x, s.car.y, s.car.hx, s.car.hy))
        for (k in cars) {
            for ((grow, col) in listOf(0.45 to SHADOW_OUT, 0.12 to SHADOW_IN)) {
                val hl = 2.3 + grow; val hw = 0.95 + grow; val cut = 0.5 + grow
                val fr = listOf(hl to hw - cut, hl - cut to hw, -hl + cut to hw, -hl to hw - cut, -hl to -hw + cut, -hl + cut to -hw, hl - cut to -hw, hl to -hw + cut)
                val px = k[3]; val py = -k[2]
                val xs = DoubleArray(8) { k[0] + k[2] * fr[it].first + px * fr[it].second }
                val ys = DoubleArray(8) { k[1] + k[3] * fr[it].first + py * fr[it].second }
                fill.color = col
                if (polygon(xs, ys, 0.012)) c.drawPath(path, fill)
            }
        }
    }

    private fun isDarkGrey(c: Int): Boolean {
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        return maxOf(r, g, b) - minOf(r, g, b) < 12 && (r + g + b) / 3 < 130
    }

    private fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).roundToInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).roundToInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).roundToInt(),
    )

    /** Paint colour scaled by the model's stored brightness (keeps the gradient of the original swatch). */
    private fun tint(color: Int, k: Float): Int = Color.rgb(
        (Color.red(color) * k).roundToInt().coerceIn(0, 255),
        (Color.green(color) * k).roundToInt().coerceIn(0, 255),
        (Color.blue(color) * k).roundToInt().coerceIn(0, 255),
    )

    private fun roleColor(role: Int, body: Int): Int = when (role) {
        R_GLASS -> GLASS
        R_TIRE -> TIRE
        R_RIM -> RIM
        R_HEAD -> HEAD
        R_TAIL -> TAIL
        R_ROOF -> shade(body, 0.93f)
        else -> body
    }

    private fun brackets(c: Canvas, r: RectF) {
        line.color = DETECT; line.strokeWidth = 2 * dp
        val k = minOf(r.width(), r.height()) * 0.25f
        c.drawLine(r.left, r.top, r.left + k, r.top, line); c.drawLine(r.left, r.top, r.left, r.top + k, line)
        c.drawLine(r.right, r.top, r.right - k, r.top, line); c.drawLine(r.right, r.top, r.right, r.top + k, line)
        c.drawLine(r.left, r.bottom, r.left + k, r.bottom, line); c.drawLine(r.left, r.bottom, r.left, r.bottom - k, line)
        c.drawLine(r.right, r.bottom, r.right - k, r.bottom, line); c.drawLine(r.right, r.bottom, r.right, r.bottom - k, line)
    }

    private fun pill(c: Canvas, label: String, cx: Float, bottom: Float, bg: Int, fg: Int) {
        val w = text.measureText(label) + 28 * dp; val h = 38 * dp
        rect.set(cx - w / 2, bottom - h, cx + w / 2, bottom)
        fill.color = bg; c.drawRoundRect(rect, h / 2, h / 2, fill)
        val old = text.color; text.color = fg
        c.drawText(label, rect.left + 14 * dp, rect.bottom - 11 * dp, text)
        text.color = old
    }

    private fun drawLegend(c: Canvas, s: ParkingScene) {
        val y = 30 * dp
        val msg = when {
            s.surroundKnown -> "모의 인식 · 주변 차량 ${s.detectedCars}대 · 기둥 표지 ${s.pillars.count { it.label != null }}개"
            s.aisle != null -> "주행 중 모의 인식 · 통로 양옆 주차 차량 ${s.detectedCars}대"
            s.walls.isNotEmpty() && straightSoFar(s) -> "주차장 진입로 · 램프를 찾는 중"
            s.walls.isNotEmpty() -> "램프 주행 중 · 내려가는 층수를 세는 중"
            else -> "주행 경로 기록 중 · 정차하면 주변을 인식해요"
        }
        c.drawText(msg, 12 * dp, y, small.apply { color = if (s.surroundKnown || s.aisle != null) DETECT else MUTED })
        small.color = MUTED
        val hint = "드래그해서 돌려 보기"
        c.drawText(hint, width - small.measureText(hint) - 12 * dp, height - 10 * dp, small)
    }

    /** Still on the straight approach to the ramp: the recent path has not turned (sum of heading changes < 20 deg). */
    private fun straightSoFar(s: ParkingScene): Boolean {
        var turn = 0.0; var prev = Double.NaN
        var (lx, ly) = s.path.firstOrNull() ?: return false
        for ((x, y) in s.path) {
            if (Math.hypot(x - lx, y - ly) < 1.0) continue
            val a = atan2(y - ly, x - lx)
            if (!prev.isNaN()) { var d = a - prev; while (d > Math.PI) d -= 2 * Math.PI; while (d < -Math.PI) d += 2 * Math.PI; turn += Math.abs(d) }
            prev = a; lx = x; ly = y
        }
        return turn < Math.toRadians(20.0)
    }

    private fun shade(color: Int, f: Float): Int = Color.rgb(
        (Color.red(color) * f).roundToInt().coerceIn(0, 255),
        (Color.green(color) * f).roundToInt().coerceIn(0, 255),
        (Color.blue(color) * f).roundToInt().coerceIn(0, 255),
    )

    companion object {
        // light direction (world, normalised): from above, slightly front-left
        private const val LX = -0.32; private const val LY = 0.42; private const val LZ = 0.85

        private const val R_BODY = 0; private const val R_GLASS = 1; private const val R_TIRE = 2
        private const val R_RIM = 3; private const val R_HEAD = 4; private const val R_TAIL = 5; private const val R_ROOF = 6

        private val GLASS = Color.parseColor("#1A2130")
        private val TIRE = Color.parseColor("#0E1014")
        private val RIM = Color.parseColor("#5B626E")
        private val HEAD = Color.parseColor("#F2F6FF")
        private val TAIL = Color.parseColor("#E5393B")

        private fun v(f: Double, r: Double, z: Double) = doubleArrayOf(f, r, z)

        /** Extruded polygon (footprint in f/r) from z0 to z1: side faces + top. */
        private fun prism(out: MutableList<Face>, fp: List<Pair<Double, Double>>, z0: Double, z1: Double, side: Int, top: Int) {
            val ref = v(fp.map { it.first }.average(), fp.map { it.second }.average(), (z0 + z1) / 2)
            for (i in fp.indices) {
                val (f1, r1) = fp[i]; val (f2, r2) = fp[(i + 1) % fp.size]
                out += Face(arrayOf(v(f1, r1, z0), v(f2, r2, z0), v(f2, r2, z1), v(f1, r1, z1)), side, ref)
            }
            out += Face(fp.map { v(it.first, it.second, z1) }.toTypedArray(), top, ref)
        }

        /** Own low-poly car: chamfered body, glass cabin with raked windshield, 4 wheels, light bar. ~4.6 m x 1.85 m. */
        private val CAR: List<Face> = buildList {
            val l = 2.3; val w = 0.92
            prism(this, listOf(
                l to w - 0.32, l - 0.34 to w, -l + 0.22 to w, -l to w - 0.22,
                -l to -w + 0.22, -l + 0.22 to -w, l - 0.34 to -w, l to -w + 0.32,
            ), 0.30, 0.80, R_BODY, R_BODY)
            // cabin: frustum from the belt line to the roof
            val zb = 0.80; val zt = 1.43
            val b = arrayOf(v(0.78, 0.86, zb), v(-1.62, 0.86, zb), v(-1.62, -0.86, zb), v(0.78, -0.86, zb))
            val t = arrayOf(v(-0.05, 0.68, zt), v(-1.28, 0.68, zt), v(-1.28, -0.68, zt), v(-0.05, -0.68, zt))
            val cref = v(-0.5, 0.0, 1.1)
            add(Face(arrayOf(b[3], b[0], t[0], t[3]), R_GLASS, cref)) // windshield
            add(Face(arrayOf(b[1], b[2], t[2], t[1]), R_GLASS, cref)) // rear window
            add(Face(arrayOf(b[0], b[1], t[1], t[0]), R_GLASS, cref)) // right windows
            add(Face(arrayOf(b[2], b[3], t[3], t[2]), R_GLASS, cref)) // left windows
            add(Face(t, R_ROOF, cref))
            // wheels: octagonal cylinders, axis along r
            for (wf in doubleArrayOf(1.42, -1.45)) for (side in doubleArrayOf(1.0, -1.0)) {
                val rOut = side * (w + 0.04); val rIn = side * (w - 0.22); val zc = 0.34; val rad = 0.34
                val ring = (0 until 8).map { k -> val a = Math.toRadians(22.5 + 45.0 * k); wf + rad * cos(a) to zc + rad * sin(a) }
                val ref = v(wf, (rOut + rIn) / 2, zc)
                for (k in 0 until 8) {
                    val (f1, z1) = ring[k]; val (f2, z2) = ring[(k + 1) % 8]
                    add(Face(arrayOf(v(f1, rIn, z1), v(f2, rIn, z2), v(f2, rOut, z2), v(f1, rOut, z1)), R_TIRE, ref))
                }
                add(Face(ring.map { (f, z) -> v(f, rOut, z) }.toTypedArray(), R_RIM, ref))
            }
            // lights, a hair in front of the body faces
            val hf = l + 0.01
            for (s in doubleArrayOf(1.0, -1.0)) add(Face(arrayOf(v(hf, s * 0.28, 0.58), v(hf, s * 0.56, 0.58), v(hf, s * 0.56, 0.70), v(hf, s * 0.28, 0.70)), R_HEAD, v(0.0, 0.0, 0.6)))
            val tf = -l - 0.01
            add(Face(arrayOf(v(tf, -0.66, 0.62), v(tf, 0.66, 0.62), v(tf, 0.66, 0.69), v(tf, -0.66, 0.69)), R_TAIL, v(0.0, 0.0, 0.6)))
        }

        private val BOOTH_MESH: List<Face> = buildList {
            prism(this, listOf(0.7 to 0.6, -0.7 to 0.6, -0.7 to -0.6, 0.7 to -0.6), 0.0, 2.3, R_BODY, R_ROOF)
            prism(this, listOf(0.85 to 0.75, -0.85 to 0.75, -0.85 to -0.75, 0.85 to -0.75), 2.3, 2.45, R_TAIL, R_TAIL)
        }

        /** Barrier post plus the arm standing up (open). */
        private val BARRIER_MESH: List<Face> = buildList {
            prism(this, listOf(0.25 to 0.25, -0.25 to 0.25, -0.25 to -0.25, 0.25 to -0.25), 0.0, 1.1, R_BODY, R_ROOF)
            prism(this, listOf(0.06 to 0.1, -0.06 to 0.1, -0.06 to -0.02, 0.06 to -0.02), 1.1, 3.6, R_TAIL, R_TAIL)
        }

        private val PILLAR_MESH: List<Face> = buildList {
            val h = 0.32
            prism(this, listOf(h to h, -h to h, -h to -h, h to -h), 0.0, 2.6, R_BODY, R_ROOF)
        }

        // Light theme matching the Pleos driving view: pale floor, white cars, only our car in colour.
        private val BG_TOP = Color.parseColor("#F7F8FA")
        private val BG_BOTTOM = Color.parseColor("#E3E6EA")
        private val INK = Color.parseColor("#2B2F36")
        /** Tyre colour written by tools/convert_car_models.py; never lightened. */
        private val TYRE = Color.parseColor("#1E2126")
        private val MUTED = Color.parseColor("#6B7280")
        private val GRID = Color.parseColor("#14000000")
        private val BAY_LINE = Color.parseColor("#B8BEC6")
        private val PATH = Color.parseColor("#CC2F80FF")
        private val ENTRY = Color.parseColor("#22B36B")
        private val MINE = Color.parseColor("#2F80FF")
        private val MINE_BAY = Color.parseColor("#332F80FF")
        private val ACCENT = Color.parseColor("#2F80FF")
        private val DETECT = Color.parseColor("#2F80FF")
        private val PILLAR = Color.parseColor("#C9CED6")
        private val PILLAR_MINE = Color.parseColor("#AEB6C2")
        private val CHIP = Color.parseColor("#FFFFFF")
        private val MONO = Color.parseColor("#D9DDE3")
        private val FADE = Color.parseColor("#E6E9ED")
        private val MINE_GLOW_OUT = Color.parseColor("#142F80FF")
        private val MINE_GLOW_MID = Color.parseColor("#1F2F80FF")
        private val MINE_GLOW_IN = Color.parseColor("#2E2F80FF")
        private val SHADOW_OUT = Color.parseColor("#12000000")
        private val SHADOW_IN = Color.parseColor("#1C000000")
        private val DETECTED_CLASSES = listOf("sedan", "suv", "van", "suv-luxury")
        private const val WALL_H = 0.9
        /** A new position further away than this is a different place: jump, do not glide. */
        private const val JUMP_METERS = 25.0
        /** Camera turn lag behind the car (seconds). */
        private const val CAM_TAU = 0.8
        private const val FRAME_MS = 33L
        private const val DIST = 17.0
        /** Bays, cars and walls further than this from our car are not drawn. */
        private const val DRAW_RADIUS = 30.0
        /** Parked cars are only modelled this close to our car (they are fully faded beyond it anyway). */
        private const val CAR_RADIUS = 22.0
        private const val SLAB_TILE = 6.0
        private val SLAB = Color.parseColor("#12475569")
        private val WALL = Color.parseColor("#C3C9D1")
        private val SAFETY = Color.parseColor("#F2B33D")
        private val RAMP_FLOOR = Color.parseColor("#1F5B6B7A")
        private val AISLE_FLOOR = Color.parseColor("#1A3C4654")
        private val LANE = Color.parseColor("#E8A82E")
        private val ARROW = Color.parseColor("#E6FFFFFF")
        private val BOOTH = Color.parseColor("#E9ECF0")
        private val BARRIER = Color.parseColor("#F5F6F8")
        private val NEIGHBOURS = intArrayOf(
            // white / pearl / light silver, like the cars in the Pleos driving view
            Color.parseColor("#F5F6F8"), Color.parseColor("#ECEEF1"), Color.parseColor("#E3E6EA"),
            Color.parseColor("#FAFAFA"), Color.parseColor("#DDE1E6"), Color.parseColor("#EFF1F4"),
        )
    }
}
