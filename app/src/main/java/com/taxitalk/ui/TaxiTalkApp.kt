package com.taxitalk.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taxitalk.model.Lang
import com.taxitalk.model.Speaker
import com.taxitalk.vehicle.VehicleState

/**
 * Driver-only Midnight Pearl shell.
 *
 * The screen selection and permission flow are intentionally kept in this one
 * place. P gear is the only state that opens the detailed map/demo surfaces;
 * D, R, N and unknown gear values fall back to the conservative RideScreen.
 */
@Composable
fun TaxiTalkApp(vm: TaxiTalkViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val vehicle by vm.vehicleState.collectAsStateWithLifecycle()
    val pleosConnected by vm.vehicle.pleosConnected.collectAsStateWithLifecycle()
    val forceSim by vm.vehicle.forceSimulation.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val ko = remember { vm.localized(Lang.KO) }
    val safePark = vehicle.gear == VehicleState.Gear.PARK

    // Screen microphone controls retain the established runtime-permission behaviour.
    val pendingSpeaker = remember { arrayOfNulls<Speaker>(1) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) when (pendingSpeaker[0]) {
            Speaker.DRIVER -> vm.driverTalk()
            Speaker.PASSENGER -> vm.passengerTalk()
            else -> Unit
        }
        pendingSpeaker[0] = null
    }
    val onMic: (Speaker) -> Unit = { speaker ->
        if (context.hasMic()) {
            if (speaker == Speaker.DRIVER) vm.driverTalk() else vm.passengerTalk()
        } else {
            pendingSpeaker[0] = speaker
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // The pre-ride permission request keeps physical-button operation available after a language choice.
    val pendingLang = remember { arrayOfNulls<Lang>(1) }
    val startLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        pendingLang[0]?.let(vm::startRide)
        pendingLang[0] = null
    }
    val onStart: (Lang) -> Unit = { language ->
        if (context.hasMic()) vm.startRide(language)
        else {
            pendingLang[0] = language
            startLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val cabinBackground = Brush.verticalGradient(
        colors = listOf(TT.MidnightNavy, TT.DriverBg, TT.InputSurface),
    )
    TaxiTalkTheme {
        Column(
            Modifier.fillMaxSize().background(cabinBackground).safeDrawingPadding(),
        ) {
            when {
                ui.inRide && ui.showMapConcept && safePark ->
                    MapConceptScreen(ui, vehicle, ko, vm, onMic, Modifier.weight(1f).fillMaxWidth())
                ui.inRide ->
                    RideScreen(ui, vehicle, ko, vm, onMic, Modifier.weight(1f).fillMaxWidth())
                else ->
                    StartScreen(ui.conversationDeleted, vehicle, ko, vm, onStart, Modifier.weight(1f).fillMaxWidth())
            }
            // Developer controls are retained, but never occupy the driver display outside P gear.
            // In simulation the gear is fake, so keep a minimal rail outside P to switch back.
            if (ui.showDemoPanel && safePark) {
                DemoPanel(ui, vehicle, pleosConnected, forceSim, vm)
            } else if (ui.showDemoPanel && vehicle.source == VehicleState.Source.SIMULATED) {
                SimGearRail(vehicle, vm)
            }
        }
    }
}

private fun Context.hasMic() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
