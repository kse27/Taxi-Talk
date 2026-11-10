package com.taxitalk.vehicle

import kotlinx.coroutines.flow.StateFlow

/** 앱이 사용하는 차량 상태 스냅샷 (FR-5). */
data class VehicleState(
    val source: Source = Source.SIMULATED,
    val gear: Gear = Gear.UNKNOWN,
    /** 뒷좌석 문 열림 (좌/우) */
    val rearLeftDoorOpen: Boolean = false,
    val rearRightDoorOpen: Boolean = false,
    /** 뒷좌석 착석 (좌/우) */
    val rearLeftOccupied: Boolean = false,
    val rearRightOccupied: Boolean = false,
    /** 뒷좌석 안전벨트 (좌/우) */
    val rearLeftBelted: Boolean = false,
    val rearRightBelted: Boolean = false,
    /** 현재 트립 주행거리(km) */
    val tripDistanceKm: Float? = null,
    /** 하이패스(ETCS) 음성 안내 볼륨 — Toll 도메인 */
    val etcsVolume: Int? = null,
    /** 도메인별 capability 지원 여부 (UI 표시용) */
    val capabilities: Map<String, Boolean> = emptyMap(),
    val lastError: String? = null,
) {
    enum class Source { PLEOS, SIMULATED }
    enum class Gear { UNKNOWN, PARK, REVERSE, NEUTRAL, DRIVE }

    val isDriving get() = gear == Gear.DRIVE || gear == Gear.REVERSE
    val passengerSeated get() = rearLeftOccupied || rearRightOccupied
    val passengerUnbelted get() = (rearLeftOccupied && !rearLeftBelted) || (rearRightOccupied && !rearRightBelted)
}

/** 차량 데이터 소스 추상화 — Pleos SDK 또는 소프트웨어 모사 (NFR-4.2). */
interface VehicleDataSource {
    val state: StateFlow<VehicleState>
    fun start()
    fun stop()
}

/**
 * 내비 이벤트(경로 변경·정체·ETA). NaviHelper SDK 연동 전까지 모사 패널에서 발생시킨다.
 */
sealed interface NaviEvent {
    data class EtaUpdate(val minutes: Int) : NaviEvent
    data class RouteChanged(val reason: Reason) : NaviEvent { enum class Reason { TRAFFIC, ACCIDENT, ROAD_WORK, OTHER } }
    /** 목적지까지 [km] 남음: 소지품 안내를 함께 한다 */
    data class Approaching(val km: Int) : NaviEvent
    /** 내비가 알려 준 도착. 손님 안내 없이 기사 화면에만 표시한다 */
    data class Arrived(val name: String?) : NaviEvent
}
