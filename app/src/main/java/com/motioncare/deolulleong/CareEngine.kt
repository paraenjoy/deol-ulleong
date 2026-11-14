package com.motioncare.deolulleong

import ai.pleos.playground.vehicle.Vehicle
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

const val TAG = "DEOL"

/** 화면이 그리는 상태(틱마다 새로 만든다). */
data class UiState(
    val consent: Boolean = false,
    val speedPermission: Boolean = true,
    val signalOk: Boolean = false,
    val driving: Boolean = false,
    val careOn: Boolean = false,
    val controlling: Boolean = false,
    val rearOccupied: Boolean? = null,
    val level: Level = Level.CALM,
    val speedKph: Double = 0.0,
    val wheelDeg: Double = 0.0,
    val lon: Double = 0.0,
    val lat: Double = 0.0,
    val shake: Double = 0.0,
    val loadSec: Double = 0.0,
    val tripMoved: Boolean = false,
    val tripElapsedSec: Long = 0,
    val report: TripReport? = null,
    val screenOnly: Boolean = false,
    val demoInfo: Boolean = false,
    val ambientStatus: String = "",
    val hvacStatus: String = "",
    val hvacNote: String? = null,
)

/** 단계가 오를 때 짧은 알림음(TTS 아님). 주의는 한 번, 거침은 두 번 울린다. */
private class Tone {
    private val tg = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull()
    fun play(level: Level) {
        when (level) {
            Level.CAUTION -> tg?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            Level.ROUGH -> tg?.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
            Level.CALM -> Unit
        }
    }
    fun release() = tg?.release()
}

/**
 * 케어 흐름
 *  - 동의 + 2열 탑승(또는 '지금 케어 시작') -> 케어 켜짐. 무드등 원래 값을 저장하고 청록으로 바꾼다.
 *  - 주행(requiresDO=true) 동안 0.1초마다 흔들림을 계산해 무드등 색, 알림음, 기록, 공조 케어를 한다.
 *  - 정차(기어 P, requiresDO=false) -> 주행 종료: 무드등, 공조를 되돌리고 리포트를 보여 준다.
 *  - 다시 출발하면 새 주행을 시작한다.
 *  - 복원 시점: 주행 종료, '케어 종료' 버튼, 앱 종료(onDestroy), 차량 신호 끊김(2초).
 */
class CareEngine(context: Context, private val prefs: SharedPreferences) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val vehicle = Vehicle(app)
    private val estimator = ShakeEstimator()
    private val load = MotionLoad()
    private val tone = Tone()
    private val ambient = AmbientCare(vehicle, main, ::log)
    private val hvac = HvacCare(vehicle, main, ::log)
    private val rear = RearSeatWatcher(vehicle, main, ::log) { occ -> onRearChanged(occ) }

    private var car: Car? = null
    private var cpm: CarPropertyManager? = null
    private var uxm: CarUxRestrictionsManager? = null

    @Volatile private var speedMps = 0.0
    @Volatile private var speedAtMs = -1L
    @Volatile private var wheelDeg = 0.0
    @Volatile private var wheelAtMs = -1L

    private var driving = false
    private var consent = prefs.getBoolean(PREF_CONSENT, false)
    private var screenOnly = prefs.getBoolean(PREF_SCREEN_ONLY, false)
    private var demoInfo = prefs.getBoolean(PREF_DEMO_INFO, false)
    private var speedPermission = false
    private var speedRegistered = false

    private var careOn = false
    private var manualCare = false   // '지금 케어 시작'
    private var manualOff = false    // '케어 종료' 뒤에는 2열 탑승이 새로 감지될 때까지 자동 시작하지 않는다
    private var controlling = false
    private var signalOk = false
    private var prevLevel = Level.CALM

    private var trip: Trip? = null
    private var tripSeq = 0
    private val reports = mutableListOf<TripReport>() // 메모리에만 둔다

    var ui by mutableStateOf(UiState(consent = consent, screenOnly = screenOnly, demoInfo = demoInfo))
        private set

    // ------------------------------------------------------------------ 수명

    fun start() {
        vehicle.initialize()
        log("vehicle.initialize()")
        // initialize()에 완료 콜백이 없어 잠시 뒤 capability를 확인한다(시험 앱과 같은 방식).
        main.postDelayed({
            ambient.probe(); hvac.probe(); rear.probe()
        }, 1500)
        car = Car.createCar(app, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
            log("Car ready=$ready")
            if (ready) onCarReady(c)
        }
        main.post(ticker)
    }

    /** 앱 종료: 제어를 멈추고 원래 값으로 되돌린 뒤, 쓰기가 끝날 시간을 두고 해제한다. */
    fun stop() {
        main.removeCallbacks(ticker)
        if (controlling) stopControl("앱 종료")
        runCatching { cpm?.unregisterCallback(speedCb) }
        runCatching { uxm?.unregisterListener() }
        tone.release()
        main.postDelayed({
            ambient.release(); hvac.release(); rear.release()
            runCatching { vehicle.release() }
            runCatching { car?.disconnect() }
            log("released")
        }, 800)
    }

    // ------------------------------------------------------------------ 차량 신호

    /** 시험용(debug 빌드에서만 부름): 이 시각까지 들어오는 차량 신호를 무시해 '신호 끊김'을 흉내 낸다. */
    @Volatile private var ignoreSignalsUntilMs = 0L

    fun simulateSignalLoss(ms: Long) {
        ignoreSignalsUntilMs = SystemClock.elapsedRealtime() + ms
        log("시험: ${ms}ms 동안 차량 신호를 무시")
    }

    private fun signalsIgnored() = SystemClock.elapsedRealtime() < ignoreSignalsUntilMs

    private val speedCb = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (signalsIgnored()) return
            (value.value as? Float)?.let {
                speedMps = it.toDouble()
                speedAtMs = SystemClock.elapsedRealtime()
            }
        }
        override fun onErrorEvent(propId: Int, zone: Int) { log("speed error prop=$propId zone=$zone") }
    }

    private fun onCarReady(c: Car) {
        cpm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        registerSpeed()
        uxm = (c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager).also { m ->
            m.registerListener { r -> main.post { onUx(r) } }
            m.currentCarUxRestrictions?.let { r -> main.post { onUx(r) } }
        }
    }

    fun onSpeedPermission(granted: Boolean) {
        speedPermission = granted
        log("CAR_SPEED granted=$granted")
        registerSpeed()
    }

    private fun registerSpeed() {
        val m = cpm ?: return
        if (!speedPermission || speedRegistered) return
        speedRegistered = runCatching {
            m.registerCallback(speedCb, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_FASTEST)
        }.onFailure { log("speed register FAIL $it") }.getOrDefault(false)
        log("speed registered=$speedRegistered")
    }

    private fun onUx(r: CarUxRestrictions) {
        val d = r.isRequiresDistractionOptimization
        if (d != driving) log("주행 상태 requiresDO=$d active=0x${Integer.toHexString(r.activeRestrictions)}")
        driving = d
    }

    private fun pollSteering() {
        vehicle.getSteeringWheel().getCurrentSteeringWheelAngle(
            { a: Float? -> if (a != null && !signalsIgnored()) { wheelDeg = a.toDouble(); wheelAtMs = SystemClock.elapsedRealtime() } },
            { _: Exception -> })
    }

    private var rearChangedAtMs = 0L

    private fun onRearChanged(occupied: Boolean) {
        rearChangedAtMs = SystemClock.elapsedRealtime()
        if (occupied) manualOff = false // 새로 탔으면 다시 자동 시작
    }

    // ------------------------------------------------------------------ 틱

    private val ticker = object : Runnable {
        override fun run() {
            runCatching { tick() }.onFailure { Log.e(TAG, "tick", it) }
            main.postDelayed(this, Spec.TICK_MS)
        }
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        runCatching { pollSteering() }

        // 차량 신호 확인: 차속, 핸들각이 2초 넘게 안 오면 모든 제어를 멈춘다.
        val fresh = speedAtMs > 0 && wheelAtMs > 0 &&
            now - speedAtMs <= Spec.SIGNAL_TIMEOUT_MS && now - wheelAtMs <= Spec.SIGNAL_TIMEOUT_MS
        if (fresh != signalOk) {
            signalOk = fresh
            if (!fresh) {
                log("신호 확인 중: 차량 신호가 ${Spec.SIGNAL_TIMEOUT_MS}ms 넘게 없음, 제어 멈춤")
                if (controlling) stopControl("신호 끊김")
            } else {
                log("차량 신호 정상")
                estimator.reset()
            }
        }
        val sample = if (signalOk) estimator.update(now, speedMps, wheelDeg) else null

        // 케어 켜고 끄기. 좌석 값은 1초 이상 그대로일 때만 믿는다(값이 잠깐 튀는 것 방지).
        val rearSettled = now - rearChangedAtMs >= SEAT_SETTLE_MS
        val want = consent && (manualCare || (rear.occupied == true && rearSettled && !manualOff))
        if (want && !careOn) startCare(now)
        if (careOn && !manualCare && !driving && rear.occupied != true && rearSettled) endCare(now, "2열 비움")

        if (careOn) careTick(now, sample)
        publish(now)
    }

    private fun careTick(now: Long, s: ShakeSample?) {
        var t = trip
        if (t == null) {
            if (!driving) return // 지난 주행 리포트를 보여 주는 중
            t = beginTrip(now)
        }
        if (driving && !t.moved) t.markMoved(now)
        if (!driving && t.moved) {
            endTrip(now, "정차")
            return
        }
        if (signalOk && !controlling) startControl()
        if (!controlling || s == null) return

        if (!screenOnly) ambient.apply(s.level)
        if (s.level > prevLevel) tone.play(s.level)
        prevLevel = s.level
        if (!t.moved) return
        t.onSample(now, s)
        load.update(now, s.instantLevel)
        if (load.exceeded) {
            hvac.tryCare(now)?.let { note ->
                t.hvacNote = "${mmss(t.elapsedSec(now))} $note"
                log("멀미 부하 ${load.loadMs}ms >= ${Spec.LOAD_TRIGGER_MS}ms: 공조 케어 ($note)")
            }
        }
    }

    // ------------------------------------------------------------------ 상태 전환

    private fun startCare(now: Long) {
        careOn = true
        log("케어 시작 (2열 탑승=${rear.occupied}, 수동=$manualCare)")
        beginTrip(now)
    }

    private fun beginTrip(now: Long): Trip {
        val t = Trip(++tripSeq, now)
        trip = t
        estimator.reset(); load.reset()
        hvac.startTrip()
        log("주행 #${t.id} 준비")
        if (signalOk) startControl()
        return t
    }

    private fun startControl() {
        controlling = true
        prevLevel = Level.CALM
        if (!screenOnly) ambient.begin()
        log("제어 시작 (무드등 ${if (screenOnly) "끔: 화면만" else "켬"})")
    }

    private fun stopControl(reason: String) {
        controlling = false
        ambient.end(reason)
        hvac.restore(reason)
        log("제어 멈춤: $reason")
    }

    private fun endTrip(now: Long, reason: String) {
        val t = trip ?: return
        t.close()
        t.endMs = now
        if (controlling) stopControl(reason)
        trip = null
        load.reset()
        if (t.moved) {
            val r = t.report(now)
            reports += r
            log("주행 #${r.id} 끝($reason): ${r.durationSec}초, 점수 ${r.score}, 거침 ${r.roughCount}, 주의 ${r.cautionCount}, " +
                r.events.joinToString { "${it.kind.label}@${mmss(it.atSec)}(${it.peakLevel.word} %.1f)".format(it.peakShake) } +
                ", 공조 ${r.hvacNote ?: "없음"}")
        }
    }

    private fun endCare(now: Long, reason: String) {
        endTrip(now, reason)
        careOn = false
        log("케어 끝: $reason")
    }

    // ------------------------------------------------------------------ 화면 동작

    fun grantConsent() {
        consent = true
        prefs.edit().putBoolean(PREF_CONSENT, true).apply()
        log("동의함")
    }

    fun startCareNow() {
        manualCare = true
        manualOff = false
        log("'지금 케어 시작'")
    }

    fun endCareByUser() {
        manualCare = false
        manualOff = true
        if (careOn) endCare(SystemClock.elapsedRealtime(), "케어 종료 버튼")
    }

    fun setScreenOnly(v: Boolean) {
        screenOnly = v
        prefs.edit().putBoolean(PREF_SCREEN_ONLY, v).apply()
        log("무드등 끄고 화면만=$v")
        if (controlling) {
            if (v) ambient.end("무드등 끄고 화면만") else ambient.begin()
        }
    }

    fun setDemoInfo(v: Boolean) {
        demoInfo = v
        prefs.edit().putBoolean(PREF_DEMO_INFO, v).apply()
    }

    /** 피드백은 기록만 한다. 오늘(1차)은 알림 기준값을 바꾸지 않는다. */
    fun submitFeedback(label: String) {
        val i = reports.lastIndex
        if (i < 0) return
        reports[i] = reports[i].copy(feedback = label)
        log("피드백 기록: 주행 #${reports[i].id} '$label' (기준값은 바꾸지 않음)")
    }

    private fun publish(now: Long) {
        val s = estimator.last
        val t = trip
        ui = UiState(
            consent = consent,
            speedPermission = speedPermission,
            signalOk = signalOk,
            driving = driving,
            careOn = careOn,
            controlling = controlling,
            rearOccupied = rear.occupied,
            level = s.level,
            speedKph = speedMps * 3.6,
            wheelDeg = wheelDeg,
            lon = s.lon,
            lat = s.latDisplay,
            shake = s.shake,
            loadSec = load.loadMs / 1000.0,
            tripMoved = t?.moved == true,
            tripElapsedSec = if (t?.moved == true) t.elapsedSec(now) else 0,
            report = if (t == null) reports.lastOrNull() else null,
            screenOnly = screenOnly,
            demoInfo = demoInfo,
            ambientStatus = ambient.status,
            hvacStatus = hvac.statusText,
            hvacNote = t?.hvacNote,
        )
    }

    private fun log(msg: String) { Log.i(TAG, msg) }

    companion object {
        private const val SEAT_SETTLE_MS = 1000L
        private const val PREF_CONSENT = "consent"
        private const val PREF_SCREEN_ONLY = "screen_only"
        private const val PREF_DEMO_INFO = "demo_info"
    }
}
