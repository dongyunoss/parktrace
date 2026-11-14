package com.parktrace.sdk

import com.parktrace.core.ParkingScene
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.random.Random

/**
 * "Surround vision": which bays are taken, where the pillars are, which bay is ours.
 *
 * Like [SignReader], this is the slot for the vehicle cameras. The contest only allows the five public
 * Pleos SDKs (no camera API), so this build ships [SimulatedSurroundVision], which places plausible
 * surroundings around the real dead-reckoned pose. The organisers accept assumed recognition results
 * for the demo (README 4장). A camera implementation only needs to implement this interface.
 */
interface SurroundVision {
    val status: String

    /**
     * @param path dead-reckoned path in meters, oldest first (real data)
     * @param spot pillar label read from signs, e.g. "C-15"
     * @param seed keeps the simulated surroundings stable for one parking
     * @param surround false while driving: return only path + car
     * @param reversed the car backed in: it faces the aisle (opposite to its last movement)
     */
    fun scan(path: List<Pair<Double, Double>>, spot: String?, seed: Long, surround: Boolean, reversed: Boolean = false, gateAt: Double? = null): ParkingScene?
}

class SimulatedSurroundVision : SurroundVision {

    override val status = "모의 인식 (카메라 미구현)"

    /**
     * @param gateAt metres along the path where the entrance gate is, when known. A registered garage
     *   switches on at its geofence edge (~80 m before the entrance), so the gate lies that far along.
     */
    override fun scan(path: List<Pair<Double, Double>>, spot: String?, seed: Long, surround: Boolean, reversed: Boolean, gateAt: Double?): ParkingScene? {
        if (path.isEmpty()) return null
        val (cx, cy) = path.last()
        // (hx, hy) = direction of the last movement = towards the wall of our bay; the nose points that way
        // when we drove in forwards, and to the aisle when we backed in.
        val (hx, hy) = heading(path)
        val car = if (reversed) ParkingScene.Pose(cx, cy, -hx, -hy) else ParkingScene.Pose(cx, cy, hx, hy)
        val tail = tail(path, TAIL_METERS)
        val gate = gateAt?.let { gateAlong(path, it) } ?: gate(path, cx, cy)
        val env = environment(path, seed, cx, cy, gate, snapToCar = surround)
        // Levels: the entrance is at street level. Once the car has gone down the ramp (well past the gate, or
        // already on a parking level) the gate is on a floor above and is no longer part of the picture.
        val gateIdx = gate?.let { g -> resample(path, 1.0).let { pts -> pts.size - 1 - pts.indices.minByOrNull { hypot(pts[it].first - g.x, pts[it].second - g.y) }!! } }
        val shownGate = gate?.takeIf { env.frame == null && (gateIdx ?: 0) < GATE_VISIBLE_METERS }
        if (surround && env.frame != null) return parkedInAisle(car, tail, env, null, spot)
        if (!surround) {
            return ParkingScene(car, tail, env.bays, env.pillars, surroundKnown = false, walls = env.walls, aisle = env.aisle, gate = shownGate,
                structure = env.structure, lanes = env.lanes, floor = env.floor)
        }

        val px = hy; val py = -hx // unit vector to the car's right
        val rnd = Random(seed)
        val bays = mutableListOf<ParkingScene.Bay>()
        // Our row: neighbours mostly park nose-in; our own bay shows how we actually parked.
        for (k in -3..3) {
            val mine = k == 0
            bays += ParkingScene.Bay(
                cx + px * k * BAY_W, cy + py * k * BAY_W, if (mine) car.hx else hx, if (mine) car.hy else hy,
                occupied = mine || rnd.nextFloat() < 0.85f, mine = mine,
                shade = rnd.nextInt(4), confidence = 0.86f + rnd.nextFloat() * 0.12f,
            )
        }
        // Opposite row across the aisle behind us, facing the other way.
        val back = BAY_D + AISLE
        for (k in -3..3) {
            bays += ParkingScene.Bay(
                cx - hx * back + px * k * BAY_W, cy - hy * back + py * k * BAY_W, -hx, -hy,
                occupied = rnd.nextFloat() < 0.8f, mine = false,
                shade = rnd.nextInt(4), confidence = 0.84f + rnd.nextFloat() * 0.14f,
            )
        }
        // Pillars every 3 bays on the front edge of each row; ours is at the right edge of our bay.
        val parsed = spot?.let { SPOT.matchEntire(it) }
        val prefix = parsed?.groupValues?.get(1)
        val number = parsed?.groupValues?.get(2)?.toIntOrNull()
        val pillars = mutableListOf<ParkingScene.Pillar>()
        val front = BAY_D / 2 + PILLAR_GAP
        for (j in -1..1) {
            val off = BAY_W / 2 + j * 3 * BAY_W
            val label = when {
                prefix != null && number != null && number + j > 0 -> "$prefix-${number + j}"
                j == 0 -> spot
                else -> null
            }
            pillars += ParkingScene.Pillar(cx + hx * front + px * off, cy + hy * front + py * off, label, mine = j == 0 && spot != null)
            pillars += ParkingScene.Pillar(cx - hx * (back + front) + px * off, cy - hy * (back + front) + py * off, null, mine = false)
        }
        // Keep the aisle seen while driving, minus whatever the close-up scan around the car replaces.
        val extraBays = env.bays.filter { e -> bays.none { hypot(it.x - e.x, it.y - e.y) < 3.2 } }
        val extraPillars = env.pillars.filter { e -> pillars.none { hypot(it.x - e.x, it.y - e.y) < 2.6 } }
        return ParkingScene(car, tail, bays + extraBays, pillars + extraPillars, surroundKnown = true,
            walls = env.walls, aisle = env.aisle, gate = shownGate, structure = env.structure, lanes = env.lanes, floor = env.floor)
    }

    private class Env(
        val bays: List<ParkingScene.Bay>,
        val pillars: List<ParkingScene.Pillar>,
        val walls: List<List<Pair<Double, Double>>>,
        val aisle: Pair<Pair<Double, Double>, Pair<Double, Double>>?,
        /** Aisle frame: origin (ax, ay) and direction (ux, uy); right = (uy, -ux). */
        val frame: DoubleArray? = null,
        val structure: List<List<Pair<Double, Double>>> = emptyList(),
        val lanes: List<Pair<Pair<Double, Double>, Pair<Double, Double>>> = emptyList(),
        val floor: ParkingScene.Floor? = null,
    )

    /**
     * Parked next to a known aisle: the rows stay aligned with the aisle, the bay nearest the car becomes ours
     * (car snapped into it, nose in or backed in), and the pillars on our side get labels around the spot read.
     */
    private fun parkedInAisle(car: ParkingScene.Pose, tail: List<Pair<Double, Double>>, env: Env, gate: ParkingScene.Pose?, spot: String?): ParkingScene {
        val (ax, ay, ux, uy) = env.frame!!.let { listOf(it[0], it[1], it[2], it[3]) }
        val rx = uy; val ry = -ux
        val lat = (car.x - ax) * rx + (car.y - ay) * ry
        val side = if (lat >= 0) 1.0 else -1.0
        fun latOf(x: Double, y: Double) = (x - ax) * rx + (y - ay) * ry
        fun tOf(x: Double, y: Double) = (x - ax) * ux + (y - ay) * uy
        val mineIdx = env.bays.indices.filter { latOf(env.bays[it].x, env.bays[it].y) * side > 0 }
            .minByOrNull { hypot(env.bays[it].x - car.x, env.bays[it].y - car.y) }
        val wallX = rx * side; val wallY = ry * side
        val nose = if (car.hx * wallX + car.hy * wallY >= 0) 1.0 else -1.0
        var snapped = car
        val bays = env.bays.mapIndexed { i, b ->
            if (i != mineIdx) b else {
                snapped = ParkingScene.Pose(b.x, b.y, wallX * nose, wallY * nose)
                b.copy(occupied = true, mine = true, hx = wallX * nose, hy = wallY * nose)
            }
        }
        val parsed = spot?.let { SPOT.matchEntire(it) }
        val prefix = parsed?.groupValues?.get(1)
        val number = parsed?.groupValues?.get(2)?.toIntOrNull()
        val carT = tOf(snapped.x, snapped.y)
        val ours = env.pillars.filter { latOf(it.x, it.y) * side > 0 }.sortedBy { tOf(it.x, it.y) }
        val k0 = ours.indices.minByOrNull { kotlin.math.abs(tOf(ours[it].x, ours[it].y) - carT) } ?: -1
        // numbers grow the way the car drove (C-12, C-13, ... along the aisle)
        val labelled = ours.mapIndexed { k, pl ->
            val label = when {
                k == k0 -> spot
                prefix != null && number != null && kotlin.math.abs(k - k0) == 1 && number + (k - k0) > 0 -> "$prefix-${number + (k - k0)}"
                else -> null
            }
            pl.copy(label = label, mine = k == k0 && spot != null)
        }
        val pillars = labelled + env.pillars.filter { latOf(it.x, it.y) * side <= 0 }
        return ParkingScene(snapped, tail + (snapped.x to snapped.y), bays, pillars, surroundKnown = true, walls = env.walls, aisle = env.aisle, gate = gate,
            structure = env.structure, lanes = env.lanes, floor = env.floor)
    }

    /**
     * What the cameras would have seen on the way in: side walls along the ramp, then the parking rows on both
     * sides of the aisle once the car drives straight on a level. Stable for one visit (seeded per bay index).
     */
    private fun environment(path: List<Pair<Double, Double>>, seed: Long, cx: Double, cy: Double, gate: ParkingScene.Pose?, snapToCar: Boolean): Env {
        // Resampled from the very start so the points (and the level built on them) do not move as the path grows.
        val pts = resample(path, 1.0).takeLast(ENV_POINTS)
        if (pts.size < 3) return Env(emptyList(), emptyList(), emptyList(), null)
        val ang = (0 until pts.size - 1).map { atan2(pts[it + 1].second - pts[it].second, pts[it + 1].first - pts[it].first) }

        // Most recent straight run (>= 8 m) that comes after a turn of >= 90 deg (ramp / corner): the aisle.
        var aisleStart = -1; var aisleEnd = -1
        var end = ang.size - 1
        while (end >= 0 && aisleStart < 0) {
            var start = end
            while (start > 0 && abs(wrap(ang[start - 1] - ang[end])) < STRAIGHT_TOL) start--
            val turnBefore = (1..start).sumOf { abs(wrap(ang[it] - ang[it - 1])) }
            if (end - start + 1 >= 8 && turnBefore >= Math.toRadians(70.0)) { aisleStart = start; aisleEnd = end + 1 }
            end = start - 1
        }

        // Ramp side walls from the entrance gate up to [endIdx] (none while still on the street).
        val wallStart = gate?.let { g -> pts.indices.minByOrNull { hypot(pts[it].first - g.x, pts[it].second - g.y) } }
        fun rampWalls(endIdx: Int): List<List<Pair<Double, Double>>> {
            if (wallStart == null || endIdx - wallStart < 2) return emptyList()
            // only the stretch of ramp just behind the car: what is further back is on the floor above
            val from = maxOf(wallStart, endIdx - RAMP_VISIBLE_METERS)
            return doubleArrayOf(1.0, -1.0).map { side ->
                (from..endIdx).map { i ->
                    val a = ang[minOf(i, ang.size - 1)]
                    val rx = kotlin.math.sin(a); val ry = -kotlin.math.cos(a) // right of travel
                    pts[i].first + rx * side * RAMP_HALF to pts[i].second + ry * side * RAMP_HALF
                }
            }
        }
        if (aisleStart < 0) return Env(emptyList(), emptyList(), rampWalls(pts.size - 1), null)

        // ---- the parking level, laid out around the aisle the car is driving on -----------------------------
        //   wall | row | side aisle | row row | MAIN AISLE | row row | side aisle | row | wall
        // The car comes in through an opening in the back wall; a cross aisle joins the three aisles at the far end.
        // Aisle direction from the clean middle of the straight run (its ends still carry a bit of the turn).
        val third = (aisleEnd - aisleStart) / 3
        val (mx, my) = pts[aisleStart + third]; val (bx, by) = pts[aisleEnd - third / 2]
        val len = hypot(bx - mx, by - my)
        val ux = (bx - mx) / len; val uy = (by - my) / len
        val rx = uy; val ry = -ux
        // Origin of the level: the corner where the straight the car came in on meets the aisle line. That is
        // a fixed place in the building, so the bays do not depend on where the path happened to be sampled.
        var ax = pts[aisleStart].first; var ay = pts[aisleStart].second
        run {
            var e = aisleStart - 1
            while (e >= 3) {
                var st = e
                while (st > 0 && abs(wrap(ang[st - 1] - ang[e])) < STRAIGHT_TOL) st--
                val n = e - st + 1
                val cross = abs(kotlin.math.sin(wrap(ang[(st + e) / 2] - atan2(uy, ux))))
                if (n >= 6 && cross > 0.7) {
                    val (px, py) = pts[st + n / 3]; val (qx, qy) = pts[st + 2 * n / 3]
                    val vl = hypot(qx - px, qy - py)
                    val vx = (qx - px) / vl; val vy = (qy - py) / vl
                    // (px, py) + v * k = (mx, my) + u * m
                    val det = vx * (-uy) - vy * (-ux)
                    if (abs(det) > 0.5) {
                        val k = ((mx - px) * (-uy) - (my - py) * (-ux)) / det
                        ax = px + vx * k; ay = py + vy * k
                    }
                    return@run
                }
                e = st - 1
            }
        }
        val carT = (cx - ax) * ux + (cy - ay) * uy
        // The bays never move: the level is fixed once seen, and the car parks into one of them.
        val shift = 0.0
        fun world(t: Double, lat: Double) = ax + ux * t + rx * lat to ay + uy * t + ry * lat
        val count = maxOf(ROW_BAYS + 2, kotlin.math.ceil((carT + 20.0) / BAY_W).toInt())
        // back wall just behind the point where the car straightened into the aisle; bays start after it
        val tBack = (pts[aisleStart].first - ax) * ux + (pts[aisleStart].second - ay) * uy + BACK_WALL
        val first = kotlin.math.ceil((tBack + 0.2) / BAY_W).toInt()
        val tEnd = count * BAY_W + END_AISLE + shift
        val half = AISLE / 2 + BAY_D * 3 + AISLE + WALL_GAP

        // what was driven before the aisle (ramp, corner): nothing is built on it
        val ramp = pts.subList(0, aisleStart + 1)
        // driven on this level: a bay the car has moved into cannot have had a car in it
        val driven = pts.subList(aisleStart, pts.size)
        fun nearRamp(x: Double, y: Double, d: Double) = ramp.any { hypot(it.first - x, it.second - y) < d }
        fun inside(x: Double, y: Double): Boolean {
            val t = (x - ax) * ux + (y - ay) * uy; val lat = (x - ax) * rx + (y - ay) * ry
            return t > tBack && t < tEnd && abs(lat) < half
        }
        val rampEnd = (wallStart ?: 0).let { w -> (w..aisleStart).firstOrNull { inside(pts[it].first, pts[it].second) } ?: aisleStart }
        val walls = rampWalls(rampEnd)

        val bays = mutableListOf<ParkingScene.Bay>()
        val pillars = mutableListOf<ParkingScene.Pillar>()
        // (distance from the main aisle centre, nose direction relative to "away from the main aisle")
        val rows = listOf(AISLE / 2 + BAY_D / 2 to 1.0, AISLE / 2 + BAY_D * 1.5 to -1.0, AISLE * 1.5 + BAY_D * 2.5 to 1.0)
        for (i in first until count) for (side in intArrayOf(1, -1)) {
            val t = (i + 0.5) * BAY_W + shift
            rows.forEachIndexed { row, (off, face) ->
                val (x, y) = world(t, side * off)
                if (nearRamp(x, y, 4.5)) return@forEachIndexed
                val rnd = Random(seed * 31 + i * 7L + side + row * 1013L)
                // Next to the main aisle, three bays in every six are always free: somewhere to park, with nobody
                // on either side of it (so no neighbour has to make way while the car backs in).
                val free = (row == 0 && i % FREE_EVERY <= 2) || driven.any { hypot(it.first - x, it.second - y) < 1.5 }
                bays += ParkingScene.Bay(x, y, rx * side * face, ry * side * face,
                    occupied = !free && rnd.nextFloat() < 0.78f, mine = false, shade = rnd.nextInt(4), confidence = 0.9f)
            }
            if (i % 3 == 0) {
                val (x, y) = world(i * BAY_W + shift, side * (AISLE / 2 + BAY_D + PILLAR_GAP))
                if (!nearRamp(x, y, 4.0)) pillars += ParkingScene.Pillar(x, y, null, mine = false)
            }
        }

        // outer walls, sampled every metre, opened where the car came through
        val ring = mutableListOf<Pair<Double, Double>>()
        fun edge(t0: Double, l0: Double, t1: Double, l1: Double) {
            val n = maxOf(1, hypot(t1 - t0, l1 - l0).toInt())
            for (k in 0 until n) ring += world(t0 + (t1 - t0) * k / n, l0 + (l1 - l0) * k / n)
        }
        edge(tBack, -half, tBack, half); edge(tBack, half, tEnd, half)
        edge(tEnd, half, tEnd, -half); edge(tEnd, -half, tBack, -half)
        ring += ring.first()
        val structure = mutableListOf<List<Pair<Double, Double>>>()
        var run = mutableListOf<Pair<Double, Double>>()
        for (q in ring) {
            if (nearRamp(q.first, q.second, RAMP_HALF + 0.4)) {
                if (run.size >= 2) structure += run
                run = mutableListOf()
            } else run += q
        }
        if (run.size >= 2) structure += run

        val aisle = world(tBack, 0.0) to world(tEnd - 0.4, 0.0)
        val sideLat = AISLE + BAY_D * 2
        val crossT = tEnd - END_AISLE / 2
        val lanes = listOf(
            world(first * BAY_W, sideLat) to world(tEnd - 0.4, sideLat),
            world(first * BAY_W, -sideLat) to world(tEnd - 0.4, -sideLat),
            world(crossT, -(half - WALL_GAP)) to world(crossT, half - WALL_GAP),
        )
        val (fx, fy) = world(tBack, -half)
        val slab = ParkingScene.Floor(fx, fy, ux, uy, tEnd - tBack, half * 2)
        return Env(bays, pillars, walls, aisle, doubleArrayOf(ax, ay, ux, uy), structure, lanes, slab)
    }

    /**
     * Entrance barrier: a few meters before the first real turn (the ramp starts there); before any turn, the
     * first point of the path.
     */
    /** Gate [at] metres along the path; if the car is not there yet, ahead of it on its current heading. */
    private fun gateAlong(path: List<Pair<Double, Double>>, at: Double): ParkingScene.Pose? {
        val pts = resample(path, 1.0)
        if (pts.size < 3) return null
        var d = 0.0
        for (i in 1 until pts.size) {
            val (ax, ay) = pts[i - 1]; val (bx, by) = pts[i]
            val seg = hypot(bx - ax, by - ay)
            if (d + seg >= at) {
                val k = (at - d) / seg
                return ParkingScene.Pose(ax + (bx - ax) * k, ay + (by - ay) * k, (bx - ax) / seg, (by - ay) / seg)
            }
            d += seg
        }
        val ahead = at - d
        if (ahead > GATE_LOOKAHEAD) return null
        val (hx, hy) = heading(path)
        val (cx, cy) = pts.last()
        return ParkingScene.Pose(cx + hx * ahead, cy + hy * ahead, hx, hy)
    }

    private fun gate(path: List<Pair<Double, Double>>, cx: Double, cy: Double): ParkingScene.Pose? {
        val pts = resample(path, 1.0)
        if (pts.size < 3) return null
        val ang = (0 until pts.size - 1).map { atan2(pts[it + 1].second - pts[it].second, pts[it + 1].first - pts[it].first) }
        val turn = ang.indexOfFirst { abs(wrap(it - ang[0])) > Math.toRadians(20.0) }
        // Usually the gate is where garage mode began (GPS lost under the entrance canopy), with the
        // straight ramp behind it. Registered garages switch on ~100 m early: then the gate sits a
        // little before the first turn instead of out on the street.
        val g = if (turn > LONG_APPROACH) turn - GATE_BEFORE_TURN else 0
        val (x, y) = pts[g]
        // Garage mode starts as the car goes under the entrance roof; the barrier is a few metres further in.
        val ahead = if (g == 0) GATE_AHEAD else 0.0
        val c = kotlin.math.cos(ang[g]); val sn = kotlin.math.sin(ang[g])
        return ParkingScene.Pose(x + c * ahead, y + sn * ahead, c, sn)
    }

    private fun resample(path: List<Pair<Double, Double>>, step: Double): List<Pair<Double, Double>> {
        if (path.isEmpty()) return path
        val out = mutableListOf(path.first())
        for (q in path) if (hypot(q.first - out.last().first, q.second - out.last().second) >= step) out += q
        if (out.last() != path.last()) out += path.last()
        return out
    }

    private fun wrap(a: Double): Double {
        var x = a
        while (x > Math.PI) x -= 2 * Math.PI
        while (x < -Math.PI) x += 2 * Math.PI
        return x
    }

    private fun heading(path: List<Pair<Double, Double>>): Pair<Double, Double> {
        val (lx, ly) = path.last()
        for (i in path.indices.reversed()) {
            val dx = lx - path[i].first; val dy = ly - path[i].second
            val d = hypot(dx, dy)
            if (d > 1.0) return dx / d to dy / d
        }
        return 0.0 to 1.0
    }

    private fun tail(path: List<Pair<Double, Double>>, maxMeters: Double): List<Pair<Double, Double>> {
        var len = 0.0
        var start = path.size - 1
        while (start > 0 && len < maxMeters) {
            len += hypot(path[start].first - path[start - 1].first, path[start].second - path[start - 1].second)
            start--
        }
        return path.subList(start, path.size).toList()
    }

    companion object {
        const val BAY_W = 2.5
        const val BAY_D = 5.0
        const val AISLE = 6.0
        private const val PILLAR_GAP = 0.45
        private const val TAIL_METERS = 30.0
        private const val GATE_VISIBLE_METERS = 26
        private const val RAMP_VISIBLE_METERS = 26
        private const val ENV_POINTS = 70
        private const val FREE_EVERY = 6
        private const val GATE_BEFORE_TURN = 30
        private const val LONG_APPROACH = 45
        private const val GATE_LOOKAHEAD = 120.0
        private const val GATE_AHEAD = 9.5
        private const val RAMP_HALF = 3.2
        /** Bays per row on a level (grows if the car drives further). */
        private const val ROW_BAYS = 24
        /** Back wall of the level, metres before the point where the car straightened into the aisle. */
        private const val BACK_WALL = -1.5
        /** Cross aisle between the last bays and the far wall. */
        private const val END_AISLE = 6.5
        private const val WALL_GAP = 0.3
        private val STRAIGHT_TOL = Math.toRadians(12.0)
        private val SPOT = Regex("^([A-Z]+)-?(\\d+)$")
    }
}
