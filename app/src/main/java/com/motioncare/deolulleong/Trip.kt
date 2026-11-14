package com.motioncare.deolulleong

import kotlin.math.abs
import kotlin.math.max

/** 급조작 종류. 흔들림 구간의 최고점에서 좌우가 크면 급조향, 아니면 앞뒤 부호로 급가속/급제동. */
enum class EventKind(val label: String) {
    HARD_ACCEL("급가속"),
    HARD_BRAKE("급제동"),
    HARD_STEER("급조향"),
}

/** 흔들림 구간 하나(잔잔함을 벗어났다가 다시 잔잔함으로 돌아올 때까지). */
data class TripEvent(
    val kind: EventKind,
    val peakLevel: Level,   // 구간의 최고 단계(주의 또는 거침)
    val peakShake: Double,  // 구간의 최고 순간 흔들림(m/s^2)
    val atSec: Long,        // 주행 시작 후 경과 초
)

val FEEDBACK_OPTIONS = listOf("괜찮았어요", "조금 울렁", "힘들었어요")

/** 리포트 화면에 그리는 주행 요약(불변). */
data class TripReport(
    val id: Int,
    val durationSec: Long,
    val score: Int,
    val roughCount: Int,
    val cautionCount: Int,
    val events: List<TripEvent>,
    val hvacNote: String?,
    val feedback: String?,
)

/**
 * 주행 기록. 메모리에만 둔다(DB 없음, 앱을 닫으면 사라진다).
 * 승차감 점수 = 100 - 거침 구간 수 x 5 - 주의 구간 수 x 2 (0점 아래로 내려가지 않음).
 * '거침 1회'는 최고 단계가 거침이었던 흔들림 구간 하나, '주의 1회'는 최고 단계가 주의였던 구간 하나다.
 */
class Trip(val id: Int, private var startMs: Long) {
    val events = mutableListOf<TripEvent>()
    var moved = false; private set
    var hvacNote: String? = null
    var feedback: String? = null
    var endMs: Long? = null

    // 진행 중인 흔들림 구간
    private var open = false
    private var peakShake = 0.0
    private var peakLevel = Level.CALM
    private var peakLon = 0.0
    private var peakLat = 0.0
    private var openAtMs = 0L

    /** 처음 출발한 시점부터 주행 시간을 잰다. */
    fun markMoved(nowMs: Long) { moved = true; startMs = nowMs }

    fun elapsedSec(nowMs: Long) = (nowMs - startMs) / 1000

    /** 틱마다 부른다. 단계(유지 적용)가 잔잔함을 벗어나면 구간을 열고, 돌아오면 닫는다. */
    fun onSample(nowMs: Long, s: ShakeSample) {
        if (s.level > Level.CALM) {
            if (!open) {
                open = true; openAtMs = nowMs
                peakShake = 0.0; peakLevel = Level.CALM
            }
            if (s.shake > peakShake) {
                peakShake = s.shake; peakLon = s.lon; peakLat = s.lat
            }
            if (s.level > peakLevel) peakLevel = s.level
        } else if (open) {
            close()
        }
    }

    fun close() {
        if (!open) return
        open = false
        val kind = when {
            abs(peakLat) >= abs(peakLon) -> EventKind.HARD_STEER
            peakLon >= 0 -> EventKind.HARD_ACCEL
            else -> EventKind.HARD_BRAKE
        }
        events += TripEvent(kind, peakLevel, peakShake, (openAtMs - startMs) / 1000)
    }

    val roughCount get() = events.count { it.peakLevel == Level.ROUGH }
    val cautionCount get() = events.count { it.peakLevel == Level.CAUTION }
    val score get() = max(0, 100 - roughCount * 5 - cautionCount * 2)

    fun report(nowMs: Long) = TripReport(
        id = id,
        durationSec = ((endMs ?: nowMs) - startMs) / 1000,
        score = score,
        roughCount = roughCount,
        cautionCount = cautionCount,
        events = events.toList(),
        hvacNote = hvacNote,
        feedback = feedback,
    )
}

fun mmss(sec: Long) = "%02d:%02d".format(sec / 60, sec % 60)
