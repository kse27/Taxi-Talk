package com.taxitalk.vehicle

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * 기본 내비의 경로 정보를 읽어 [NaviEvent]로 바꾼다. 내비 화면은 그대로 쓰고 정보만 받는다.
 * 남은 거리는 콜백이 오지 않을 수 있어 운행 중 주기적으로 조회한다.
 */
class PleosNaviSource(
    context: Context,
    private val scope: CoroutineScope,
    private val onEvent: (NaviEvent) -> Unit,
) {
    private val helper = runCatching { NaviHelper(context.applicationContext) }.getOrNull()
    private var pollJob: Job? = null
    private var etaAnnounced = false
    private var approachAnnounced = false
    @Volatile private var location: CurrentLocationInfo? = null

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(p0: RouteStartInfo) {
            Log.i(TAG, "route started: $p0")
            newRoute()
            emitEta(p0.duration)
        }
        override fun onRoutingCompleted() { Log.i(TAG, "routing completed") }
        override fun onReRouteEnded() {
            Log.i(TAG, "reroute ended")
            emit(NaviEvent.RouteChanged(NaviEvent.RouteChanged.Reason.OTHER))
        }
        override fun onDestinationChanged() { Log.i(TAG, "destination changed"); newRoute() }
        // 새 경로를 시작할 때 이전 경로의 종료가 바로 뒤따라 오므로 여기서는 초기화하지 않는다
        override fun onRouteEnded() { Log.i(TAG, "route ended") }
        override fun onRouteCancelled() { Log.i(TAG, "route cancelled"); newRoute() }
        override fun onDestinationArrived(p0: DestinationArrivedInfo) {
            Log.i(TAG, "arrived: $p0")
            emit(NaviEvent.Arrived(p0.name))
        }
        override fun onDestinationInfo(p0: DestinationInfo) {
            Log.i(TAG, "destination: ${p0.name} remain=${p0.remainDistance} time=${p0.remainTime}")
            onRemaining(p0.remainDistance, p0.remainTime)
        }
        override fun onDrivingInfo(p0: DrivingInfo) {
            val d = p0.destination
            Log.i(TAG, "driving: ${d.name} distance=${d.distance} duration=${d.duration}")
            onRemaining(d.distance, d.duration)
        }
        override fun onCurrentLocationInfo(p0: CurrentLocationInfo) { location = p0 }
        override fun onNavigationStatus(p0: NavigationStatus) { Log.i(TAG, "status: $p0") }
        override fun onError(p0: NaviErrorCode) { Log.w(TAG, "error: $p0") }
    }

    init {
        runCatching {
            helper?.initialize(
                { Log.i(TAG, "init OK"); runCatching { helper?.addListener(listener) } },
                { e -> Log.w(TAG, "init failed", e) },
            )
        }.onFailure { Log.w(TAG, "NaviHelper unavailable", it) }
    }

    /** 운행이 시작되면 안내 상태를 초기화하고 남은 거리 조회를 시작한다. */
    fun start() {
        newRoute()
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                runCatching { helper?.getDestinationInfo(); helper?.getCurrentLocationInfo() }
                    .onFailure { Log.w(TAG, "poll failed", it) }
                delay(POLL_MS)
            }
        }
    }

    fun stop() { pollJob?.cancel(); pollJob = null }

    /** 시연용: 내비에 목적지를 설정한다. */
    fun requestRoute(name: String, latitude: Double, longitude: Double) {
        Log.i(TAG, "request route: $name")
        runCatching { helper?.requestRoute(RouteInfo(latitude = latitude, longitude = longitude, poiName = name, poiId = "", address = "", poiSubId = "")) }
            .onFailure { Log.w(TAG, "request route failed", it) }
    }

    /** 시연용: 현재 위치 근처로 목적지를 설정해, 차가 움직이지 않아도 남은 거리 안내를 보여 준다. */
    fun requestNearbyRoute(): Boolean {
        val here = location ?: return false
        requestRoute("근처 목적지", here.latitude + NEARBY_OFFSET, here.longitude)
        return true
    }

    /** 시연용: 경로 재탐색을 요청한다. */
    fun requestReRoute() {
        runCatching { helper?.requestReRoute() }.onFailure { Log.w(TAG, "reroute failed", it) }
    }

    fun release() {
        stop()
        runCatching { helper?.removeListener(listener); helper?.release() }
    }

    private fun newRoute() { etaAnnounced = false; approachAnnounced = false }

    /** 경로가 없으면 남은 거리가 0으로 오므로 무시한다. */
    private fun onRemaining(distanceM: Int, durationSec: Int) {
        if (distanceM <= 0) return
        emitEta(durationSec)
        if (!approachAnnounced && distanceM <= APPROACH_M) {
            approachAnnounced = true
            emit(NaviEvent.Approaching(ceil(distanceM / 1000.0).toInt()))
        }
    }

    private fun emitEta(durationSec: Int) {
        if (etaAnnounced || durationSec <= 0) return
        etaAnnounced = true
        emit(NaviEvent.EtaUpdate(ceil(durationSec / 60.0).toInt()))
    }

    private fun emit(e: NaviEvent) { scope.launch(Dispatchers.Main) { onEvent(e) } }

    private companion object {
        const val TAG = "TaxiTalkNavi"
        const val POLL_MS = 10_000L
        const val APPROACH_M = 2_000
        const val NEARBY_OFFSET = 0.002 // 위도 약 220m
    }
}
