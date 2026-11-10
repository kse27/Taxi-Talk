package com.taxitalk.ui

import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taxitalk.R
import com.taxitalk.model.Lang
import com.taxitalk.model.Speaker
import com.taxitalk.model.TurnState
import com.taxitalk.model.Utterance
import com.taxitalk.translate.PhrasebookTranslator
import com.taxitalk.vehicle.VehicleState

/** Midnight Pearl launch screen. Language selection remains the existing start-ride entry point. */
@Composable
fun StartScreen(
    conversationDeleted: Boolean,
    vehicle: VehicleState,
    ko: Context,
    vm: TaxiTalkViewModel,
    onStart: (Lang) -> Unit,
    modifier: Modifier = Modifier,
) {
    val parked = vehicle.gear == VehicleState.Gear.PARK
    Column(modifier.padding(horizontal = 28.dp, vertical = 22.dp).animateContentSize()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 640.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Text("TAXI·TALK", color = TT.PearlIvory, fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 1.8.sp)
                        Text("MIDNIGHT PEARL · PLEOS CONNECT", color = TT.Champagne, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    StartVehicleBadge(vehicle)
                    if (parked) {
                        Spacer(Modifier.width(8.dp))
                        Pill(ko.getString(R.string.drv_demo), TT.DriverCardRaised, TT.DriverText, onClick = vm::toggleDemoPanel)
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("TAXI·TALK", color = TT.PearlIvory, fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp)
                            Text("MIDNIGHT PEARL", color = TT.Champagne, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp)
                        }
                        StartVehicleBadge(vehicle)
                    }
                    if (parked) Pill(ko.getString(R.string.drv_demo), TT.DriverCardRaised, TT.DriverText, onClick = vm::toggleDemoPanel)
                }
            }
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= 880.dp
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (conversationDeleted) {
                    Row(
                        Modifier.background(TT.Ok.copy(alpha = 0.14f), RoundedCornerShape(18.dp))
                            .border(1.dp, TT.Ok.copy(alpha = 0.52f), RoundedCornerShape(18.dp))
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(8.dp).background(TT.Ok, CircleShape))
                        Spacer(Modifier.width(9.dp))
                        Text(ko.getString(R.string.drv_conversation_deleted), color = TT.Ok, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(24.dp))
                }
                Text("RIDE INTERPRET", color = TT.Champagne, fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 1.8.sp)
                Spacer(Modifier.height(10.dp))
                Text(ko.getString(R.string.drv_choose_lang), color = TT.DriverText, fontSize = if (wide) 40.sp else 32.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text(
                    ko.getString(R.string.drv_choose_lang_sub),
                    color = TT.DriverSub,
                    fontSize = 19.sp,
                    lineHeight = 28.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 680.dp),
                )
                Spacer(Modifier.height(if (wide) 42.dp else 28.dp))
                if (wide) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
                        Lang.passengerLanguages.forEach { language ->
                            LangCard(
                                language,
                                Modifier.width(238.dp).heightIn(min = 260.dp),
                                onClick = { onStart(language) },
                            )
                        }
                    }
                } else {
                    Column(
                        Modifier.fillMaxWidth().widthIn(max = 480.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Lang.passengerLanguages.forEach { language ->
                            LangCard(language, Modifier.fillMaxWidth(), onClick = { onStart(language) })
                        }
                    }
                }
                Spacer(Modifier.height(22.dp))
                Text(
                    if (parked) "언어를 선택하면 통역과 내비 안내를 시작합니다"
                    else "차량 상태를 확인하는 동안에도 언어를 선택할 수 있습니다",
                    color = TT.DriverSub,
                    fontSize = 15.sp,
                )
            }
        }
    }
}

@Composable
private fun StartVehicleBadge(vehicle: VehicleState) {
    val (label, color) = when (vehicle.source) {
        VehicleState.Source.PLEOS -> "VEHICLE CONNECTED" to TT.Ok
        VehicleState.Source.SIMULATED -> "SIMULATION MODE" to TT.Accent
    }
    Row(
        Modifier.background(TT.DriverCard, RoundedCornerShape(16.dp))
            .border(1.dp, TT.OutlineSoft, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, color = TT.DriverText, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp)
            Text("기어 ${vehicle.gear.label()}", color = TT.DriverSub, fontSize = 12.sp)
        }
    }
}

@Composable
private fun LangCard(language: Lang, modifier: Modifier, onClick: () -> Unit) {
    val accent = when (language) {
        Lang.EN -> TT.Accent
        Lang.JA -> TT.DustyRose
        Lang.ZH -> TT.Champagne
        Lang.KO -> TT.Ok
    }
    val brush = Brush.verticalGradient(listOf(TT.DriverCardRaised, TT.DriverCard))
    Column(
        modifier.pearlPanel(TT.DriverCard, accent.copy(alpha = 0.46f), RoundedCornerShape(28.dp), 4.dp)
            .background(brush, RoundedCornerShape(28.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(68.dp).background(accent.copy(alpha = 0.16f), CircleShape)
                .border(1.dp, accent.copy(alpha = 0.62f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(language.flag, fontSize = 34.sp)
        }
        Spacer(Modifier.height(16.dp))
        Text(language.koreanName(), color = TT.DriverText, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text(language.nativeName, color = TT.DriverSub, fontSize = 18.sp)
        Spacer(Modifier.height(18.dp))
        Text("통역 시작", color = accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Presentation-only map mode. It remains a clearly labelled demonstration map
 * and preserves the same voice controls and callbacks as the standard display.
 */
@Composable
fun MapConceptScreen(
    ui: UiState,
    vehicle: VehicleState,
    ko: Context,
    vm: TaxiTalkViewModel,
    onMic: (Speaker) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lang = ui.passengerLang ?: return
    var notice by remember { mutableStateOf(ui.driverNotice) }
    LaunchedEffect(ui.driverNotice) {
        notice = ui.driverNotice
        if (notice != null) {
            kotlinx.coroutines.delay(6_000)
            notice = null
        }
    }
    val last = ui.utterances.lastOrNull()
    var showLast by remember { mutableStateOf(false) }
    LaunchedEffect(last?.id) {
        showLast = last != null
        if (last != null) {
            kotlinx.coroutines.delay(UTTERANCE_VISIBLE_MS)
            showLast = false
        }
    }
    var showPhrases by remember { mutableStateOf(false) }

    Box(modifier) {
        DemoMap(driving = vehicle.isDriving, modifier = Modifier.fillMaxSize())
        Column(
            Modifier.align(Alignment.TopStart).fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().pearlPanel(TT.DriverBg.copy(alpha = 0.94f), TT.Outline, RoundedCornerShape(22.dp), 4.dp)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("TAXI·TALK", color = TT.PearlIvory, fontSize = 19.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                        Text("DEMO NAVIGATION VIEW · P GEAR", color = TT.Champagne, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
                    }
                    Pill(ko.getString(R.string.drv_demo), TT.DriverCardRaised, TT.DriverText, onClick = vm::toggleDemoPanel)
                    Spacer(Modifier.width(8.dp))
                    ConfirmEndRidePill(vm::endRide)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Lang.passengerLanguages.forEach { candidate ->
                        Pill(
                            "${candidate.flag} ${candidate.koreanName()}",
                            if (candidate == lang) TT.Accent else TT.DriverCard,
                            fg = if (candidate == lang) TT.MidnightNavy else TT.DriverText,
                            onClick = { vm.changeLanguage(candidate) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Pill(
                        ko.getString(R.string.quick_phrases),
                        if (showPhrases) TT.Champagne else TT.DriverCard,
                        fg = if (showPhrases) TT.MidnightNavy else TT.DriverText,
                        onClick = { showPhrases = !showPhrases },
                    )
                }
            }
            notice?.let {
                NoticeBanner(ko.getString(it.type.res, *it.args.toTypedArray()), dark = true, prefix = ko.getString(R.string.drv_notice_prefix))
            }
            ui.error?.let {
                Box(Modifier.background(TT.DriverBg.copy(alpha = 0.94f), RoundedCornerShape(18.dp))) { ErrorBanner(ko.getString(it.res)) }
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ui.partialText?.let {
                Text(
                    "듣는 중  ·  $it",
                    color = TT.VoiceActive,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.background(TT.DriverBg.copy(alpha = 0.94f), RoundedCornerShape(14.dp)).padding(horizontal = 15.dp, vertical = 9.dp),
                )
            }
            if (showLast && last != null) UtteranceCard(last, ko)
            if (showPhrases) {
                Column(
                    Modifier.fillMaxWidth().pearlPanel(TT.DriverBg.copy(alpha = 0.96f), TT.Outline, RoundedCornerShape(20.dp), 4.dp)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PhraseRow(PhrasebookTranslator.driverPhrases.map { it.ko }, dark = true) {
                        vm.submitText(Speaker.DRIVER, it)
                    }
                    TextInputRow(ko.getString(R.string.type_hint), dark = true) {
                        vm.submitText(Speaker.DRIVER, it)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TalkButton(
                    state = ui.driverTurn,
                    label = ko.getString(R.string.drv_talk_me),
                    activeLabel = ko.getString(ui.driverTurn.labelRes()),
                    direction = ko.getString(R.string.drv_direction_fmt, Lang.KO.koreanName(), lang.koreanName()),
                    color = TT.Champagne,
                    contentColor = TT.MidnightNavy,
                    onClick = { onMic(Speaker.DRIVER) },
                    level = ui.micLevel,
                    modifier = Modifier.weight(1f).height(118.dp),
                )
                TalkButton(
                    state = ui.passengerTurn,
                    label = ko.getString(R.string.drv_talk_passenger),
                    activeLabel = ko.getString(ui.passengerTurn.labelRes()),
                    direction = ko.getString(R.string.drv_direction_fmt, lang.koreanName(), Lang.KO.koreanName()),
                    color = TT.Accent,
                    contentColor = TT.MidnightNavy,
                    onClick = { onMic(Speaker.PASSENGER) },
                    level = ui.micLevel,
                    modifier = Modifier.weight(1f).height(118.dp),
                )
            }
        }
    }
}

private const val UTTERANCE_VISIBLE_MS = 12_000L

/** Driver-facing original/translation card retained for the optional demo map. */
@Composable
private fun UtteranceCard(utterance: Utterance, ko: Context) {
    val fromPassenger = utterance.speaker == Speaker.PASSENGER
    val main = if (fromPassenger) utterance.translated ?: utterance.original else utterance.original
    val sub = if (fromPassenger) "${utterance.sourceLang.flag} ${utterance.original}"
    else utterance.translated?.let { "${utterance.targetLang.flag} $it" }
    val accent = if (fromPassenger) TT.Accent else TT.Champagne
    Column(
        Modifier.fillMaxWidth().pearlPanel(TT.DriverBg.copy(alpha = 0.96f), TT.Outline, RoundedCornerShape(20.dp), 4.dp)
            .padding(16.dp),
    ) {
        Text(ko.getString(if (fromPassenger) R.string.drv_passenger else R.string.drv_me), color = accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(main, color = TT.DriverText, fontSize = 29.sp, lineHeight = 37.sp, fontWeight = FontWeight.Bold)
        sub?.let { Text(it, color = TT.DriverSub, fontSize = 18.sp) }
    }
}

fun VehicleState.Gear.label() = when (this) {
    VehicleState.Gear.PARK -> "P"
    VehicleState.Gear.DRIVE -> "D"
    VehicleState.Gear.REVERSE -> "R"
    VehicleState.Gear.NEUTRAL -> "N"
    VehicleState.Gear.UNKNOWN -> "-"
}

fun Lang.koreanName() = when (this) {
    Lang.KO -> "한국어"
    Lang.EN -> "영어"
    Lang.JA -> "일본어"
    Lang.ZH -> "중국어"
}

fun TurnState.labelRes() = when (this) {
    TurnState.LISTENING -> R.string.listening
    TurnState.RECOGNIZING, TurnState.TRANSLATING -> R.string.translating
    TurnState.SPEAKING -> R.string.speaking_out
    else -> R.string.speak
}
