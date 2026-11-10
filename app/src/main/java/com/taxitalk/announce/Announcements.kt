package com.taxitalk.announce

import androidx.annotation.StringRes
import com.taxitalk.R
import com.taxitalk.vehicle.NaviEvent
import com.taxitalk.vehicle.VehicleState
import java.time.LocalTime

/** 차량 데이터 기반 사전 정의 안내 (FR-3). 문구는 언어별 strings 리소스 (NFR-5.2). */
enum class AnnouncementType(@StringRes val res: Int, val priority: Int) {
    WELCOME(R.string.ann_welcome, 1),
    RIDE_NOTICE(R.string.ann_ride_notice, 1),
    SEATBELT(R.string.ann_seatbelt, 3),
    NIGHT_SURCHARGE(R.string.ann_night_surcharge, 2),
    ETA(R.string.ann_eta, 1),
    ROUTE_TRAFFIC(R.string.ann_route_traffic, 2),
    ROUTE_ACCIDENT(R.string.ann_route_accident, 2),
    ROUTE_ROADWORK(R.string.ann_route_roadwork, 2),
    ROUTE_OTHER(R.string.ann_route_other, 2),
    APPROACHING(R.string.ann_approaching, 2),
    DOOR_TRAFFIC_SIDE(R.string.ann_door_traffic_side, 4),
}

data class Announcement(
    val type: AnnouncementType,
    val args: List<Any> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * 서울 택시 심야할증(2022.12~): 22–23시 20%, 23–02시 40%, 02–04시 20%.
 * Pleos Vehicle SDK의 Toll 도메인은 하이패스 음성 볼륨만 제공하므로 할증은 시각 기반으로 판단한다.
 */
object NightSurcharge {
    fun percentAt(time: LocalTime): Int {
        val h = time.hour
        return when {
            h == 22 -> 20
            h >= 23 || h < 2 -> 40
            h in 2..3 -> 20
            else -> 0
        }
    }
}

/**
 * 차량 상태 전이 → 안내 이벤트 규칙. 순수 함수로 두어 테스트/교체가 쉽다.
 */
object AnnouncementRules {

    fun onVehicleChange(prev: VehicleState, cur: VehicleState): List<Announcement> {
        val out = mutableListOf<Announcement>()
        // 출발 시 미착용 → 벨트 안내 (Seat + Gear)
        if (!prev.isDriving && cur.isDriving && cur.passengerUnbelted) out += Announcement(AnnouncementType.SEATBELT)
        // 주행 중 벨트 해제
        if (cur.isDriving && !prev.passengerUnbelted && cur.passengerUnbelted) out += Announcement(AnnouncementType.SEATBELT)
        // 차도 쪽(왼쪽) 뒷문 열림 → 오른쪽 문 이용 경고 (Door)
        if (!prev.rearLeftDoorOpen && cur.rearLeftDoorOpen) out += Announcement(AnnouncementType.DOOR_TRAFFIC_SIDE)
        return out
    }

    fun onNavi(e: NaviEvent): Announcement? = when (e) {
        is NaviEvent.EtaUpdate -> Announcement(AnnouncementType.ETA, listOf(e.minutes))
        is NaviEvent.RouteChanged -> Announcement(
            when (e.reason) {
                NaviEvent.RouteChanged.Reason.TRAFFIC -> AnnouncementType.ROUTE_TRAFFIC
                NaviEvent.RouteChanged.Reason.ACCIDENT -> AnnouncementType.ROUTE_ACCIDENT
                NaviEvent.RouteChanged.Reason.ROAD_WORK -> AnnouncementType.ROUTE_ROADWORK
                NaviEvent.RouteChanged.Reason.OTHER -> AnnouncementType.ROUTE_OTHER
            }
        )
        is NaviEvent.Approaching -> Announcement(AnnouncementType.APPROACHING, listOf(e.km))
        is NaviEvent.Arrived -> null
    }
}
