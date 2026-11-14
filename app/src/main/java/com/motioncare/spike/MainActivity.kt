package com.motioncare.spike

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.listener.AmbientLightBrightnessColorListener
import ai.pleos.playground.vehicle.listener.HvacFanSpeedListener
import ai.pleos.playground.vehicle.listener.HvacRecirculationStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import android.app.Activity
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/**
 * 기술 확인용 화면. distractionOptimized=true.
 * 로그 태그 MCSPIKE 로 모든 결과를 남긴다(adb logcat -s MCSPIKE).
 */
open class MainActivity : Activity() {

    companion object {
        const val TAG = "MCSPIKE"
        const val TICK_MS = 100L
        /** 조향각 범위가 ±90°를 넘으면 핸들각으로 보고 이 비율로 나눠 앞바퀴각을 만든다. */
        const val STEERING_RATIO = 15.0
        private const val REQ_SPEED = 1
        private const val PERM_SPEED = "android.car.permission.CAR_SPEED"
    }

    protected open val screenName = "MainActivity (DO=true)"

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var vehicle: Vehicle
    private var car: Car? = null
    private var uxm: CarUxRestrictionsManager? = null
    private var cpm: CarPropertyManager? = null

    private val est = ShakeEstimator()
    private var naviProbe: NaviProbe? = null

    // 최신 신호
    @Volatile private var speedMps = 0.0
    @Volatile private var speedEvents = 0
    @Volatile private var steerRaw = 0.0
    @Volatile private var steerPolls = 0
    @Volatile private var steerEvents = 0
    private var steerIsHandwheel = false

    // 엠비언트
    private var ambientAreas: List<SeatArea> = emptyList()
    private var pendingAmbientAreas: List<SeatArea> = emptyList()
    private var appliedLevel: Level? = null
    private var ambientReadback = "-"
    private var ambientLastResult = "-"
    private var ambientAuto = true

    private lateinit var tvMain: TextView
    private lateinit var tvAcc: TextView
    private lateinit var swatch: TextView
    private lateinit var tvAmbient: TextView
    private lateinit var tvUx: TextView
    private lateinit var tvLog: TextView
    private val logLines = ArrayDeque<String>()

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        log("onCreate $screenName")

        vehicle = Vehicle(applicationContext)
        vehicle.initialize()
        log("vehicle.initialize() called")
        // initialize()에 완료 콜백이 없어 잠시 뒤 조회한다.
        ui.postDelayed({ probeVehicle() }, 1500)
        naviProbe = NaviProbe(applicationContext) { log(it) }.also { it.start() }

        car = Car.createCar(this, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
            log("Car lifecycle ready=$ready")
            if (ready) onCarReady(c)
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        runCatching { vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steerListener) }
        runCatching { cpm?.unregisterCallback(speedCb) }
        runCatching { uxm?.unregisterListener() }
        runCatching { vehicle.release() }
        naviProbe?.stop()
        runCatching { car?.disconnect() }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 1. 차속 (AAOS)

    private val speedCb = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            speedMps = (value.value as Float).toDouble()
            if (speedEvents++ == 0) runOnUiThread { est.reset() }
        }
        override fun onErrorEvent(propId: Int, zone: Int) {
            log("speed onErrorEvent prop=$propId zone=$zone")
        }
    }

    private fun onCarReady(c: Car) {
        cpm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        startSpeedIfPermitted()

        // 5. 주행 규제
        uxm = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
        uxm?.registerListener { r -> runOnUiThread { showUx(r, "listener") } }
        uxm?.currentCarUxRestrictions?.let { runOnUiThread { showUx(it, "initial") } }
    }

    private fun startSpeedIfPermitted() {
        if (checkSelfPermission(PERM_SPEED) != PackageManager.PERMISSION_GRANTED) {
            log("CAR_SPEED not granted → requestPermissions")
            requestPermissions(arrayOf(PERM_SPEED), REQ_SPEED)
            return
        }
        val m = cpm ?: return
        try {
            val cfg = m.getCarPropertyConfig(VehiclePropertyIds.PERF_VEHICLE_SPEED)
            log("PERF_VEHICLE_SPEED config: rate ${cfg?.minSampleRate}~${cfg?.maxSampleRate}")
            val ok = m.registerCallback(speedCb, VehiclePropertyIds.PERF_VEHICLE_SPEED,
                CarPropertyManager.SENSOR_RATE_FASTEST)
            log("registerCallback(PERF_VEHICLE_SPEED)=$ok")
        } catch (e: Exception) {
            log("speed register FAIL $e")
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == REQ_SPEED) {
            log("CAR_SPEED result=${res.toList()}")
            if (res.firstOrNull() == PackageManager.PERMISSION_GRANTED) startSpeedIfPermitted()
        }
    }

    // ---------------------------------------------------------------- 1. 조향각 (Vehicle SDK)

    private val steerListener = object : SteeringWheelAngleListener {
        override fun onFailed(e: Exception) { log("listener onFailed $e") }
        override fun onSteeringWheelAngleUpdated(angle: Float?) {
            angle?.let { steerRaw = it.toDouble(); steerEvents++ }
        }
    }

    private fun probeVehicle() {
        val sw = vehicle.getSteeringWheel()
        sw.checkSteeringWheelAngleCapabilities(
            { cfg: CapabilityConfig, b: AreaBounds<Float> ->
                val maxAbs = maxOf(abs(b.minValue ?: 0f), abs(b.maxValue ?: 0f))
                steerIsHandwheel = maxAbs > 90f
                log("cap SteeringWheelAngle access=${cfg.accessType} bounds=${b.minValue}~${b.maxValue} → handwheel=$steerIsHandwheel")
            },
            { e: Exception -> log("cap SteeringWheelAngle FAIL $e") })
        runCatching { sw.registerSteeringWheelAngle(steerListener) }
            .onFailure { log("registerSteeringWheelAngle FAIL $it") }
            .onSuccess { log("registerSteeringWheelAngle OK") }

        probeAmbient()
        probeSeatOccupancy()
        probeHvacCaps()
    }

    private fun pollSteering() {
        vehicle.getSteeringWheel().getCurrentSteeringWheelAngle(
            { a: Float? -> if (a != null) { steerRaw = a.toDouble(); steerPolls++ } },
            { e: Exception -> if (steerPolls == 0) log("getCurrentSteeringWheelAngle FAIL $e") })
    }

    // ---------------------------------------------------------------- 2. 흔들림 계산 + 3. 엠비언트

    private var tickCount = 0
    private val ticker = object : Runnable {
        override fun run() {
            tickCount++
            if (::vehicle.isInitialized) runCatching { pollSteering() }
            val wheelDeg = if (steerIsHandwheel) steerRaw / STEERING_RATIO else steerRaw
            val level = est.update(SystemClock.elapsedRealtime(), speedMps, wheelDeg)
            if (ambientAuto && level != appliedLevel) applyAmbient(level, "auto")
            render(wheelDeg)
            if (tickCount % 5 == 0) {
                Log.i(TAG, "TICK v=%.2fm/s(%.1fkm/h) steer=%.1f wheel=%.2f lat=%.2f lon=%.2f shake=%.2f level=%s spdEv=%d stEv=%d stPoll=%d".format(
                    speedMps, speedMps * 3.6, steerRaw, wheelDeg, est.latAcc, est.lonAcc, est.shake,
                    est.level.name, speedEvents, steerEvents, steerPolls))
            }
            ui.postDelayed(this, TICK_MS)
        }
    }

    private fun probeAmbient() {
        val light = vehicle.getLight()
        light.checkAmbientLightBrightnessColorCapabilities(
            { cfg: CapabilityConfig, areas: List<SeatArea> ->
                pendingAmbientAreas = areas
                log("cap AmbientLightBrightnessColor access=${cfg.accessType} areas=${areas.map { a -> a.values.toList() }}")
                areas.forEach { a ->
                    light.setAmbientLightSwitch(a, LightSwitch.ON,
                        { _: SeatArea, sw: LightSwitch? -> log("set AmbientSwitch ON → OK $sw") },
                        { e: Exception -> log("set AmbientSwitch ON FAIL $e") })
                    light.setAmbientLightBrightness(a, 100,
                        { _: SeatArea, b: Int? -> log("set AmbientBrightness 100 → OK $b") },
                        { e: Exception -> log("set AmbientBrightness FAIL $e") })
                    light.getAmbientLightBrightnessColor(a,
                        { v: Int? -> ambientReadback = v?.let { hex(it) } ?: "null"; log("get AmbientColor ${a.values.toList()} = ${v?.let { hex(it) }} ($v)") },
                        { e: Exception -> log("get AmbientColor FAIL $e") })
                    light.getAmbientLightSwitch(a,
                        { s -> log("get AmbientSwitch ${a.values.toList()} = $s") },
                        { e: Exception -> log("get AmbientSwitch FAIL $e") })
                    runCatching {
                        light.registerAmbientLightBrightnessColorListener(a, object : AmbientLightBrightnessColorListener {
        override fun onFailed(e: Exception) { log("listener onFailed $e") }
                            override fun onBrightnessColorUpdated(color: Int?) {
                                ambientReadback = color?.let { hex(it) } ?: "null"
                                log("event AmbientColor ${a.values.toList()} = $ambientReadback")
                            }
                        })
                    }.onFailure { log("register AmbientColor FAIL $it") }
                }
            },
            { e: Exception -> log("cap AmbientLightBrightnessColor FAIL $e") })
        // 스위치 ON 이후에 색을 쓰도록 잠시 뒤 영역을 활성화한다.
        ui.postDelayed({ if (ambientAreas.isEmpty()) ambientAreas = pendingAmbientAreas; appliedLevel = null }, 800)
        light.checkAmbientLightBrightnessCapabilities(
            { cfg: CapabilityConfig, m: Map<SeatArea, AreaBounds<Int>> ->
                log("cap AmbientLightBrightness access=${cfg.accessType} ${m.entries.map { (k, v) -> "${k.values.toList()}:${v.minValue}~${v.maxValue}" }}")
            },
            { e: Exception -> log("cap AmbientLightBrightness FAIL $e") })
        light.checkAmbientLightSwitchCapabilities(
            { cfg: CapabilityConfig, m: Map<SeatArea, List<*>> ->
                log("cap AmbientLightSwitch access=${cfg.accessType} ${m.entries.map { (k, v) -> "${k.values.toList()}:$v" }}")
            },
            { e: Exception -> log("cap AmbientLightSwitch FAIL $e") })
        light.getAmbientColorAutoByDriveModeEnabled(
            { b: Boolean? -> log("get AmbientColorAutoByDriveMode = $b") },
            { e: Exception -> log("get AmbientColorAutoByDriveMode FAIL $e") })
    }

    private fun applyAmbient(level: Level, why: String) {
        val areas = ambientAreas
        if (areas.isEmpty()) return  // capability 확인 전
        appliedLevel = level
        val t0 = SystemClock.elapsedRealtime()
        areas.forEach { a ->
            vehicle.getLight().setAmbientLightBrightnessColor(a, level.rgb,
                { area: SeatArea, v: Int? ->
                    ambientLastResult = "OK ${level.label} ${v?.let { hex(it) }} ${SystemClock.elapsedRealtime() - t0}ms"
                    log("set AmbientColor[$why] ${area.values.toList()} ${level.label} → OK ${v?.let { hex(it) }} (${SystemClock.elapsedRealtime() - t0}ms)")
                },
                { e: Exception ->
                    ambientLastResult = "FAIL $e"
                    log("set AmbientColor[$why] ${a.values.toList()} ${level.label} → FAIL $e")
                })
        }
    }

    // ---------------------------------------------------------------- 4. 공조

    private var recircArea: SeatArea? = null
    private var fanArea: SeatArea? = null
    private var fanBounds: AreaBounds<Int>? = null

    private fun probeHvacCaps() {
        val hvac = vehicle.getHVAC()
        hvac.checkHvacRecirculationCapabilities(
            { cfg: CapabilityConfig, areas: List<SeatArea> ->
                log("cap HvacRecirculation access=${cfg.accessType} areas=${areas.map { it.values.toList() }}")
                recircArea = areas.firstOrNull()
            },
            { e: Exception -> log("cap HvacRecirculation FAIL $e") })
        hvac.checkHvacAutoRecirculationCapabilities(
            { cfg: CapabilityConfig, areas: List<SeatArea> ->
                log("cap HvacAutoRecirculation access=${cfg.accessType} areas=${areas.map { it.values.toList() }}")
            },
            { e: Exception -> log("cap HvacAutoRecirculation FAIL $e") })
        hvac.checkHvacFanSpeedCapabilities(
            { cfg: CapabilityConfig, m: Map<SeatArea, AreaBounds<Int>> ->
                log("cap HvacFanSpeed access=${cfg.accessType} ${m.entries.map { (k, v) -> "${k.values.toList()}:${v.minValue}~${v.maxValue}" }}")
                m.entries.firstOrNull()?.let { fanBounds = it.value; fanArea = it.key }
            },
            { e: Exception -> log("cap HvacFanSpeed FAIL $e") })
    }

    private fun runHvacTest() {
        val hvac = vehicle.getHVAC()
        val area = recircArea ?: SeatArea(SeatValue.ROW_1_LEFT)
        val fArea = fanArea ?: SeatArea(SeatValue.ROW_1_LEFT)
        log("HVAC test recircArea=${area.values.toList()} fanArea=${fArea.values.toList()}")
        runCatching {
            hvac.registerHvacRecirculationState(area, object : HvacRecirculationStateListener {
        override fun onFailed(e: Exception) { log("listener onFailed $e") }
                override fun onHvacAutoRecirculationStateUpdated(state: Boolean?) { log("event HvacRecirculation = $state") }
            })
            hvac.registerHvacFanSpeed(fArea, object : HvacFanSpeedListener {
        override fun onFailed(e: Exception) { log("listener onFailed $e") }
                override fun onHvacFanSpeedUpdated(speed: Int?) { log("event HvacFanSpeed = $speed") }
            })
        }.onFailure { log("HVAC register FAIL $it") }

        hvac.getHvacRecirculationState(area,
            { cur: Boolean? ->
                log("get HvacRecirculation = $cur → set ${cur != true}")
                hvac.setHvacRecirculation(area, cur != true,
                    { _: SeatArea, v: Boolean? ->
                        log("set HvacRecirculation OK → $v")
                        ui.postDelayed({
                            hvac.getHvacRecirculationState(area,
                                { r: Boolean? -> log("readback HvacRecirculation = $r") },
                                { e: Exception -> log("readback HvacRecirculation FAIL $e") })
                        }, 700)
                    },
                    { e: Exception -> log("set HvacRecirculation FAIL $e") })
            },
            { e: Exception -> log("get HvacRecirculation FAIL $e") })

        hvac.getHvacFanSpeed(fArea,
            { cur0: Int? ->
                val cur = cur0 ?: 0
                val b = fanBounds
                val next = if (b != null && cur >= (b.maxValue ?: cur)) (b.minValue ?: 1) else cur + 1
                log("get HvacFanSpeed = $cur → set $next")
                setFan(fArea, next, retryWithPower = true)
            },
            { e: Exception -> log("get HvacFanSpeed FAIL $e") })
    }

    /** 풍량 쓰기. 공조 전원이 꺼져 있으면 NOT_AVAILABLE(2)이 나므로 전원을 켜고 한 번 더 시도한다. */
    private fun setFan(fArea: SeatArea, value: Int, retryWithPower: Boolean) {
        val hvac = vehicle.getHVAC()
        hvac.setHvacFanSpeed(fArea, value,
            { _: SeatArea, v: Int? ->
                log("set HvacFanSpeed OK → $v")
                ui.postDelayed({
                    hvac.getHvacFanSpeed(fArea,
                        { r: Int? -> log("readback HvacFanSpeed = $r") },
                        { e: Exception -> log("readback HvacFanSpeed FAIL $e") })
                }, 700)
            },
            { e: Exception ->
                log("set HvacFanSpeed FAIL $e")
                if (!retryWithPower) return@setHvacFanSpeed
                hvac.checkHvacPowerCapabilities(
                    { cfg: CapabilityConfig, areas: List<SeatArea> ->
                        log("cap HvacPower access=${cfg.accessType} areas=${areas.map { it.values.toList() }}")
                        val pArea = areas.firstOrNull() ?: fArea
                        hvac.setHvacPower(pArea, true,
                            { _: SeatArea, on: Boolean? ->
                                log("set HvacPower ON → OK $on, retry fan")
                                ui.postDelayed({ setFan(fArea, value, retryWithPower = false) }, 500)
                            },
                            { e2: Exception -> log("set HvacPower FAIL $e2") })
                    },
                    { e2: Exception -> log("cap HvacPower FAIL $e2") })
            })
    }

    // ---------------------------------------------------------------- 덤: 뒷좌석 탑승

    private fun probeSeatOccupancy() {
        val seat = vehicle.getSeat()
        seat.checkSeatOccupancyCapabilities(
            { cfg: CapabilityConfig, areas: List<SeatArea> ->
                log("cap SeatOccupancy access=${cfg.accessType} areas=${areas.map { it.values.toList() }}")
                val targets = (areas + listOf(SeatArea(SeatValue.ROW_2_LEFT), SeatArea(SeatValue.ROW_2_RIGHT))).distinct()
                targets.forEach { a ->
                    seat.getCurrentSeatOccupancy(a,
                        { occ: Boolean? -> log("get SeatOccupancy ${a.values.toList()} = $occ") },
                        { e: Exception -> log("get SeatOccupancy ${a.values.toList()} FAIL $e") })
                    runCatching {
                        seat.registerSeatOccupancy(a, object : SeatOccupancyListener {
        override fun onFailed(e: Exception) { log("listener onFailed $e") }
                            override fun onSeatOccupancyUpdated(occ: Boolean?) { log("event SeatOccupancy ${a.values.toList()} = $occ") }
                        })
                    }
                }
            },
            { e: Exception -> log("cap SeatOccupancy FAIL $e") })
    }

    // ---------------------------------------------------------------- UI

    private fun showUx(r: CarUxRestrictions, src: String) {
        val txt = "UX[$src] requiresDO=${r.isRequiresDistractionOptimization} active=0x${Integer.toHexString(r.activeRestrictions)}"
        tvUx.text = txt
        log(txt)
    }

    private fun render(wheelDeg: Double) {
        tvMain.text = "차속 %.1f km/h (%.2f m/s)  ev=%d\n조향 raw %.1f° → 앞바퀴 %.2f°  poll=%d ev=%d%s".format(
            speedMps * 3.6, speedMps, speedEvents, steerRaw, wheelDeg, steerPolls, steerEvents,
            if (steerIsHandwheel) " (핸들각/$STEERING_RATIO)" else "")
        tvAcc.text = "횡 %.2f  종 %.2f  합 %.2f m/s²  (기준 %.1f, 최대 %.2f)".format(
            est.latAcc, est.lonAcc, est.shake, ShakeEstimator.SHAKE_THRESHOLD, est.peak)
        val lv = appliedLevel ?: est.level
        swatch.setBackgroundColor(Color.rgb(lv.rgb shr 16 and 0xFF, lv.rgb shr 8 and 0xFF, lv.rgb and 0xFF))
        swatch.text = "엠비언트 목표: ${lv.label}" + if (ambientAuto) "" else " (수동)"
        tvAmbient.text = "set 결과: $ambientLastResult\n읽은 값: $ambientReadback"
    }

    private fun buildUi() {
        val pad = 24
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        fun tv(size: Float) = TextView(this).apply { textSize = size; setPadding(0, 8, 0, 8) }.also { root.addView(it) }
        fun btn(label: String, onClick: () -> Unit) = Button(this).apply { text = label; setOnClickListener { onClick() } }

        tv(18f).text = screenName
        tvMain = tv(26f)
        tvAcc = tv(22f)
        swatch = tv(24f).apply { minHeight = 120; setTextColor(Color.WHITE) }
        tvAmbient = tv(16f)
        tvUx = tv(16f)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(btn("자동") { ambientAuto = true; appliedLevel = null; log("ambient auto") })
        Level.entries.forEach { lv -> row.addView(btn(lv.label) { ambientAuto = false; applyAmbient(lv, "manual") }) }
        row.addView(btn("공조 테스트") { runHvacTest() })
        row.addView(btn("최대값 리셋") { log("peak reset (was %.2f)".format(est.peak)); est.reset() })
        row.addView(btn("DO 없는 화면") { startActivity(Intent(this, PlainActivity::class.java)) })
        root.addView(row)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(btn("내비 경로") { naviProbe?.requestTestRoute() })
        row2.addView(btn("경로 취소") { naviProbe?.cancelRoute() })
        root.addView(row2)

        tvLog = tv(13f).apply { typeface = android.graphics.Typeface.MONOSPACE }
        setContentView(ScrollView(this).apply { addView(root) })
        root.visibility = View.VISIBLE
    }

    private fun hex(v: Int) = "0x" + Integer.toHexString(v).uppercase()

    protected fun log(msg: String) {
        Log.i(TAG, "[$screenName] $msg")
        runOnUiThread {
            logLines.addFirst(msg)
            while (logLines.size > 40) logLines.removeLast()
            if (::tvLog.isInitialized) tvLog.text = logLines.joinToString("\n")
        }
    }
}

/** distractionOptimized 메타데이터가 없는 비교용 화면 */
class PlainActivity : MainActivity() {
    override val screenName = "PlainActivity (DO 없음)"
}
