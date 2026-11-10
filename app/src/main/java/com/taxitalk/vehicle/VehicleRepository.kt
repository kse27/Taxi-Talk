package com.taxitalk.vehicle

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Pleos SDK 연결을 시도하고, 미연결/미지원 시 모사 소스로 자동 폴백 (NFR-4.2, SC-4).
 * 데모를 위해 Pleos 연결 중에도 [forceSimulation] 으로 모사 패널 값을 쓸 수 있다.
 */
class VehicleRepository(context: Context, scope: CoroutineScope) {
    private val pleos = PleosVehicleDataSource(context)
    val simulated = SimulatedVehicleDataSource()
    val forceSimulation = MutableStateFlow(false)

    val pleosConnected: StateFlow<Boolean?> = pleos.connected

    val state: StateFlow<VehicleState> =
        combine(pleos.state, simulated.state, pleos.connected, forceSimulation) { p, s, connected, force ->
            if (connected == true && !force) p
            else s.copy(capabilities = p.capabilities, lastError = s.lastError ?: p.lastError)
        }.stateIn(scope, SharingStarted.Eagerly, simulated.state.value)

    init {
        scope.launch {
            pleos.start()
            // 일정 시간 내 capability 응답이 없으면 차량 서비스 미연결로 판단
            delay(CONNECT_TIMEOUT_MS)
            pleos.markDisconnectedIfUnknown()
        }
    }

    fun release() = pleos.stop()

    companion object { private const val CONNECT_TIMEOUT_MS = 3_000L }
}
