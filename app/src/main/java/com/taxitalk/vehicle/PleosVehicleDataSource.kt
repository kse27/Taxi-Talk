package com.taxitalk.vehicle

import android.content.Context
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.EtcsVoiceGuidanceVolumeListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Pleos Vehicle SDK 2.3.2 기반 차량 데이터 소스 (FR-5.5 패턴):
 * initialize() → capability check → get(초기값) + register(변화 구독) → unregister → release()
 *
 * 사용 도메인:
 *  - Gear      : P ↔ D 전환으로 출발/도착(정차) 판단 → 하차 안내 트리거 (FR-5.3, FR-6)
 *  - Door      : 2열 좌/우 문 열림 → 하차 방향 안내·차도 쪽 문 경고 (FR-5.2)
 *  - Seat      : 2열 착석·안전벨트 → 승객 좌석 컨텍스트, 벨트 착용 안내 (FR-5.4)
 *  - Odometer  : 현재 트립 주행거리 → 운행 정보 재확인 (FR-6.3)
 *  - Toll      : 하이패스 음성 안내 볼륨 → 통역 중 하이패스 안내와 겹침 감지 (FR-5.1)
 *
 * 모든 콜백은 SDK 스레드에서 올 수 있으므로 StateFlow.update 로만 상태를 갱신한다.
 */
class PleosVehicleDataSource(context: Context) : VehicleDataSource {
    private val appContext = context.applicationContext
    private var vehicle: Vehicle? = null

    private val _state = MutableStateFlow(VehicleState(source = VehicleState.Source.PLEOS))
    override val state: StateFlow<VehicleState> = _state.asStateFlow()

    /** 하나 이상의 capability 확인 성공 시 true — 실제 차량 서비스 연결 판단에 사용. */
    private val _connected = MutableStateFlow<Boolean?>(null)
    val connected: StateFlow<Boolean?> = _connected.asStateFlow()

    private val rearLeftDoor = DoorArea(DoorValue.ROW_2_LEFT)
    private val rearRightDoor = DoorArea(DoorValue.ROW_2_RIGHT)
    private val rearLeftSeat = SeatArea(SeatValue.ROW_2_LEFT)
    private val rearRightSeat = SeatArea(SeatValue.ROW_2_RIGHT)

    private val onError: (Exception) -> Unit = { e ->
        Log.w(TAG, "Vehicle SDK error", e)
        _state.update { it.copy(lastError = e.message ?: e.javaClass.simpleName) }
    }

    private fun capability(domain: String, ok: Boolean) {
        _state.update { it.copy(capabilities = it.capabilities + (domain to ok)) }
        if (ok) _connected.value = true
    }

    // ---------- listeners ----------
    private val gearListener = object : GearSelectionListener {
        override fun onSelected(p0: VehicleGear?) { _state.update { it.copy(gear = p0.toGear()) } }
        override fun onFailed(p0: Exception) { onError(p0) }
    }
    private fun doorListener(left: Boolean) = object : DoorPositionListener {
        override fun onPositionUpdated(p0: Int?) {
            val open = (p0 ?: 0) > 0
            _state.update { if (left) it.copy(rearLeftDoorOpen = open) else it.copy(rearRightDoorOpen = open) }
        }
        override fun onFailed(p0: Exception) { onError(p0) }
    }
    private val leftDoorListener = doorListener(true)
    private val rightDoorListener = doorListener(false)

    private fun occupancyListener(left: Boolean) = object : SeatOccupancyListener {
        override fun onSeatOccupancyUpdated(p0: Boolean?) {
            val v = p0 == true
            _state.update { if (left) it.copy(rearLeftOccupied = v) else it.copy(rearRightOccupied = v) }
        }
        override fun onFailed(p0: Exception) { onError(p0) }
    }
    private val leftOccListener = occupancyListener(true)
    private val rightOccListener = occupancyListener(false)

    private fun beltListener(left: Boolean) = object : SeatBeltBuckledStateListener {
        override fun onSeatBeltBuckledStateUpdated(p0: Boolean?) {
            val v = p0 == true
            _state.update { if (left) it.copy(rearLeftBelted = v) else it.copy(rearRightBelted = v) }
        }
        override fun onFailed(p0: Exception) { onError(p0) }
    }
    private val leftBeltListener = beltListener(true)
    private val rightBeltListener = beltListener(false)

    private val tripListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(p0: Float?) { _state.update { it.copy(tripDistanceKm = p0) } }
        override fun onFailed(p0: Exception) { onError(p0) }
    }
    private val etcsListener = object : EtcsVoiceGuidanceVolumeListener {
        override fun onEtcsVoiceGuidanceVolumeUpdated(p0: Int?) { _state.update { it.copy(etcsVolume = p0) } }
        override fun onFailed(p0: Exception) { onError(p0) }
    }

    private var registered = false

    fun markDisconnectedIfUnknown() { _connected.compareAndSet(null, false) }

    override fun start() {
        if (vehicle != null) return
        val v = try {
            Vehicle(appContext).also { it.initialize() }
        } catch (t: Throwable) {
            Log.w(TAG, "Vehicle initialize failed", t)
            _connected.value = false
            _state.update { it.copy(lastError = t.message ?: "initialize failed") }
            return
        }
        vehicle = v
        runCatching { setup(v) }.onFailure {
            Log.w(TAG, "Vehicle setup failed", it)
            _state.update { s -> s.copy(lastError = it.message) }
        }
    }

    private fun setup(v: Vehicle) {
        // --- Gear (capability API 없음: 초기값 조회 성공으로 지원 판단)
        v.getGear().getCurrentGearSelectionState({ g ->
            capability("Gear", true)
            _state.update { it.copy(gear = g.toGear()) }
        }, { e -> capability("Gear", false); onError(e) })
        v.getGear().registerGearSelection(gearListener)

        // --- Door
        val door = v.getDoor()
        door.checkDoorPositionCapabilities({ areas: Map<DoorArea, *> ->
            capability("Door", areas.isNotEmpty())
            if (rearLeftDoor in areas) {
                door.getCurrentDoorPosition(rearLeftDoor, { p -> _state.update { it.copy(rearLeftDoorOpen = (p ?: 0) > 0) } }, onError)
                door.registerDoorPosition(rearLeftDoor, leftDoorListener)
            }
            if (rearRightDoor in areas) {
                door.getCurrentDoorPosition(rearRightDoor, { p -> _state.update { it.copy(rearRightDoorOpen = (p ?: 0) > 0) } }, onError)
                door.registerDoorPosition(rearRightDoor, rightDoorListener)
            }
        }, { e -> capability("Door", false); onError(e) })

        // --- Seat (착석 + 안전벨트)
        val seat = v.getSeat()
        seat.checkSeatOccupancyCapabilities({ areas: List<SeatArea> ->
            capability("Seat", areas.isNotEmpty())
            if (rearLeftSeat in areas) {
                seat.getCurrentSeatOccupancy(rearLeftSeat, { b -> _state.update { it.copy(rearLeftOccupied = b == true) } }, onError)
                seat.registerSeatOccupancy(rearLeftSeat, leftOccListener)
            }
            if (rearRightSeat in areas) {
                seat.getCurrentSeatOccupancy(rearRightSeat, { b -> _state.update { it.copy(rearRightOccupied = b == true) } }, onError)
                seat.registerSeatOccupancy(rearRightSeat, rightOccListener)
            }
        }, { e -> capability("Seat", false); onError(e) })
        seat.checkSeatBeltBuckledCapabilities({ areas: List<SeatArea> ->
            capability("SeatBelt", areas.isNotEmpty())
            if (rearLeftSeat in areas) {
                seat.getCurrentSeatBeltBuckledState(rearLeftSeat, { b -> _state.update { it.copy(rearLeftBelted = b == true) } }, onError)
                seat.registerSeatBeltBuckledState(rearLeftSeat, leftBeltListener)
            }
            if (rearRightSeat in areas) {
                seat.getCurrentSeatBeltBuckledState(rearRightSeat, { b -> _state.update { it.copy(rearRightBelted = b == true) } }, onError)
                seat.registerSeatBeltBuckledState(rearRightSeat, rightBeltListener)
            }
        }, { e -> capability("SeatBelt", false); onError(e) })

        // --- Odometer (현재 트립 거리)
        val odo = v.getOdometer()
        odo.getTripCurrentDrivingDistance({ d ->
            capability("Odometer", true)
            _state.update { it.copy(tripDistanceKm = d) }
        }, { e -> capability("Odometer", false); onError(e) })
        odo.registerTripCurrentDrivingDistance(tripListener)

        // --- Toll (하이패스 음성 안내 볼륨)
        val toll = v.getToll()
        toll.checkEtcsVoiceGuidanceVolumeCapabilities({ _, _ ->
            capability("Toll", true)
            toll.getCurrentEtcsVoiceGuidanceVolume({ vol -> _state.update { it.copy(etcsVolume = vol) } }, onError)
            toll.registerEtcsVoiceGuidanceVolume(etcsListener)
        }, { e -> capability("Toll", false); onError(e) })

        registered = true
    }

    override fun stop() {
        val v = vehicle ?: return
        if (registered) runCatching {
            v.getGear().unregisterGearSelection(gearListener)
            v.getDoor().unregisterDoorPosition(rearLeftDoor, leftDoorListener)
            v.getDoor().unregisterDoorPosition(rearRightDoor, rightDoorListener)
            v.getSeat().unregisterSeatOccupancy(rearLeftSeat, leftOccListener)
            v.getSeat().unregisterSeatOccupancy(rearRightSeat, rightOccListener)
            v.getSeat().unregisterSeatBeltBuckledState(rearLeftSeat, leftBeltListener)
            v.getSeat().unregisterSeatBeltBuckledState(rearRightSeat, rightBeltListener)
            v.getOdometer().unregisterTripCurrentDrivingDistance(tripListener)
            v.getToll().unregisterEtcsVoiceGuidanceVolume(etcsListener)
        }
        registered = false
        runCatching { v.release() }
        vehicle = null
    }

    private fun VehicleGear?.toGear(): VehicleState.Gear = when (this) {
        VehicleGear.GEAR_PARK -> VehicleState.Gear.PARK
        VehicleGear.GEAR_REVERSE -> VehicleState.Gear.REVERSE
        VehicleGear.GEAR_NEUTRAL -> VehicleState.Gear.NEUTRAL
        null, VehicleGear.GEAR_UNKNOWN -> VehicleState.Gear.UNKNOWN
        else -> VehicleState.Gear.DRIVE
    }

    companion object { private const val TAG = "TaxiTalkVehicle" }
}
