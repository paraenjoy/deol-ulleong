package com.motioncare.spike

import kotlin.math.sqrt
import kotlin.math.tan

/** 흔들림 단계. 엠비언트 색과 1:1로 대응한다. */
enum class Level(val rgb: Int, val label: String) {
    CALM(0x00C853, "초록"),
    ROUGH(0xFF8F00, "주황"),
    HARSH(0xD50000, "빨강"),
}

/**
 * 차속(m/s)과 앞바퀴 조향각(도)으로 횡·종가속도를 추정한다.
 * - 횡가속도 ≈ v²·tan(δ)/L
 * - 종가속도 = 차속 변화율(DV_WINDOW_MS 구간 차분) → 이동평균
 */
class ShakeEstimator(
    private val wheelbaseM: Double = WHEELBASE_M,
    private val threshold: Double = SHAKE_THRESHOLD,
) {
    companion object {
        /** CarInfo에 축거 API가 없어 상수로 둔다. */
        const val WHEELBASE_M = 2.95
        /** 흔들림 기준(m/s²). 이 값 하나로 민감도를 바꾼다. */
        const val SHAKE_THRESHOLD = 2.0
        /** 주황 → 빨강 배수 */
        const val HARSH_FACTOR = 1.5
        /** 종가속도 차분 구간 */
        const val DV_WINDOW_MS = 500L
        /** 이동평균 샘플 수(틱 100ms 기준 0.3s) */
        const val MA_SIZE = 3
        /** 단계가 내려가기 전 유지 시간(깜빡임 방지) */
        const val HOLD_MS = 1500L
    }

    private val speedHist = ArrayDeque<Pair<Long, Double>>()
    private val latMa = ArrayDeque<Double>()
    private val lonMa = ArrayDeque<Double>()

    var latAcc = 0.0; private set
    var lonAcc = 0.0; private set
    var shake = 0.0; private set
    var peak = 0.0; private set
    var level = Level.CALM; private set
    private var lastRaiseMs = 0L

    /** @param wheelDeg 앞바퀴 각도(도). 좌/우 부호는 횡가속도 크기에만 쓰므로 상관없다. */
    fun update(nowMs: Long, speedMps: Double, wheelDeg: Double): Level {
        speedHist.addLast(nowMs to speedMps)
        while (speedHist.size > 2 && nowMs - speedHist[1].first >= DV_WINDOW_MS) speedHist.removeFirst()
        val (t0, v0) = speedHist.first()
        val dt = (nowMs - t0) / 1000.0
        val lonRaw = if (dt > 0.05) (speedMps - v0) / dt else 0.0

        val delta = Math.toRadians(wheelDeg.coerceIn(-45.0, 45.0))
        val latRaw = speedMps * speedMps * tan(delta) / wheelbaseM

        lonAcc = push(lonMa, lonRaw)
        latAcc = push(latMa, latRaw)
        shake = sqrt(latAcc * latAcc + lonAcc * lonAcc)
        if (shake > peak) peak = shake

        val target = when {
            shake >= threshold * HARSH_FACTOR -> Level.HARSH
            shake >= threshold -> Level.ROUGH
            else -> Level.CALM
        }
        if (target.ordinal > level.ordinal) {
            level = target; lastRaiseMs = nowMs
        } else if (target.ordinal < level.ordinal && nowMs - lastRaiseMs >= HOLD_MS) {
            level = target; lastRaiseMs = nowMs
        } else if (target == level) {
            lastRaiseMs = nowMs
        }
        return level
    }

    private fun push(q: ArrayDeque<Double>, v: Double): Double {
        q.addLast(v)
        while (q.size > MA_SIZE) q.removeFirst()
        return q.sum() / q.size
    }

    /** 첫 차속 수신 시 0→현재속도 점프를 가속으로 오인하지 않도록 비운다. */
    fun reset() {
        speedHist.clear(); latMa.clear(); lonMa.clear()
        latAcc = 0.0; lonAcc = 0.0; shake = 0.0; peak = 0.0; level = Level.CALM
    }
}
