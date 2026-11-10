package com.taxitalk.vehicle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 차량 미연결(일반 Android) 환경용 모사 데이터 소스. 데모 패널에서 값을 바꾼다. */
class SimulatedVehicleDataSource(reason: String? = null) : VehicleDataSource {
    private val _state = MutableStateFlow(
        VehicleState(source = VehicleState.Source.SIMULATED, gear = VehicleState.Gear.PARK, lastError = reason)
    )
    override val state: StateFlow<VehicleState> = _state.asStateFlow()

    override fun start() {}
    override fun stop() {}

    fun update(block: (VehicleState) -> VehicleState) = _state.update(block)
}
