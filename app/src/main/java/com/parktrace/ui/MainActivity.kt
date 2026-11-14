package com.parktrace.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.format.DateUtils
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.parktrace.BuildConfig
import com.parktrace.R
import com.parktrace.core.Directions
import com.parktrace.core.FeePolicy
import com.parktrace.core.ParkingDetector
import com.parktrace.core.ParkingDetector.Phase
import com.parktrace.data.ParkingRecord
import com.parktrace.data.ShareLink
import com.parktrace.demo.DemoScenario
import com.parktrace.service.ParkTraceState
import com.parktrace.service.ParkingMonitorService
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var phase: TextView
    private lateinit var floorValue: TextView
    private lateinit var floorCaption: TextView
    private lateinit var spotValue: TextView
    private lateinit var subline: TextView
    private lateinit var place: TextView
    private lateinit var fee: TextView
    private lateinit var scene: SceneView
    private lateinit var feeCard: LinearLayout
    private lateinit var feeTitle: TextView
    private lateinit var feeBar: android.widget.ProgressBar
    private lateinit var guide: TextView
    private var demo: DemoScenario? = null

    private val observer: (ParkTraceState.Ui) -> Unit = { render(it) }
    private val main = Handler(Looper.getMainLooper())
    /** Re-renders the fee line while the screen is visible (the fee changes with time, not with signals). */
    private val feeTicker = object : Runnable {
        override fun run() { render(ParkTraceState.ui); main.postDelayed(this, 15_000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        phase = findViewById(R.id.phase)
        floorValue = findViewById(R.id.floorValue)
        floorCaption = findViewById(R.id.floorCaption)
        spotValue = findViewById(R.id.spotValue)
        subline = findViewById(R.id.subline)
        place = findViewById(R.id.place)
        fee = findViewById(R.id.fee)
        scene = findViewById(R.id.scene)
        feeCard = findViewById(R.id.feeCard)
        feeTitle = findViewById(R.id.feeTitle)
        feeBar = findViewById(R.id.feeBar)
        guide = findViewById(R.id.guide)

        findViewById<Button>(R.id.btnQr).setOnClickListener { showQr() }
        findViewById<Button>(R.id.btnManual).setOnClickListener { ParkTraceState.service?.manualGarageEntry() }
        findViewById<Button>(R.id.btnDemo).setOnClickListener { toggleDemo() }
        findViewById<Button>(R.id.btnFee).setOnClickListener { showFeeDialog() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { ParkTraceState.service?.clearHistory() }

        // "모의 표지판": stands in for the camera until camera access is available (향후 계획).
        mapOf(
            R.id.btnSignB1 to "B1",
            R.id.btnSignB2 to "B2",
            R.id.btnSignC12 to "C-12",
            R.id.btnSignC14 to "C-14",
            R.id.btnSignC15 to "C-15",
        ).forEach { (id, text) ->
            findViewById<Button>(id).setOnClickListener { ParkTraceState.service?.simulateSign(text) }
        }

        requestNeededPermissions()
    }

    override fun onStart() {
        super.onStart()
        ParkTraceState.observe(observer)
        main.postDelayed(feeTicker, 15_000)
    }

    /** "Focused" = what the driver is looking at. Pleos keeps covered panel apps started, so onStart/onStop is not enough. */
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        ParkTraceState.uiVisible = isTopResumedActivity
        // In front now: the fallback "주차장에 진입했어요" notification (if any) would only cover the screen.
        if (isTopResumedActivity) getSystemService(android.app.NotificationManager::class.java)?.cancel(ParkingMonitorService.ENTRY_NOTI_ID)
    }

    override fun onStop() {
        ParkTraceState.uiVisible = false
        main.removeCallbacks(feeTicker)
        main.removeCallbacks(hideGuide); guide.visibility = View.GONE
        main.removeCallbacks(dotTicker); dotsRunning = false
        ParkTraceState.remove(observer)
        super.onStop()
    }

    // ------------------------------------------------------------------ permissions & service

    private val permissions: Array<String> get() = buildList {
        add("pleos.car.permission.FUSED_LOCATION")
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private fun requestNeededPermissions() {
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startMonitor() else requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) startMonitor() // start anyway; missing pieces degrade gracefully
    }

    private fun startMonitor() {
        val i = Intent(this, ParkingMonitorService::class.java)
        runCatching { startForegroundService(i) }.onFailure { startService(i) }
    }

    // ------------------------------------------------------------------ actions

    private fun toggleDemo() {
        val svc = ParkTraceState.service ?: return
        demo?.let { it.cancel(); demo = null; return }
        demo = DemoScenario(svc) { demo = null }.also { it.start() }
    }

    private fun showQr() {
        val rec = ParkTraceState.ui.latest ?: run { toast("저장된 주차 기록이 없어요"); return }
        val url = ShareLink.build(BuildConfig.FIND_PAGE_URL, rec)
        val img = ImageView(this).apply { setImageBitmap(QrCode.render(url)); adjustViewBounds = true }
        val label = TextView(this).apply {
            text = "폰 카메라로 찍으면 ${rec.headline()} 위치와 요금 알림이 열려요"
            textSize = 22f; setPadding(24, 24, 24, 0)
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32); addView(img); addView(label) }
        AlertDialog.Builder(this).setView(box).setPositiveButton("닫기", null).show()
    }

    /** Fee table per garage, entered by hand (no fee API). Only meant to be used while parked. */
    private fun showFeeDialog() {
        val svc = ParkTraceState.service ?: return
        val place = svc.currentPlace()
        val cur = svc.feePolicyFor(place)
        val labels = listOf("무료 시간 (분)", "과금 단위 (분)", "단위 요금 (원)", "1일 최대 (원, 0 = 없음)")
        val fields = listOf(cur.freeMinutes, cur.unitMinutes, cur.unitPrice, cur.dailyMax).map { v ->
            EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(v.toString()); textSize = 24f }
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 24, 40, 0) }
        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "30분 무료 · 10분 1,000원" to FeePolicy(30, 10, 1000, 30000),
            "1시간 무료 · 10분 500원" to FeePolicy(60, 10, 500, 20000),
            "2시간 무료 · 30분 1,500원" to FeePolicy(120, 30, 1500, 0),
        ).forEach { (title, p) ->
            presets.addView(Button(this).apply {
                text = title; textSize = 18f; isAllCaps = false
                setOnClickListener { listOf(p.freeMinutes, p.unitMinutes, p.unitPrice, p.dailyMax).forEachIndexed { i, v -> fields[i].setText(v.toString()) } }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        box.addView(presets)
        labels.forEachIndexed { i, l ->
            box.addView(TextView(this).apply { text = l; textSize = 20f; setPadding(0, 20, 0, 0) })
            box.addView(fields[i])
        }
        AlertDialog.Builder(this)
            .setTitle("요금표 · ${place ?: "기본"}")
            .setView(box)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장") { _, _ ->
                val v = fields.map { it.text.toString().trim().toIntOrNull() ?: 0 }
                runCatching { FeePolicy(v[0], v[1], v[2], v[3]) }
                    .onSuccess { svc.setFeePolicy(place, it) }
                    .onFailure { toast("요금표 값을 확인해 주세요 (과금 단위는 1분 이상)") }
            }
            .show()
    }

    // ------------------------------------------------------------------ rendering

    private fun render(s: ParkTraceState.Ui) {
        // behind the car's camera view: draw nothing (see ParkingMonitorService.openCarCamera)
        scene.visibility = if (s.cameraUp) View.INVISIBLE else View.VISIBLE
        if (s.cameraUp) return
        val t0 = System.nanoTime()
        renderInner(s)
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (ms > 30) android.util.Log.w("ParkTrace/UI", "slow render: $ms ms")
    }

    // ---- guidance line: appears when the message changes, goes away by itself
    private var shownMessage: String? = null
    private val hideGuide = Runnable { guide.visibility = View.GONE }

    private fun renderGuide(message: String?) {
        if (message == shownMessage) return
        shownMessage = message
        main.removeCallbacks(hideGuide)
        if (message == null) { guide.visibility = View.GONE; return }
        guide.text = message
        guide.visibility = View.VISIBLE
        main.postDelayed(hideGuide, GUIDE_MS)
    }

    private fun renderInner(s: ParkTraceState.Ui) {
        renderGuide(s.message)
        phase.text = when (s.phase) {
            Phase.DRIVING -> if (s.latest != null) "저장된 위치" else "주행 중"
            Phase.IN_GARAGE -> if (s.parkingMode) "주차 중" else if (s.gateMeters != null && (s.path.lastOrNull()?.distance ?: 0.0) < s.gateMeters) "주차장 입구 접근 중" else "주차장 안"
            Phase.STOPPED -> "주차 확인 중"
            Phase.PARKED -> "주차 완료"
        }
        phase.setTextColor(getColor(when (s.phase) {
            Phase.DRIVING -> R.color.muted
            Phase.IN_GARAGE, Phase.STOPPED -> R.color.accent
            Phase.PARKED -> R.color.ok
        }))

        val live = s.phase == Phase.IN_GARAGE || s.phase == Phase.STOPPED
        val rec = s.latest
        if (live) {
            val d = s.liveDecision
            val guess = s.estimate?.takeIf { !it.method.startsWith("단서 부족") }?.label
            val floorText = d?.floorLabel?.let { ParkingRecord.spokenFloor(it) } ?: guess?.let { ParkingRecord.spokenFloor(it) }
            loadingFloor = floorText == null
            loadingSpot = d?.spot == null
            if (floorText != null) floorValue.text = floorText
            // A floor from altitude/turns only is marked in the caption, not with a "?" on the value.
            floorCaption.text = if (d?.floorLabel == null && guess != null) "층 · 추정" else "층"
            d?.spot?.let { spotValue.text = it }
            showDots()
            place.text = parkName(s.placeName)
            subline.text = ""
            renderFee(s.enteredAt, s.feePolicy)
            scene.setScene(s.scene, "내 차")
        } else if (rec != null) {
            loadingFloor = false; loadingSpot = false
            floorCaption.text = "층"
            floorValue.text = if (!rec.inGarage) "지상" else rec.floorLabel?.let { ParkingRecord.spokenFloor(it) } ?: "모름"
            spotValue.text = rec.spot ?: if (rec.inGarage) "못 읽음" else "—"
            place.text = if (rec.inGarage) parkName(rec.placeName) else rec.placeName ?: "지상 주차"
            subline.text = if (rec.savedAt > System.currentTimeMillis() - 60_000L) "방금 전" else DateUtils.getRelativeTimeSpanString(rec.savedAt).toString()
            renderFee(rec.enteredAt, rec.fee)
            // the saved position is shown from the front of the car
            scene.setScene(s.scene, "내 차", front = true)
        } else {
            loadingFloor = false; loadingSpot = false
            floorCaption.text = "층"
            floorValue.text = "—"
            spotValue.text = "—"
            place.text = ""
            subline.text = ""
            renderFee(null, null)
            scene.setScene(null, "")
        }
    }

    // ---- "still working it out": three dots, one lit at a time
    private var loadingFloor = false
    private var loadingSpot = false
    private var dotStep = 0
    private var dotsRunning = false
    private val dotTicker = object : Runnable {
        override fun run() {
            dotsRunning = false
            dotStep = (dotStep + 1) % 3
            showDots()
        }
    }

    private fun showDots() {
        if (loadingFloor) floorValue.text = dots(getColor(R.color.fg))
        if (loadingSpot) spotValue.text = dots(getColor(R.color.brand))
        if ((loadingFloor || loadingSpot) && !dotsRunning) { dotsRunning = true; main.postDelayed(dotTicker, 320) }
    }

    private fun dots(color: Int): CharSequence = android.text.SpannableString("•••").apply {
        val dim = (color and 0x00FFFFFF) or 0x33000000
        for (i in 0 until 3) setSpan(android.text.style.ForegroundColorSpan(if (i == dotStep) color else dim), i, i + 1, 0)
    }

    /** "수정구 시흥동" -> "수정구 시흥동 주차장": the line under the location says which car park this is. */
    private fun parkName(name: String?): String = when {
        name.isNullOrBlank() -> "주차장"
        name.contains("주차장") -> name
        else -> "$name 주차장"
    }

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    /** Fee card: big status ("무료 23분 남음" / "현재 3,000원"), a bar of the free time / current unit, detail line. */
    private fun renderFee(enteredAt: Long?, p: FeePolicy?, now: Long = System.currentTimeMillis()) {
        if (enteredAt == null || p == null) { feeCard.visibility = android.view.View.GONE; return }
        feeCard.visibility = android.view.View.VISIBLE
        val hm = SimpleDateFormat("HH:mm", Locale.KOREA)
        val freeEnd = p.freeUntil(enteredAt)
        if (now < freeEnd) {
            val left = (freeEnd - now + 59_999) / 60_000
            feeTitle.text = "무료 ${left}분 남음"
            feeTitle.setTextColor(getColor(if (left <= 10) R.color.warn else R.color.ok))
            val total = (freeEnd - enteredAt).coerceAtLeast(1)
            feeBar.progress = (1000 * (freeEnd - now) / total).toInt()
            fee.text = "${hm.format(freeEnd)}까지 무료 · 이후 ${p.unitMinutes}분당 ${FeePolicy.won(p.unitPrice)}"
        } else {
            feeTitle.text = "현재 ${FeePolicy.won(p.feeAt(enteredAt, now))}"
            feeTitle.setTextColor(getColor(R.color.warn))
            val next = p.nextIncreaseAt(enteredAt, now)
            val unit = p.unitMinutes * 60_000L
            feeBar.progress = if (next == null) 1000 else (1000 * (unit - (next - now)).coerceIn(0, unit) / unit).toInt()
            fee.text = next?.let { "${hm.format(it)}부터 ${FeePolicy.won(p.feeAt(enteredAt, it + 1))}" } ?: "1일 최대 요금"
        }
    }

    companion object {
        private const val REQ_PERMS = 7
        /** How long the guidance line stays on screen. */
        private const val GUIDE_MS = 8_000L
    }
}
