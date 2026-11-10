package com.taxitalk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taxitalk.model.Speaker
import com.taxitalk.translate.PhrasebookTranslator
import com.taxitalk.vehicle.NaviEvent
import com.taxitalk.vehicle.VehicleState

/**
 * Park-only developer rail. Every existing simulator and navigation action stays
 * available here, while TaxiTalkApp keeps it off the driving display.
 */
@Composable
fun DemoPanel(ui: UiState, v: VehicleState, pleosConnected: Boolean?, forceSim: Boolean, vm: TaxiTalkViewModel) {
    val simulationActive = v.source == VehicleState.Source.SIMULATED
    Column(
        Modifier.fillMaxWidth().pearlPanel(TT.DemoBg, TT.Outline, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp), 8.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text("DEVELOPER / SIMULATION", color = TT.Champagne, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                val connection = when (pleosConnected) {
                    true -> "Pleos SDK 연결됨 · 주차 상태에서만 시연 제어 가능"
                    false -> "Pleos 미연결 · 시뮬레이션 상태 사용 중"
                    null -> "Pleos 연결 상태 확인 중…"
                }
                Text(connection, color = TT.DriverText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
            Pill("PARK ONLY", TT.DriverCardRaised, TT.Champagne)
        }
        val capabilities = v.capabilities.entries.joinToString("  ") { "${it.key}${if (it.value) " ✓" else " ✕"}" }
        if (capabilities.isNotEmpty() || v.lastError != null) {
            Text(
                listOfNotNull(capabilities.takeIf { it.isNotEmpty() }, v.lastError?.let { "SDK: $it" }).joinToString("   ·   "),
                color = if (v.lastError == null) TT.DriverSub else TT.DustyRose,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pleosConnected == true) {
                Toggle("모사 모드 강제", forceSim) { vm.vehicle.forceSimulation.value = !forceSim }
            }
            if (simulationActive) {
                Toggle("뒷좌석 착석", v.rearRightOccupied) {
                    vm.simulate { it.copy(rearRightOccupied = !it.rearRightOccupied) }
                }
                Toggle("안전벨트", v.rearRightBelted) {
                    vm.simulate { it.copy(rearRightBelted = !it.rearRightBelted) }
                }
                Toggle("기어 D(주행)", v.gear == VehicleState.Gear.DRIVE) {
                    vm.simulate {
                        val drive = it.gear != VehicleState.Gear.DRIVE
                        it.copy(
                            gear = if (drive) VehicleState.Gear.DRIVE else VehicleState.Gear.PARK,
                            tripDistanceKm = if (drive) (it.tripDistanceKm ?: 0f) + 3.2f else it.tripDistanceKm,
                        )
                    }
                }
                Toggle("왼쪽 뒷문(차도)", v.rearLeftDoorOpen) {
                    vm.simulate { it.copy(rearLeftDoorOpen = !it.rearLeftDoorOpen) }
                }
                Toggle("오른쪽 뒷문", v.rearRightDoorOpen) {
                    vm.simulate { it.copy(rearRightDoorOpen = !it.rearRightDoorOpen) }
                }
            }
            Toggle("지도 콘셉트", ui.showMapConcept) { vm.toggleMapConcept() }
            Toggle("심야 시간", ui.simulateNight) { vm.setSimulateNight(!ui.simulateNight) }
            ui.passengerLang?.let { language ->
                Action("뒷좌석 버튼") { vm.passengerTalk() }
                Action("기사 인식 실패") { vm.simulateRecognitionFailure(Speaker.DRIVER) }
                Action("손님 인식 실패") { vm.simulateRecognitionFailure(Speaker.PASSENGER) }
                PhrasebookTranslator.passengerPhrases.take(3).forEach { phrase ->
                    Action("손님: ${phrase.ko}") { vm.submitText(Speaker.PASSENGER, phrase.of(language)) }
                }
            }
            Action("내비 목적지: 서울역") { vm.requestDemoRoute() }
            Action("내비 목적지: 근처") { vm.requestNearbyDemoRoute() }
            Action("내비 재탐색") { vm.requestDemoReRoute() }
            Action("내비 도착") { vm.naviEvent(NaviEvent.Arrived("서울역")) }
            Action("ETA 12분") { vm.naviEvent(NaviEvent.EtaUpdate(12)) }
            Action("정체 우회") { vm.naviEvent(NaviEvent.RouteChanged(NaviEvent.RouteChanged.Reason.TRAFFIC)) }
            Action("사고 우회") { vm.naviEvent(NaviEvent.RouteChanged(NaviEvent.RouteChanged.Reason.ACCIDENT)) }
            Action("목적지 1km 전") { vm.naviEvent(NaviEvent.Approaching(1)) }
        }
    }
}

/** Simulation-only rail shown outside P so the demo can return to park. */
@Composable
fun SimGearRail(v: VehicleState, vm: TaxiTalkViewModel) {
    Row(
        Modifier.fillMaxWidth().pearlPanel(TT.DemoBg, TT.Outline, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp), 8.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("SIMULATION", color = TT.Champagne, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        Toggle("기어 D(주행)", v.gear == VehicleState.Gear.DRIVE) {
            vm.simulate { it.copy(gear = if (it.gear == VehicleState.Gear.DRIVE) VehicleState.Gear.PARK else VehicleState.Gear.DRIVE) }
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onClick: () -> Unit) = Pill(
    text = (if (on) "● " else "○ ") + label,
    bg = if (on) TT.Ok.copy(alpha = 0.24f) else TT.DriverCardRaised,
    fg = if (on) TT.Ok else TT.DriverText,
    onClick = onClick,
)

@Composable
private fun Action(label: String, onClick: () -> Unit) = Pill(
    text = "▶ $label",
    bg = TT.DriverCardSoft,
    fg = TT.Champagne,
    onClick = onClick,
)
