package com.parktrace.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Locale
import com.parktrace.ParkTraceApp
import com.parktrace.R
import com.parktrace.core.DeadReckoner
import com.parktrace.core.FeePolicy
import com.parktrace.core.FloorEstimate
import com.parktrace.core.ParkingDetector
import com.parktrace.core.Signal
import com.parktrace.core.SignTextParser
import com.parktrace.core.SpotVoter
import com.parktrace.core.ParkingScene
import com.parktrace.core.floorLabel
import com.parktrace.data.FeeBook
import com.parktrace.data.GarageRegistry
import com.parktrace.data.ParkingRecord
import com.parktrace.data.ParkingRepository
import com.parktrace.sdk.Announcer
import com.parktrace.sdk.LocationSource
import com.parktrace.sdk.NaviSource
import com.parktrace.sdk.SimulatedSignReader
import com.parktrace.sdk.SimulatedSurroundVision
import com.parktrace.sdk.SurroundVision
import com.parktrace.sdk.VehicleSource
import com.parktrace.ui.MainActivity

/**
 * Wires every source into the [ParkingDetector]:
 *
 *  Vehicle SDK ─┐
 *  FusedLocation┴─► ParkingDetector ──► garage entered ──► sign reader ON ──► SpotVoter
 *                                     └► parked ──────────► decide floor/pillar ──► save + TTS
 *
 * The sign reader is [SimulatedSignReader] in this build: the camera is not implemented yet
 * (향후 계획). Sign text comes from the demo scenario or the SIM_SIGN debug broadcast.
 */
class ParkingMonitorService : Service() {

    private val main = Handler(Looper.getMainLooper())
    /** All Pleos SDK calls (init, register, speak) are IPCs; on a busy head unit they can block for seconds. */
    private val sdkThread = HandlerThread("ParkTrace-sdk").apply { start() }
    private val sdk = Handler(sdkThread.looper)
    private lateinit var repo: ParkingRepository
    private lateinit var garages: GarageRegistry
    private lateinit var vehicle: VehicleSource
    private lateinit var location: LocationSource
    private lateinit var navi: NaviSource
    private lateinit var announcer: Announcer
    private lateinit var signReader: SimulatedSignReader
    private lateinit var fees: FeeBook
    /** Camera slot for bays / neighbouring cars / pillars (simulated in this build). */
    private val vision: SurroundVision = SimulatedSurroundVision()
    /** Garage entry time of the current visit. */
    private var enteredAt: Long? = null

    private val voter = SpotVoter()
    private lateinit var detector: ParkingDetector
    private var lastTripMeters = 0.0
    /** While the demo runs, real vehicle signals are ignored so the scenario is deterministic. */
    @Volatile var demoMode = false
        private set

    private val tick = object : Runnable {
        override fun run() {
            if (!demoMode) detector.onSignal(Signal.Tick(System.currentTimeMillis()))
            main.postDelayed(this, 1000)
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        repo = ParkingRepository(this)
        garages = GarageRegistry(this)
        detector = ParkingDetector(listener = detectorListener)
        vehicle = VehicleSource(this, sdkThread.looper) { realSignal(it) }
        location = LocationSource(this, garages) { realSignal(it) }
        navi = NaviSource(this)
        announcer = Announcer(this)
        signReader = SimulatedSignReader()
        fees = FeeBook(this)

        goForeground()
        sdk.post { vehicle.start(); location.start(); navi.start(); announcer.start() }
        ParkTraceState.service = this
        val latest = repo.latest()
        ParkTraceState.update { it.copy(latest = latest, cameraStatus = signReader.status, scene = latest?.let { r -> sceneFor(r) }) }
        main.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SIM_ENTRY) manualGarageEntry()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        ParkTraceState.service = null
        signReader.release()
        sdk.post { vehicle.stop(); location.stop(); navi.stop(); announcer.stop() }
        sdkThread.quitSafely()
        super.onDestroy()
    }

    private fun goForeground() {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = Notification.Builder(this, ParkTraceApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_parktrace)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("주차 위치를 자동으로 기억하는 중")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        var type = 0
        if (Build.VERSION.SDK_INT >= 29) {
            if (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION))
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 29 && type != 0) startForeground(NOTI_ID, n, type) else startForeground(NOTI_ID, n)
        }.onFailure { Log.w(TAG, "foreground start failed, running as plain service", it) }
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ signals

    private fun realSignal(s: Signal) {
        if (demoMode) return
        main.post { process(s) }
    }

    private fun process(s: Signal) {
        if (s is Signal.TripDistance) lastTripMeters = s.meters.toDouble()
        // After parking: the door closing means the driver has left; the next time it opens they are back.
        if (detector.phase == ParkingDetector.Phase.PARKED && s is Signal.DriverDoor) {
            val real = android.os.SystemClock.elapsedRealtime()
            if (!s.open) { if (!driverAway) { driverAway = true; awaySince = real } }
            else if (driverAway && real - awaySince >= MIN_AWAY_MS) { driverAway = false; notifyReturn(System.currentTimeMillis()) }
        }
        // The car stopping just before the estimated barrier position is the barrier: pin the gate to it.
        if (s is Signal.Brake && s.pressed && detector.phase == ParkingDetector.Phase.IN_GARAGE) {
            val ui = ParkTraceState.ui
            val gate = ui.gateMeters; val d = ui.path.lastOrNull()?.distance
            if (gate != null && d != null && d > gate - 16.0 && d < gate + 4.0) {
                ParkTraceState.update { it.copy(gateMeters = d + GATE_STOP_GAP).let { u -> u.copy(scene = liveScene(u, u.liveDecision?.spot, surround = false)) } }
            }
        }
        // Parking mode = backing in (gear R). The car's own surround-camera screen has priority then:
        // this app never brings itself to the front until the gear is in P (see onStopped).
        if (s is Signal.Gear) {
            val was = ParkTraceState.ui.parkingMode
            ParkTraceState.update { it.copy(parkingMode = s.reverse) }
            // Backing into the bay: hand the screen to the car's own camera view (it comes back at P).
            // With a real gear signal the car opens its camera view by itself in R; opening it a second time from
            // here made the emulator crawl (R to P took 33 s). The in-app demo has no real gear: it shows its own
            // simulated rear-camera picture of a car park instead (the car's camera view has a fixed road video).
            if (s.reverse && !was && detector.phase == ParkingDetector.Phase.IN_GARAGE) {
                if (demoMode) showSimulatedCamera() else {
                    // the car's camera view is coming up over this app: stop drawing behind it
                    ParkTraceState.update { it.copy(cameraUp = true) }
                    watchCamera()
                }
            }
            // out of R again (parked, or driving on): this app comes back
            if (!s.reverse && was && ParkTraceState.ui.cameraUp) returnFromCamera()
        }
        detector.onSignal(s)
    }

    /** true once the driver has got out and closed the door after parking. */
    private var driverAway = false
    private var awaySince = 0L

    /** Used by the demo scenario (main thread). */
    fun inject(s: Signal) = process(s)

    /**
     * Simulated sign (SIM_SIGN debug broadcast): behaves as if the camera had just read [text] ("|" separates lines).
     * Only accepted while the car is inside a garage, like the real reader would be.
     */
    fun simulateSign(text: String) {
        if (demoMode) return
        if (!signReader.feed(text)) {
            ParkTraceState.update { it.copy(message = "표지판은 주차장 안에서만 읽어요. 먼저 '주차장 진입'을 누르세요") }
        }
    }

    /** Recognised sign text: from the simulated reader, the demo scenario, or (later) the camera. */
    fun onSignText(lines: List<String>, t: Long) {
        val obs = SignTextParser.parse(lines.joinToString("\n"))
        if (obs.isEmpty()) return
        main.post {
            if (detector.phase != ParkingDetector.Phase.IN_GARAGE && detector.phase != ParkingDetector.Phase.STOPPED) return@post
            voter.add(lastTripMeters, t, obs)
            val readable = obs.joinToString("  ") { o -> listOfNotNull(o.floorLevel?.let { floorLabel(it) }, o.spot).joinToString(" ") }
            val decision = voter.decide(lastTripMeters)
            ParkTraceState.update {
                val scene = if (detector.phase == ParkingDetector.Phase.STOPPED) liveScene(it, decision?.spot, surround = true) else it.scene
                it.copy(lastSignText = readable, liveDecision = decision, scene = scene)
            }
        }
    }

    fun manualGarageEntry() = process(Signal.ManualGarageEntry(System.currentTimeMillis()))

    fun setDemoMode(on: Boolean) {
        demoMode = on
        if (on) {
            stopCamera()
            detector = ParkingDetector(listener = detectorListener)
            voter.clear()
            lastTripMeters = 0.0
        } else {
            detector = ParkingDetector(listener = detectorListener)
        }
        enteredAt = null
        ParkTraceState.update {
            it.copy(
                demoRunning = on, path = emptyList(), liveDecision = null, lastSignText = null, estimate = null, entryCue = null,
                enteredAt = null, feePolicy = null, scene = if (on) null else it.latest?.let { r -> sceneFor(r) },
            )
        }
    }

    // ------------------------------------------------------------------ sign reader (camera placeholder)

    private fun startCamera() {
        if (demoMode) { ParkTraceState.update { it.copy(cameraStatus = "시연: 표지판 인식 시뮬레이션 (카메라 미구현)") }; return }
        signReader.start { lines, t -> onSignText(lines, t) }
        ParkTraceState.update { it.copy(cameraStatus = signReader.status) }
    }

    private fun stopCamera() {
        signReader.stop()
        ParkTraceState.update { it.copy(cameraStatus = signReader.status) }
    }

    // ------------------------------------------------------------------ detector events

    private val detectorListener = object : ParkingDetector.Listener {
        override fun onPhaseChanged(phase: ParkingDetector.Phase) {
            ParkTraceState.update { it.copy(phase = phase) }
        }

        override fun onGarageEntered(cue: ParkingDetector.EntryCue, entrance: ParkingDetector.Anchor?, garageName: String?) {
            val t0 = System.currentTimeMillis()
            voter.clear()
            navi.refresh()
            startCamera()
            val now = System.currentTimeMillis()
            Log.d(TAG, "garage entered ($cue): setup ${now - t0} ms")
            enteredAt = now
            val place = if (demoMode) DEMO_PLACE else garageName ?: navi.placeName()
            // A registered garage switches on at its geofence edge. How far ahead the barrier is comes from the
            // GPS distance to the registered entrance (the roof edge) plus the few metres from there to the barrier.
            val gateMeters = if (cue == ParkingDetector.EntryCue.GEOFENCE && entrance != null) {
                garages.all().firstOrNull { it.name == garageName }?.let { g ->
                    // the fix can be stale when the alert fires; then fall back to the geofence radius
                    val d = ParkingDetector.haversine(entrance.lat, entrance.lon, g.lat, g.lon)
                    (if (d in 20.0..g.radiusMeters + 30.0) d else g.radiusMeters - 5.0) + GATE_AFTER_ENTRANCE
                } ?: GEOFENCE_GATE_METERS
            } else null
            Log.i(TAG, "gate ${gateMeters?.let { "%.1f".format(it) }} m ahead")
            ParkTraceState.update {
                it.copy(
                    gateMeters = gateMeters,
                    entryCue = cue, placeName = place, path = emptyList(), liveDecision = null, lastSignText = null,
                    enteredAt = now, feePolicy = fees.policyFor(place), scene = null,
                    message = "주차장 진입: 층·구역·요금을 기록하는 중",
                )
            }
            if (!demoMode) bringAppToFront()
            notifyEntry(place, fees.policyFor(place), now)
        }

        override fun onTrace(points: List<DeadReckoner.Point>, estimate: FloorEstimate) {
            ParkTraceState.update {
                val p = it.copy(path = points.toList(), estimate = estimate)
                // Once stopped, keep the surroundings on screen instead of the bare path.
                if (detector.phase == ParkingDetector.Phase.STOPPED) p else p.copy(scene = liveScene(p, null, surround = false))
            }
        }

        override fun onStopped(estimate: FloorEstimate) {
            // Backing in, Pleos shows its own rear/side cameras (EVS) over our app. Once the car is stopped
            // (gear P), come back to the front with the 3D position.
            returnFromCamera()
            ParkTraceState.update {
                it.copy(
                    estimate = estimate, message = "정차 감지: 주변 차량과 구역 표지를 확인하는 중",
                    scene = liveScene(it, it.liveDecision?.spot, surround = true),
                )
            }
        }

        override fun onRestarted() {
            ParkTraceState.update { it.copy(message = null) }
        }

        override fun onLeftGarage() {
            stopCamera()
            voter.clear()
            enteredAt = null
            ParkTraceState.update {
                it.copy(
                    entryCue = null, path = emptyList(), liveDecision = null, message = "주차장을 벗어남",
                    enteredAt = null, feePolicy = null, scene = it.latest?.let { r -> sceneFor(r) },
                )
            }
        }

        override fun onParked(session: ParkingDetector.Session) {
            ParkTraceState.update { it.copy(parkingMode = false) }
            returnFromCamera()
            driverAway = false
            stopCamera()
            val record = buildRecord(session)
            repo.save(record)
            if (!demoMode && session.inGarage && session.entryCue != ParkingDetector.EntryCue.GEOFENCE && session.entrance != null) {
                val name = record.placeName ?: "주차장 ${garages.all().size + 1}"
                if (garages.learn(name, session.entrance.lat, session.entrance.lon)) sdk.post { location.registerGarageAlerts() }
            }
            val spoken = record.spoken()
            // Gear P while the car's camera view is still up: the guidance waits until that view has closed
            // (see toFront). The on-screen line waits by itself, nothing is drawn behind the camera view.
            if (ParkTraceState.ui.cameraUp) heldGuidance = spoken else sdk.post { announcer.speak(spoken) }
            enteredAt = null
            ParkTraceState.update {
                it.copy(latest = record, message = "저장 완료: ${record.headline()}", enteredAt = null, feePolicy = null, scene = sceneFor(record))
            }
        }
    }

    private fun buildRecord(session: ParkingDetector.Session): ParkingRecord {
        val decision = if (session.inGarage) voter.decide(lastTripMeters) else null
        val est = session.estimate
        val ocrFloor = decision?.floorLabel
        val floor = ocrFloor ?: est?.label
        val crossOk = if (ocrFloor != null && est != null) ocrFloor == est.label else null
        return ParkingRecord(
            id = session.parkedAt,
            savedAt = session.parkedAt,
            inGarage = session.inGarage,
            placeName = if (demoMode) DEMO_PLACE else session.garageName ?: navi.placeName(),
            entranceLat = session.entrance?.lat,
            entranceLon = session.entrance?.lon,
            floorLabel = if (session.inGarage) floor else null,
            floorSource = when {
                !session.inGarage -> null
                ocrFloor != null -> "표지판 인식"
                else -> "고도·회전 추정"
            },
            estimatedFloor = est?.label,
            estimateConfidence = est?.confidence?.name,
            floorCrossCheckOk = crossOk,
            spot = decision?.spot,
            spotVotes = decision?.spotVotes ?: 0,
            entryCue = session.entryCue?.korean,
            path = session.path.map { it.x to it.y },
            travelledMeters = session.travelledInGarage,
            enteredAt = if (session.inGarage) enteredAt ?: session.parkedAt else null,
            reversedIn = session.reversedIn,
            fee = if (session.inGarage) fees.policyFor(if (demoMode) DEMO_PLACE else session.garageName ?: navi.placeName()) else null,
        )
    }

    fun clearHistory() {
        repo.clear()
        ParkTraceState.update { it.copy(latest = null, scene = if (it.enteredAt != null) it.scene else null) }
    }

    // ------------------------------------------------------------------ 3D scene

    private fun liveScene(ui: ParkTraceState.Ui, spot: String?, surround: Boolean): ParkingScene? =
        vision.scan(ui.path.map { it.x to it.y }, spot, enteredAt ?: 0L, surround, reversed = detector.lastMoveReversed,
            gateAt = ui.gateMeters)

    private fun sceneFor(r: ParkingRecord): ParkingScene? =
        if (!r.inGarage) null else vision.scan(r.path, r.spot, r.enteredAt ?: r.id, surround = true, reversed = r.reversedIn,
            gateAt = if (r.entryCue == ParkingDetector.EntryCue.GEOFENCE.korean) GEOFENCE_GATE_METERS else null)

    // ------------------------------------------------------------------ fees

    /** Garage the fee table applies to: the one we are in, else the last parking. */
    fun currentPlace(): String? = ParkTraceState.ui.let { if (it.enteredAt != null) it.placeName else it.latest?.placeName ?: it.placeName }

    fun feePolicyFor(place: String?): FeePolicy = fees.policyFor(place)

    fun setFeePolicy(place: String?, policy: FeePolicy) {
        fees.save(place, policy)
        val latest = repo.latest()?.takeIf { it.inGarage && it.placeName == place }?.copy(fee = policy)
        latest?.let { repo.save(it) }
        ParkTraceState.update {
            it.copy(
                latest = latest ?: it.latest,
                feePolicy = if (it.enteredAt != null && it.placeName == place) policy else it.feePolicy,
                message = "요금표 저장: ${policy.describe()}",
            )
        }
    }

    // ------------------------------------------------------------------ auto launch on garage entry

    /**
     * Brings the app to the front when the car enters a garage.
     * A background start is allowed when the app may draw over other apps (SYSTEM_ALERT_WINDOW);
     * otherwise Android blocks it silently, so a full-screen-intent notification is posted as well.
     */
    private fun bringAppToFront() {
        // never over the car's surround-camera screen while backing in
        if (ParkTraceState.ui.parkingMode) return
        // Pleos hosts apps in task panels: a covered app still counts as "started", so the check is
        // "top resumed" (focused), not onStart/onStop. Starting an already-front singleTask activity is a no-op.
        runCatching { startActivity(mainIntent()) }.onFailure { Log.w(TAG, "background activity start refused", it) }
        // Pleos panels: an activity that is already "top of its task" is not always switched to by a start alone
        runCatching { getSystemService(android.app.ActivityManager::class.java).appTasks.firstOrNull()?.moveToFront() }
        Log.i(TAG, "garage entry: bring app to front (focused=${ParkTraceState.uiVisible})")
    }

    /**
     * In-app demo only: a simulated rear-camera picture of a car park, drawn by this app over its own screen
     * (RearCameraView). It stands in for the car's camera view, which this app can neither feed nor change.
     */
    private fun showSimulatedCamera() {
        ParkTraceState.update { it.copy(cameraUp = true, simulatedCamera = true) }
        Log.i(TAG, "simulated rear camera view shown")
        watchCamera()
    }

    private var cameraOpenedAt = 0L
    /** Spoken parking guidance that came up during the camera view; said once that view has closed. */
    private var heldGuidance: String? = null
    private val toFront = Runnable {
        ParkTraceState.update { it.copy(cameraUp = false, simulatedCamera = false) }
        bringAppToFront()
        heldGuidance?.let { line -> heldGuidance = null; Log.i(TAG, "camera view closed: guidance \"$line\""); sdk.post { announcer.speak(line) } }
    }

    /** The camera view is being called up now: the time it needs to come up fully counts from here. */
    private fun watchCamera() {
        cameraOpenedAt = android.os.SystemClock.elapsedRealtime()
        main.removeCallbacks(toFront)
    }

    /**
     * Parked (or stopped in P): back to this app, but only after the camera view has had time to come up fully
     * and has then stayed on screen for about 3 seconds.
     */
    private fun returnFromCamera() {
        // the simulated picture is on screen at once; only the car's own camera view needs time to come up
        val total = (if (ParkTraceState.ui.simulatedCamera) 0L else CAMERA_APPEAR_MS) + CAMERA_SHOWN_MS
        val wait = if (cameraOpenedAt == 0L) 0L else (cameraOpenedAt + total - android.os.SystemClock.elapsedRealtime()).coerceIn(0L, total)
        main.removeCallbacks(toFront)
        main.postDelayed(toFront, wait)
    }
    private fun mainIntent() = Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

    /**
     * "주차장에 진입했어요": voice line always; the screen side is the app itself (entry time + free-until line).
     * Pleos pops every notification up over the screen (whatever its importance), so a notification is only
     * posted when the app cannot come to the front by itself (no overlay permission): then its full-screen
     * intent is what opens the app, and MainActivity clears it as soon as it is in front.
     */
    private fun notifyEntry(place: String?, policy: FeePolicy, now: Long) {
        val line = "주차장에 진입했어요." + if (policy.freeMinutes > 0) " ${FeePolicy.minutesText(policy.freeMinutes)} 동안 무료예요." else ""
        sdk.post { announcer.speak(line) }

        val appShowsItself = ParkTraceState.uiVisible || android.provider.Settings.canDrawOverlays(this)
        if (appShowsItself) return
        val hm = SimpleDateFormat("HH:mm", Locale.KOREA)
        val free = if (policy.freeMinutes > 0) "무료 ${hm.format(policy.freeUntil(now))}까지 · " else ""
        val pi = PendingIntent.getActivity(this, 1, mainIntent(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, ParkTraceApp.ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_parktrace)
            .setContentTitle("주차장에 진입했어요" + (place?.let { " · $it" } ?: ""))
            .setContentText("${hm.format(now)} 진입 · $free${policy.describe()}")
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000L)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(ENTRY_NOTI_ID, n) }
    }

    /** Back in the car: what the parking is expected to cost if the driver leaves now (pop-up + voice). */
    private fun notifyReturn(now: Long) {
        val rec = ParkTraceState.ui.latest ?: return
        val entered = rec.enteredAt ?: return
        val policy = rec.fee ?: return
        val fee = policy.feeAt(entered, now)
        val parked = FeePolicy.minutesText(maxOf(1, ((now - entered) / 60_000L).toInt()))
        val freeLeft = ((policy.freeUntil(entered) - now + 59_999L) / 60_000L).toInt()
        val title = if (fee > 0) "예상 주차 요금 ${FeePolicy.won(fee)}" else "주차 요금 없음"
        val detail = if (fee > 0) "$parked 주차" else "$parked 주차 · 무료 ${FeePolicy.minutesText(maxOf(1, freeLeft))} 남음"
        sdk.post { announcer.speak(if (fee > 0) "예상 주차 요금은 ${FeePolicy.won(fee)}이에요." else "아직 무료 시간이에요.") }
        val pi = PendingIntent.getActivity(this, 2, mainIntent(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, ParkTraceApp.ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_parktrace)
            .setContentTitle(title)
            .setContentText(detail)
            // Pleos only pops up navigation-category notifications from apps
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000L)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).apply { cancel(RETURN_NOTI_ID); notify(RETURN_NOTI_ID, n) } }
            .onFailure { Log.w(TAG, "return notification failed", it) }
        Log.i(TAG, "driver back: $title · $detail")
    }

    companion object {
        private const val TAG = "ParkTrace/Service"
        const val RETURN_NOTI_ID = 44
        /** How long the camera view stays on screen, once it is fully up, before this app shows the result. */
        const val CAMERA_SHOWN_MS = 3_000L
        /**
         * Time allowed for the camera view to come up fully (its picture included) after it is called up.
         * Measured on the Pleos Connect emulator: still this app at 1.6 s, the camera picture by 2.3 to 3.2 s.
         * An app cannot observe this: losing "top resumed" comes within milliseconds of the call.
         */
        private const val CAMERA_APPEAR_MS = 3_500L
        /** The door opening again only counts as "back in the car" after this long away (not while still getting out). */
        const val MIN_AWAY_MS = 30_000L
        private const val NOTI_ID = 42
        /** Registered garages switch on at the geofence edge (80 m); the gate is about this far along the path. */
        const val GEOFENCE_GATE_METERS = 94.0
        /** From the entrance roof edge (where GPS is lost, the registered point) to the barrier. */
        const val GATE_AFTER_ENTRANCE = 11.5
        /** A car waiting at the barrier stops about this far from it. */
        const val GATE_STOP_GAP = 3.5
        const val ENTRY_NOTI_ID = 43
        private const val DEMO_PLACE = "시연 주차장"
        /** Test hook for adb (see DebugEntryReceiver): simulates a garage entry while the app is in the background. */
        const val ACTION_SIM_ENTRY = "com.parktrace.action.SIM_GARAGE_ENTRY"
        const val ACTION_SIM_SIGN = "com.parktrace.action.SIM_SIGN"
    }
}
