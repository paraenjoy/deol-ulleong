package com.motioncare.spike

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlin.math.abs

/**
 * 덤 확인: 에뮬레이터 내비 앱과 NaviHelper SDK 연동.
 * - 연결·현재 위치·경로 상태 조회
 * - requestRoute로 목적지를 걸고, 안내 중 TBT(다음 회전) 콜백을 받는다.
 * 결과는 log로만 남긴다(태그 MCSPIKE, 접두어 "navi").
 */
class NaviProbe(context: Context, private val log: (String) -> Unit) {
    private val navi = NaviHelper(context)
    private val ui = Handler(Looper.getMainLooper())

    private var lastSpeedLimit: Int? = null
    private var lastTbtKey = ""
    private var lastTbtDist = -1.0
    private var tbtCount = 0
    private var pollCount = 0

    private val listener = object : NaviHelperEventListener {
        override fun onError(code: NaviErrorCode) = log("navi onError $code")
        override fun onRouteStateInfo(info: RouteStateInfo) =
            log("navi onRouteStateInfo isRouting=${info.isRouting} naviMode=${info.naviMode} option=${info.routeOption}")
        override fun onCurrentLocationInfo(info: CurrentLocationInfo) = log("navi onCurrentLocationInfo $info")
        override fun onNavigationStatus(status: NavigationStatus) = log("navi onNavigationStatus $status")
        override fun onDrivingInfo(info: DrivingInfo) = log("navi onDrivingInfo $info")
        override fun onCurrentRoadSpeedLimit(limit: Int) {
            if (limit != lastSpeedLimit) { lastSpeedLimit = limit; log("navi onCurrentRoadSpeedLimit $limit") }
        }
        override fun onRouteStarted(info: RouteStartInfo) = log("navi onRouteStarted $info")
        override fun onRoutingCompleted() = log("navi onRoutingCompleted")
        override fun onRouteEnded() = log("navi onRouteEnded")
        override fun onRouteCancelled() = log("navi onRouteCancelled")
        override fun onDestinationArrived(info: DestinationArrivedInfo) = log("navi onDestinationArrived $info")
        override fun onCautionPointInfo(list: List<CautionPoint>) = log("navi onCautionPointInfo n=${list.size} ${list.take(3)}")
        override fun onTBTInfo(list: List<TBTInfo>) {
            tbtCount++
            // 회전 종류·설명이 바뀌었거나 거리가 50 m 이상 줄었을 때만 남긴다(콜백이 잦을 수 있음).
            val key = list.joinToString { "${it.type}|${it.description}" }
            val d = list.firstOrNull()?.distance ?: -1.0
            if (tbtCount == 1 || key != lastTbtKey || abs(d - lastTbtDist) >= 50) {
                lastTbtKey = key; lastTbtDist = d
                log("navi onTBTInfo #$tbtCount n=${list.size} " +
                    list.joinToString(" / ") { "${it.type} %.0fm '%s'".format(it.distance, it.description) })
            }
        }
    }

    private val tbtPoll = object : Runnable {
        override fun run() {
            pollCount++
            runCatching { navi.getTBTInfo() }.onFailure { log("navi getTBTInfo FAIL $it") }
            if (pollCount % 5 == 0) log("navi poll getTBTInfo x$pollCount, callbacks so far=$tbtCount")
            ui.postDelayed(this, 2000)
        }
    }

    fun start() {
        navi.initialize(
            {
                log("navi connected")
                navi.addListener(listener)
                runCatching {
                    navi.getRouteStateInfo()
                    navi.getCurrentLocationInfo()
                    navi.getTBTInfo()
                }.onFailure { log("navi get* FAIL $it") }
                // 경로가 이미 걸려 있을 수도 있으니 연결 직후부터 2초마다 TBT를 조회한다.
                ui.removeCallbacks(tbtPoll)
                ui.postDelayed(tbtPoll, 2000)
            },
            { t: Throwable -> log("navi initialize FAIL $t") },
        )
    }

    /** 판교역 근처로 경로를 건다(에뮬레이터 기본 위치 성남 수정구에서 약 2.5 km). */
    fun requestTestRoute() {
        val dest = RouteInfo(
            37.394776, 127.111148, // latitude, longitude
            null, null,            // displayLatitude, displayLongitude
            "판교역", "", "경기 성남시 분당구 판교역로 160", "",
            RouteOption.RECOMMENDED, "", false,
        )
        log("navi requestRoute → $dest")
        runCatching { navi.requestRoute(dest) }
            .onFailure { log("navi requestRoute FAIL $it") }
        ui.removeCallbacks(tbtPoll)
        ui.postDelayed(tbtPoll, 2000)
    }

    fun cancelRoute() {
        runCatching { navi.cancelRoute() }.onFailure { log("navi cancelRoute FAIL $it") }
        log("navi cancelRoute called (tbt callbacks=$tbtCount)")
    }

    fun stop() {
        ui.removeCallbacks(tbtPoll)
        runCatching { navi.removeListener(listener) }
        runCatching { navi.release() }
    }
}
