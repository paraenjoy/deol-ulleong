package com.motioncare.deolulleong

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.tan

/*
 * 흔들림 계산 명세 (README의 '흔들림 계산 명세'와 같은 내용)
 *
 * 처리 순서: 원신호 유효성 검사 -> 추정 -> 필터 -> 표시용 범위 제한
 *
 * 1. 원신호 유효성 검사
 *    - 차속 0~70 m/s, 핸들각 -470~500도 밖의 값은 그 샘플을 통째로 버린다.
 * 2. 추정
 *    - 앞뒤 가속도: 0.5초 구간 차속 차분 (v_now - v_0.5s전) / 경과시간
 *      차분 결과 |a| > 12 m/s^2 이면 신호 이상으로 보고 버린다(이동평균에 넣지 않음).
 *    - 좌우 가속도: v^2 * tan(핸들각 / 조향비) / 축거. 조향비 15, 축거 2.95 m (상수).
 * 3. 필터
 *    - 앞뒤, 좌우 모두 3샘플 이동평균(틱 0.1초 기준 0.3초).
 *    - 순간 흔들림 = sqrt(앞뒤^2 + 좌우^2) (필터를 거친 값으로 계산)
 *    - 단계: 2.0 미만 잔잔함, 2.0 이상 주의, 3.0 이상 거침.
 *      올라갈 때는 바로 올리고, 내려갈 때는 낮은 단계가 1.5초 이어져야 내린다.
 * 4. 표시용 범위 제한
 *    - 화면과 로그에 보이는 좌우 가속도는 +-9.8 m/s^2 에서 자른다. 단계 판정은 자르기 전 값으로 한다.
 *
 * 멀미 부하 (MotionLoad): 최근 LOAD_WINDOW_MS 동안 순간 흔들림이 주의 이상(2.0 이상)이었던 시간의 합.
 *    LOAD_TRIGGER_MS 를 넘으면 공조 케어를 한다. 두 값은 데모용 값이다.
 *
 * 차량 신호가 SIGNAL_TIMEOUT_MS(2초) 넘게 안 오면 모든 제어를 멈추고 '신호 확인 중'을 띄운다(CareEngine).
 */
object Spec {
    // 1. 원신호 유효 범위
    const val SPEED_MIN_MPS = 0.0
    const val SPEED_MAX_MPS = 70.0
    const val WHEEL_MIN_DEG = -470.0
    const val WHEEL_MAX_DEG = 500.0

    // 2. 추정
    const val STEERING_RATIO = 15.0
    const val WHEELBASE_M = 2.95
    const val DV_WINDOW_MS = 500L
    const val LON_ANOMALY_MPS2 = 12.0

    // 3. 필터, 단계
    const val MA_SIZE = 3
    const val CAUTION_MPS2 = 2.0
    const val ROUGH_MPS2 = 3.0
    const val LEVEL_DOWN_HOLD_MS = 1500L

    // 4. 표시용 범위 제한
    const val LAT_DISPLAY_LIMIT_MPS2 = 9.8

    // 멀미 부하 (데모용 값: 실제 주행용 값은 2차에서 정한다)
    const val LOAD_WINDOW_MS = 60_000L
    const val LOAD_TRIGGER_MS = 4_000L

    // 신호, 주기
    const val SIGNAL_TIMEOUT_MS = 2_000L
    const val TICK_MS = 100L
}

/** 흔들림 단계. 무드등 색과 화면 단어가 1:1로 대응한다. 빨강은 쓰지 않는다. */
enum class Level(val rgb: Int, val word: String) {
    CALM(0x00BFA5, "잔잔"),     // 청록
    CAUTION(0xFFB300, "주의"),  // 호박
    ROUGH(0xFF6D00, "거침"),    // 진한 주황
}

fun levelOf(shake: Double): Level = when {
    shake >= Spec.ROUGH_MPS2 -> Level.ROUGH
    shake >= Spec.CAUTION_MPS2 -> Level.CAUTION
    else -> Level.CALM
}

/** 한 틱의 계산 결과 */
data class ShakeSample(
    val lon: Double,          // 앞뒤 가속도(필터 후), m/s^2. +가속, -감속
    val lat: Double,          // 좌우 가속도(필터 후, 범위 제한 전)
    val latDisplay: Double,   // 좌우 가속도 표시값(+-9.8 제한)
    val shake: Double,        // 순간 흔들림
    val instantLevel: Level,  // 순간 흔들림 그대로의 단계
    val level: Level,         // 내려갈 때 1.5초 유지를 적용한 단계(무드등, 화면에 쓰는 값)
)

class ShakeEstimator {
    private val speedHist = ArrayDeque<Pair<Long, Double>>()
    private val lonMa = ArrayDeque<Double>()
    private val latMa = ArrayDeque<Double>()
    private var belowSinceMs = -1L

    var level = Level.CALM; private set
    var last: ShakeSample = ShakeSample(0.0, 0.0, 0.0, 0.0, Level.CALM, Level.CALM); private set
    var invalidInputs = 0; private set
    var lonAnomalies = 0; private set

    /**
     * @param speedMps 차속(m/s), PERF_VEHICLE_SPEED
     * @param wheelDeg 핸들각(도), SteeringWheel. 부호는 크기에만 쓰므로 콘솔과 반대여도 상관없다.
     * @return 이번 틱 결과. 원신호가 유효 범위 밖이면 null(샘플을 버림).
     */
    fun update(nowMs: Long, speedMps: Double, wheelDeg: Double): ShakeSample? {
        // 1. 원신호 유효성 검사
        if (speedMps.isNaN() || speedMps < Spec.SPEED_MIN_MPS || speedMps > Spec.SPEED_MAX_MPS ||
            wheelDeg.isNaN() || wheelDeg < Spec.WHEEL_MIN_DEG || wheelDeg > Spec.WHEEL_MAX_DEG
        ) {
            invalidInputs++
            return null
        }

        // 2. 추정: 앞뒤 (0.5초 구간 차분)
        speedHist.addLast(nowMs to speedMps)
        while (speedHist.size > 2 && nowMs - speedHist[1].first >= Spec.DV_WINDOW_MS) speedHist.removeFirst()
        val (t0, v0) = speedHist.first()
        val dt = (nowMs - t0) / 1000.0
        var lonRaw: Double? = if (dt >= 0.2) (speedMps - v0) / dt else 0.0
        if (lonRaw != null && abs(lonRaw) > Spec.LON_ANOMALY_MPS2) {
            lonAnomalies++
            lonRaw = null // 신호 이상: 버린다
        }
        // 2. 추정: 좌우 (핸들각 / 조향비 = 앞바퀴각)
        val delta = Math.toRadians(wheelDeg / Spec.STEERING_RATIO)
        val latRaw = speedMps * speedMps * tan(delta) / Spec.WHEELBASE_M

        // 3. 필터
        val lon = if (lonRaw != null) push(lonMa, lonRaw) else lonMa.averageOrZero()
        val lat = push(latMa, latRaw)
        val shake = hypot(lon, lat)
        val instant = levelOf(shake)
        if (instant >= level) {
            level = instant
            belowSinceMs = -1L
        } else {
            if (belowSinceMs < 0) belowSinceMs = nowMs
            if (nowMs - belowSinceMs >= Spec.LEVEL_DOWN_HOLD_MS) {
                level = instant
                belowSinceMs = -1L
            }
        }

        // 4. 표시용 범위 제한
        val latDisplay = lat.coerceIn(-Spec.LAT_DISPLAY_LIMIT_MPS2, Spec.LAT_DISPLAY_LIMIT_MPS2)

        return ShakeSample(lon, lat, latDisplay, shake, instant, level).also { last = it }
    }

    /** 주행 시작, 신호 복구 때 부른다. 이전 차속과의 차이를 가속으로 오인하지 않게 한다. */
    fun reset() {
        speedHist.clear(); lonMa.clear(); latMa.clear()
        level = Level.CALM; belowSinceMs = -1L
        last = ShakeSample(0.0, 0.0, 0.0, 0.0, Level.CALM, Level.CALM)
    }

    private fun push(q: ArrayDeque<Double>, v: Double): Double {
        q.addLast(v)
        while (q.size > Spec.MA_SIZE) q.removeFirst()
        return q.sum() / q.size
    }

    private fun ArrayDeque<Double>.averageOrZero() = if (isEmpty()) 0.0 else sum() / size
}

/** 멀미 부하: 최근 LOAD_WINDOW_MS 동안 순간 흔들림이 주의 이상이었던 시간의 합(ms). */
class MotionLoad {
    private val highTicks = ArrayDeque<Pair<Long, Long>>() // (시각, 지속 ms)
    private var lastMs = -1L

    var loadMs = 0L; private set

    fun update(nowMs: Long, instant: Level): Long {
        val dt = if (lastMs < 0) 0L else (nowMs - lastMs).coerceIn(0L, 500L)
        lastMs = nowMs
        if (instant >= Level.CAUTION && dt > 0) highTicks.addLast(nowMs to dt)
        while (highTicks.isNotEmpty() && nowMs - highTicks.first().first > Spec.LOAD_WINDOW_MS) highTicks.removeFirst()
        loadMs = highTicks.sumOf { it.second }
        return loadMs
    }

    fun reset() { highTicks.clear(); lastMs = -1L; loadMs = 0L }

    val exceeded get() = loadMs >= Spec.LOAD_TRIGGER_MS
}
