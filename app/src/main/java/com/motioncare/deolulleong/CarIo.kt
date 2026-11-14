package com.motioncare.deolulleong

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.listener.AmbientLightBrightnessColorListener
import ai.pleos.playground.vehicle.listener.HvacFanSpeedListener
import ai.pleos.playground.vehicle.listener.HvacPowerStateListener
import ai.pleos.playground.vehicle.listener.HvacRecirculationStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import android.os.Handler
import android.os.SystemClock
import kotlin.math.min

/**
 * 에뮬레이터 관찰 보정, 원인 확인 중.
 * Pleos Connect Emulator v2.1.0 + Vehicle SDK 2.3.2 에서 SeatOccupancy Boolean이 실제와 반대로 나온다
 * (콘솔에서 탑승 -> SDK false, 비움 -> SDK true. VHAL 원시값은 정상: 2=OCCUPIED, 1=VACANT).
 * SDK와 에뮬레이터 중 어느 쪽 문제인지 확인 중이며 Pleos 기술지원에 문의할 예정이다.
 * 실차나 고쳐진 버전에서는 false로 바꾼다.
 */
const val SEAT_OCCUPANCY_EMULATOR_INVERT = true

private fun SeatArea.names() = values.joinToString("+") { it.name }

private fun hex(v: Int?) = v?.let { "#%06X".format(it and 0xFFFFFF) } ?: "?"

/** 무드등 케어: 시작 전 원래 값(스위치, 밝기, 색)을 저장하고, 단계 색을 쓰고, 끝나면 되돌린다. */
class AmbientCare(vehicle: Vehicle, private val main: Handler, private val log: (String) -> Unit) {
    private val light = vehicle.getLight()
    private enum class St { IDLE, SNAPSHOT, ACTIVE }
    private data class Snap(val sw: LightSwitch?, val brightness: Int?, val color: Int?)

    private var st = St.IDLE
    private var gen = 0
    private var area: SeatArea? = null
    private var snap: Snap? = null
    private var applied: Level? = null
    private var lastFailMs = 0L

    /** 차량이 알려 준 현재 무드등 색(리스너) */
    var currentColor: Int? = null; private set
    var status = "확인 전"; private set
    val active get() = st == St.ACTIVE

    private val colorListener = object : AmbientLightBrightnessColorListener {
        override fun onFailed(e: Exception) { log("ambient listener FAIL $e") }
        override fun onBrightnessColorUpdated(color: Int?) { main.post { currentColor = color } }
    }

    fun probe() {
        light.checkAmbientLightBrightnessColorCapabilities(
            { cfg: CapabilityConfig, areas: List<SeatArea> ->
                main.post {
                    // 이 에뮬레이터는 5좌석을 묶은 영역 하나만 받는다. capability가 준 영역을 그대로 쓴다.
                    area = areas.firstOrNull()
                    status = if (area == null) "지원 안 됨" else "준비됨"
                    log("ambient cap access=${cfg.accessType} area=${area?.names()}")
                    area?.let { a ->
                        runCatching { light.registerAmbientLightBrightnessColorListener(a, colorListener) }
                        light.getAmbientLightBrightnessColor(a, { c: Int? -> main.post { currentColor = c } }, { _: Exception -> })
                    }
                }
            },
            { e: Exception -> main.post { status = "확인 실패"; log("ambient cap FAIL $e") } })
    }

    /** 케어 시작: 원래 값 저장 -> 스위치 켜기, 밝기 100 -> 이후 apply()로 색을 쓴다. */
    fun begin() {
        val a = area ?: run { log("ambient: 영역 확인 전이라 건너뜀"); return }
        if (st != St.IDLE) return
        st = St.SNAPSHOT
        val g = ++gen
        val parts = arrayOfNulls<Any>(3)
        var n = 0
        fun got(i: Int, v: Any?) = main.post {
            if (g != gen) return@post
            parts[i] = v
            if (++n == 3) onSnapshot(g, a, Snap(parts[0] as LightSwitch?, parts[1] as Int?, parts[2] as Int?))
        }
        light.getAmbientLightSwitch(a, { s: LightSwitch? -> got(0, s) }, { _: Exception -> got(0, null) })
        light.getAmbientLightBrightness(a, { b: Int? -> got(1, b) }, { _: Exception -> got(1, null) })
        light.getAmbientLightBrightnessColor(a, { c: Int? -> got(2, c) }, { _: Exception -> got(2, null) })
    }

    private fun onSnapshot(g: Int, a: SeatArea, s: Snap) {
        if (g != gen || st != St.SNAPSHOT) return
        snap = s
        log("ambient 원래 값 저장: 스위치 ${s.sw}, 밝기 ${s.brightness}, 색 ${hex(s.color)}")
        if (s.sw != LightSwitch.ON) light.setAmbientLightSwitch(a, LightSwitch.ON, { _: SeatArea, _: LightSwitch? -> }, { e: Exception -> log("ambient switch ON FAIL $e") })
        if (s.brightness != 100) light.setAmbientLightBrightness(a, 100, { _: SeatArea, _: Int? -> }, { e: Exception -> log("ambient brightness FAIL $e") })
        st = St.ACTIVE
        applied = null
        status = "케어 중"
    }

    /** 단계가 바뀔 때만 색을 쓴다. 실패하면 1초 뒤 다시 쓴다. */
    fun apply(level: Level) {
        val a = area ?: return
        if (st != St.ACTIVE || level == applied) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastFailMs < 1000) return
        applied = level
        val t0 = now
        light.setAmbientLightBrightnessColor(a, level.rgb,
            { _: SeatArea, v: Int? -> log("ambient ${level.word} ${hex(level.rgb)} -> OK ${hex(v)} (${SystemClock.elapsedRealtime() - t0}ms)") },
            { e: Exception ->
                main.post { if (applied == level) applied = null; lastFailMs = SystemClock.elapsedRealtime() }
                log("ambient ${level.word} FAIL $e")
            })
    }

    /** 원래 값으로 되돌린다(색 -> 밝기 -> 스위치 순서). 저장 중이었으면 취소만 한다. */
    fun end(reason: String) {
        gen++
        val a = area
        val s = snap
        if (st == St.ACTIVE && a != null && s != null) {
            log("ambient 복원($reason): 색 ${hex(s.color)}, 밝기 ${s.brightness}, 스위치 ${s.sw}")
            s.color?.let { light.setAmbientLightBrightnessColor(a, it, { _: SeatArea, _: Int? -> }, { e: Exception -> log("ambient restore color FAIL $e") }) }
            s.brightness?.let { light.setAmbientLightBrightness(a, it, { _: SeatArea, _: Int? -> }, { e: Exception -> log("ambient restore brightness FAIL $e") }) }
            s.sw?.let { light.setAmbientLightSwitch(a, it, { _: SeatArea, _: LightSwitch? -> }, { e: Exception -> log("ambient restore switch FAIL $e") }) }
        }
        st = St.IDLE
        snap = null
        applied = null
        if (area != null) status = "준비됨"
    }

    fun release() {
        area?.let { runCatching { light.unregisterAmbientLightBrightnessColorListener(it, colorListener) } }
    }
}

/**
 * 공조 케어: 공조 전원이 켜져 있을 때만, 한 주행에 한 번 외기 유입 + 풍량 +1.
 * 바꾸기 직전 값을 저장해 두고 끝날 때 되돌린다. 바꾼 뒤 사용자가 공조를 바꾸면 그 주행에서는 더 건드리지 않는다(복원도 안 함).
 */
class HvacCare(vehicle: Vehicle, private val main: Handler, private val log: (String) -> Unit) {
    private val hvac = vehicle.getHVAC()
    private data class Changed(val origRecirc: Boolean?, val origFan: Int?, val ourFan: Int?, val atMs: Long)

    private var powerArea: SeatArea? = null
    private var recircArea: SeatArea? = null
    private var fanArea: SeatArea? = null
    private var fanMax = 8

    var power: Boolean? = null; private set
    var recirc: Boolean? = null; private set
    var fan: Int? = null; private set

    private var changed: Changed? = null
    private var doneThisTrip = false
    var userOverride = false; private set
    private var powerOffLogged = false

    private val powerListener = object : HvacPowerStateListener {
        override fun onFailed(e: Exception) { log("hvac power listener FAIL $e") }
        override fun onHvacPowerStateUpdated(on: Boolean?) { main.post { power = on } }
    }
    private val recircListener = object : HvacRecirculationStateListener {
        override fun onFailed(e: Exception) { log("hvac recirc listener FAIL $e") }
        override fun onHvacAutoRecirculationStateUpdated(state: Boolean?) { main.post { recirc = state; checkOverride() } }
    }
    private val fanListener = object : HvacFanSpeedListener {
        override fun onFailed(e: Exception) { log("hvac fan listener FAIL $e") }
        override fun onHvacFanSpeedUpdated(speed: Int?) { main.post { fan = speed; checkOverride() } }
    }

    fun probe() {
        hvac.checkHvacPowerCapabilities(
            { _: CapabilityConfig, areas: List<SeatArea> ->
                main.post {
                    powerArea = areas.firstOrNull()?.also { a ->
                        runCatching { hvac.registerHvacPowerState(a, powerListener) }
                        hvac.getHvacPowerState(a, { v: Boolean? -> main.post { power = v } }, { e: Exception -> log("hvac get power FAIL $e") })
                    }
                    log("hvac power area=${powerArea?.names()}")
                }
            },
            { e: Exception -> log("hvac power cap FAIL $e") })
        hvac.checkHvacRecirculationCapabilities(
            { _: CapabilityConfig, areas: List<SeatArea> ->
                main.post {
                    recircArea = areas.firstOrNull()?.also { a ->
                        runCatching { hvac.registerHvacRecirculationState(a, recircListener) }
                        hvac.getHvacRecirculationState(a, { v: Boolean? -> main.post { recirc = v } }, { e: Exception -> log("hvac get recirc FAIL $e") })
                    }
                    log("hvac recirc area=${recircArea?.names()}")
                }
            },
            { e: Exception -> log("hvac recirc cap FAIL $e") })
        hvac.checkHvacFanSpeedCapabilities(
            { _: CapabilityConfig, m: Map<SeatArea, AreaBounds<Int>> ->
                main.post {
                    m.entries.firstOrNull()?.let { (a, b) ->
                        fanArea = a
                        fanMax = b.maxValue ?: 8
                        runCatching { hvac.registerHvacFanSpeed(a, fanListener) }
                        hvac.getHvacFanSpeed(a, { v: Int? -> main.post { fan = v } }, { e: Exception -> log("hvac get fan FAIL $e") })
                    }
                    log("hvac fan area=${fanArea?.names()} max=$fanMax")
                }
            },
            { e: Exception -> log("hvac fan cap FAIL $e") })
    }

    /** 새 주행 시작: 지금 공조 값을 기록하고, 주행당 1회 제한과 사용자 우선 표시를 초기화한다. */
    fun startTrip() {
        changed = null; doneThisTrip = false; userOverride = false; powerOffLogged = false
        log("hvac 시작 상태: 전원 $power, 내기순환 $recirc, 풍량 $fan")
    }

    /** 멀미 부하가 기준을 넘었을 때 부른다. 실제로 바꿨으면 리포트용 설명을 돌려준다. */
    fun tryCare(nowMs: Long): String? {
        if (doneThisTrip || userOverride) return null
        if (power != true) {
            if (!powerOffLogged) { log("hvac: 공조 전원이 꺼져 있어 공조 케어를 건너뜀"); powerOffLogged = true }
            return null
        }
        val rA = recircArea
        val fA = fanArea
        if (rA == null && fA == null) return null
        doneThisTrip = true
        val origR = recirc
        val origF = fan
        val newF = origF?.let { min(it + 1, fanMax) }
        changed = Changed(origR, origF, newF, nowMs)
        log("hvac 케어: 내기순환 $origR -> false, 풍량 $origF -> $newF")
        rA?.let { hvac.setHvacRecirculation(it, false, { _: SeatArea, v: Boolean? -> log("hvac recirc set OK $v") }, { e: Exception -> log("hvac recirc set FAIL $e") }) }
        if (fA != null && newF != null && newF != origF) {
            hvac.setHvacFanSpeed(fA, newF, { _: SeatArea, v: Int? -> log("hvac fan set OK $v") }, { e: Exception -> log("hvac fan set FAIL $e") })
        }
        val fanText = when {
            origF == null -> ""
            newF == origF -> ", 풍량 최대라 유지"
            else -> ", 풍량 ${origF}에서 ${newF}로"
        }
        return "외기 유입$fanText"
    }

    private fun checkOverride() {
        val c = changed ?: return
        if (userOverride) return
        if (SystemClock.elapsedRealtime() - c.atMs < 1500) return // 우리가 쓴 값이 돌아오는 시간
        val r = recirc
        val f = fan
        if ((r != null && r) || (c.ourFan != null && f != null && f != c.ourFan)) {
            userOverride = true
            log("hvac: 사용자가 공조를 바꿈(내기순환 $r, 풍량 $f). 이번 주행에서는 공조를 더 건드리지 않음")
        }
    }

    /** 우리가 바꾼 값을 되돌린다. 사용자가 바꾼 뒤라면 사용자 설정을 그대로 둔다. */
    fun restore(reason: String) {
        val c = changed ?: return
        changed = null
        if (userOverride) { log("hvac 복원 안 함($reason): 사용자 설정 우선"); return }
        log("hvac 복원($reason): 내기순환 ${c.origRecirc}, 풍량 ${c.origFan}")
        val rA = recircArea
        val fA = fanArea
        if (rA != null && c.origRecirc != null) hvac.setHvacRecirculation(rA, c.origRecirc, { _: SeatArea, _: Boolean? -> }, { e: Exception -> log("hvac restore recirc FAIL $e") })
        if (fA != null && c.origFan != null && c.origFan != c.ourFan) hvac.setHvacFanSpeed(fA, c.origFan, { _: SeatArea, _: Int? -> }, { e: Exception -> log("hvac restore fan FAIL $e") })
    }

    val statusText get() = "전원 ${onOff(power)}, 내기순환 ${onOff(recirc)}, 풍량 ${fan ?: "?"}"

    fun release() {
        powerArea?.let { runCatching { hvac.unregisterHvacPowerState(it, powerListener) } }
        recircArea?.let { runCatching { hvac.unregisterHvacRecirculationState(it, recircListener) } }
        fanArea?.let { runCatching { hvac.unregisterHvacFanSpeed(it, fanListener) } }
    }
}

private fun onOff(b: Boolean?) = when (b) { true -> "켜짐"; false -> "꺼짐"; null -> "?" }

/** 2열 탑승 감지: 앱이 켜져 있는 동안 2열 좌석의 SeatOccupancy를 구독한다. */
class RearSeatWatcher(
    vehicle: Vehicle,
    private val main: Handler,
    private val log: (String) -> Unit,
    private val onChange: (Boolean) -> Unit,
) {
    private val seat = vehicle.getSeat()
    private val seats = mutableMapOf<String, Boolean>()
    private val listeners = mutableListOf<Pair<SeatArea, SeatOccupancyListener>>()

    var occupied: Boolean? = null; private set

    fun probe() {
        seat.checkSeatOccupancyCapabilities(
            { _: CapabilityConfig, areas: List<SeatArea> ->
                main.post {
                    val rear = areas.filter { a -> a.values.any { it.name.startsWith("ROW_2") } }
                    log("seat cap rear=${rear.map { it.names() }}")
                    subscribe(rear.ifEmpty { listOf(SeatArea(SeatValue.ROW_2_LEFT), SeatArea(SeatValue.ROW_2_RIGHT)) })
                }
            },
            { e: Exception ->
                log("seat cap FAIL $e")
                main.post { subscribe(listOf(SeatArea(SeatValue.ROW_2_LEFT), SeatArea(SeatValue.ROW_2_RIGHT))) }
            })
    }

    private fun subscribe(areas: List<SeatArea>) {
        areas.forEach { a ->
            val key = a.names()
            val l = object : SeatOccupancyListener {
                override fun onFailed(e: Exception) { log("seat listener FAIL $key $e") }
                override fun onSeatOccupancyUpdated(occ: Boolean?) { main.post { set(key, occ) } }
            }
            runCatching { seat.registerSeatOccupancy(a, l); listeners += a to l }
            seat.getCurrentSeatOccupancy(a, { occ: Boolean? -> main.post { set(key, occ) } }, { e: Exception -> log("seat get FAIL $key $e") })
        }
    }

    private fun set(key: String, sdkValue: Boolean?) {
        if (sdkValue == null) return
        val occ = if (SEAT_OCCUPANCY_EMULATOR_INVERT) !sdkValue else sdkValue
        if (seats[key] == occ) return
        seats[key] = occ
        val any = seats.values.any { it }
        log("seat $key sdk=$sdkValue -> 탑승 $occ (2열 전체 $any)")
        if (occupied != any) {
            occupied = any
            onChange(any)
        }
    }

    fun release() {
        listeners.forEach { (a, l) -> runCatching { seat.unregisterSeatOccupancy(a, l) } }
        listeners.clear()
    }
}
