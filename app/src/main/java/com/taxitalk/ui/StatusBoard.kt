package com.taxitalk.ui

import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taxitalk.R
import com.taxitalk.announce.Announcement
import com.taxitalk.announce.AnnouncementType
import com.taxitalk.model.Lang
import com.taxitalk.model.Speaker
import com.taxitalk.model.TurnState
import com.taxitalk.model.Utterance
import com.taxitalk.translate.PhrasebookTranslator
import com.taxitalk.vehicle.VehicleState
import java.util.Locale

/** 차도 쪽 하차처럼 놓치면 안 되는 안내. 기록이 아니라 화면에 크게 띄운다. */
private val CriticalTypes = setOf(AnnouncementType.DOOR_TRAFFIC_SIDE)

/** P에서만 상세 조작을 노출한다. N/UNKNOWN은 보수적으로 제한 화면을 유지한다. */
private enum class DriverUiMode(val shortLabel: String, val label: String) {
    PARK("P", "주차 · 상세 보기"),
    DRIVE("D / R", "주행 · 집중 보기"),
    LIMITED("N / ?", "안전 · 제한 보기"),
}

private fun VehicleState.driverUiMode(): DriverUiMode = when (gear) {
    VehicleState.Gear.PARK -> DriverUiMode.PARK
    VehicleState.Gear.DRIVE, VehicleState.Gear.REVERSE -> DriverUiMode.DRIVE
    VehicleState.Gear.NEUTRAL, VehicleState.Gear.UNKNOWN -> DriverUiMode.LIMITED
}

/**
 * Midnight Pearl driver display.
 *
 * The underlying ViewModel events and vehicle signals remain unchanged. Only P
 * unlocks dense information and secondary touch controls; all other gear states
 * use the focused, conservative presentation.
 */
@Composable
fun RideScreen(
    ui: UiState,
    vehicle: VehicleState,
    ko: Context,
    vm: TaxiTalkViewModel,
    onMic: (Speaker) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lang = ui.passengerLang ?: return
    val mode = vehicle.driverUiMode()
    val parkMode = mode == DriverUiMode.PARK
    var notice by remember { mutableStateOf(ui.driverNotice) }
    LaunchedEffect(ui.driverNotice) {
        notice = ui.driverNotice
        if (notice != null) {
            kotlinx.coroutines.delay(if (notice?.type in CriticalTypes) 10_000 else 6_000)
            notice = null
        }
    }

    Column(
        modifier.padding(horizontal = 24.dp, vertical = 18.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        DriverHeader(ui, vehicle, lang, ko, vm, mode)

        notice?.let { announcement ->
            val text = ko.getString(announcement.type.res, *announcement.args.toTypedArray())
            if (announcement.type in CriticalTypes) CriticalAlert(text)
            else if (!parkMode) NoticeBanner(text, dark = true, prefix = ko.getString(R.string.drv_notice_prefix))
        }
        ui.arrivedAt?.let { place ->
            NoticeBanner(
                if (place.isBlank()) ko.getString(R.string.drv_arrived) else ko.getString(R.string.drv_arrived_fmt, place),
                dark = true,
                prefix = ko.getString(R.string.drv_navi_prefix),
            )
        }
        // Phrase selection already explains the failure. In a limited mode the prompt is hidden,
        // so retain the error banner as the driver's only immediate feedback.
        if (!ui.phrasePrompt || !parkMode) ui.error?.let { ErrorBanner(ko.getString(it.res)) }

        if (parkMode) {
            ParkDashboard(ui, vehicle, ko, vm, Modifier.weight(1f).fillMaxWidth())
        } else {
            FocusDashboard(ui, vehicle, ko, mode, Modifier.weight(1f).fillMaxWidth())
        }

        VoiceControlDeck(ui, lang, ko, onMic, prominent = !parkMode)

        // Text and quick phrases stay available only while the vehicle is safely in P.
        if (parkMode && !ui.phrasePrompt) {
            SectionLabel("빠른 발화", "음성 인식이 어려울 때도 같은 통역 경로로 전달됩니다")
            PhraseRow(PhrasebookTranslator.driverPhrases.map { it.ko }, dark = true) {
                vm.submitText(Speaker.DRIVER, it)
            }
            TextInputRow(ko.getString(R.string.type_hint), dark = true) {
                vm.submitText(Speaker.DRIVER, it)
            }
        }
    }
}

/** Premium header with immutable status indicators and P-only configuration controls. */
@Composable
private fun DriverHeader(
    ui: UiState,
    vehicle: VehicleState,
    lang: Lang,
    ko: Context,
    vm: TaxiTalkViewModel,
    mode: DriverUiMode,
) {
    Column(
        Modifier.fillMaxWidth().pearlPanel(TT.DriverCardSoft, TT.Outline, RoundedCornerShape(28.dp), 4.dp)
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 760.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Text("TAXI·TALK", color = TT.PearlIvory, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp)
                        Text("PLEOS CONNECT  ·  INTERPRET", color = TT.DriverSub, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    ModeBadge(mode)
                    Spacer(Modifier.width(8.dp))
                    ConnectionBadge(vehicle)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("TAXI·TALK", color = TT.PearlIvory, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp)
                            Text("PLEOS CONNECT  ·  INTERPRET", color = TT.DriverSub, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
                        }
                        ModeBadge(mode)
                    }
                    ConnectionBadge(vehicle)
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TurnStatus(ui, ko, Modifier.weight(1f))
            AudioReadiness(ui)
        }

        if (mode == DriverUiMode.PARK) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Lang.passengerLanguages.forEach { candidate ->
                    Pill(
                        "${candidate.flag} ${candidate.koreanName()}",
                        if (candidate == lang) TT.Accent else TT.DriverCard,
                        fg = if (candidate == lang) TT.MidnightNavy else TT.DriverText,
                        onClick = { vm.changeLanguage(candidate) },
                    )
                }
                ModelStatusPill(ui.modelStatus, ko)
                Pill(ko.getString(R.string.drv_demo), TT.DriverCardRaised, TT.DriverText, onClick = vm::toggleDemoPanel)
                ConfirmEndRidePill(vm::endRide)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (mode == DriverUiMode.DRIVE) "주행 중에는 음성 통역과 안전 안내만 표시합니다"
                    else "기어 상태 확인 전에는 제한 화면을 유지합니다",
                    color = TT.DriverSub,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.weight(1f))
                ModelStatusPill(ui.modelStatus, ko)
            }
        }
    }
}

@Composable
private fun ModeBadge(mode: DriverUiMode) {
    val color = when (mode) {
        DriverUiMode.PARK -> TT.Ok
        DriverUiMode.DRIVE -> TT.Warning
        DriverUiMode.LIMITED -> TT.Warning
    }
    Column(
        Modifier.background(color.copy(alpha = 0.16f), RoundedCornerShape(15.dp))
            .border(1.dp, color.copy(alpha = 0.54f), RoundedCornerShape(15.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text(mode.shortLabel, color = color, fontSize = 13.sp, fontWeight = FontWeight.Black)
        Text(mode.label, color = TT.DriverText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ConnectionBadge(vehicle: VehicleState) {
    val (label, color) = when {
        vehicle.source == VehicleState.Source.PLEOS -> "VEHICLE · CONNECTED" to TT.Ok
        vehicle.lastError != null -> "VEHICLE · SIMULATION" to TT.Warning
        else -> "VEHICLE · SIMULATION" to TT.Accent
    }
    Row(
        Modifier.background(TT.DriverBg, RoundedCornerShape(15.dp))
            .border(1.dp, TT.OutlineSoft, RoundedCornerShape(15.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.width(7.dp))
        Text(label, color = TT.DriverText, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp)
    }
}

@Composable
private fun AudioReadiness(ui: UiState) {
    val ready = ui.sttAvailable && ui.ttsAvailable
    Pill(
        text = if (ready) "음성 준비" else "음성 확인 중",
        bg = if (ready) TT.Ok.copy(alpha = 0.20f) else TT.DriverCard,
        fg = if (ready) TT.Ok else TT.DriverSub,
    )
}

@Composable
private fun ModelStatusPill(status: ModelStatus, ko: Context) {
    when (status) {
        ModelStatus.DOWNLOADING -> Pill(ko.getString(R.string.model_downloading), TT.DriverCardRaised, TT.DriverText)
        ModelStatus.READY -> Pill(ko.getString(R.string.model_ready), TT.Ok.copy(alpha = 0.22f), TT.Ok)
        ModelStatus.FALLBACK -> Pill(ko.getString(R.string.model_fallback), TT.Warning.copy(alpha = 0.22f), TT.Warning)
        ModelStatus.UNKNOWN -> Pill("통역 준비 확인 중", TT.DriverCard, TT.DriverSub)
    }
}

/** Shows the active speech state without turning it into a high-glare alert. */
@Composable
private fun TurnStatus(ui: UiState, ko: Context, modifier: Modifier = Modifier) {
    val turns = listOf(ui.driverTurn, ui.passengerTurn)
    val (res, color) = when {
        ui.driverTurn == TurnState.LISTENING -> R.string.drv_listening_me to TT.VoiceActive
        ui.passengerTurn == TurnState.LISTENING -> R.string.drv_listening_passenger to TT.VoiceActive
        TurnState.RECOGNIZING in turns || TurnState.TRANSLATING in turns -> R.string.translating to TT.Processing
        TurnState.SPEAKING in turns -> R.string.speaking_out to TT.Speaking
        TurnState.ERROR in turns -> R.string.err_no_match to TT.Danger
        else -> R.string.drv_status_idle to TT.DriverSub
    }
    val active = ui.driverTurn != TurnState.IDLE || ui.passengerTurn != TurnState.IDLE
    val transition = rememberInfiniteTransition(label = "turnStatus")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (active) 1.18f else 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "statusPulse",
    )
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(11.dp).graphicsLayer(scaleX = scale, scaleY = scale)
                .background(color.copy(alpha = 0.22f), CircleShape).padding(2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.fillMaxSize().background(color, CircleShape).animateContentSize())
        }
        Spacer(Modifier.width(9.dp))
        Column {
            Text("LIVE INTERPRET", color = TT.DriverSub, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp)
            Text(ko.getString(res), color = TT.DriverText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CriticalAlert(text: String) {
    Row(
        Modifier.fillMaxWidth().background(TT.Danger, RoundedCornerShape(20.dp))
            .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).background(Color.White, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column {
            Text("SAFETY ALERT", color = Color.White.copy(alpha = 0.78f), fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp)
            Text(text, color = Color.White, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ParkDashboard(
    ui: UiState,
    vehicle: VehicleState,
    ko: Context,
    vm: TaxiTalkViewModel,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        if (maxWidth >= 1_040.dp) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val mainArea = Modifier.weight(1.45f).fillMaxHeight()
                if (ui.phrasePrompt) PhrasePrompt(ko, vm, mainArea) else ConversationCard(ui, ko, large = false, modifier = mainArea)
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    VehicleCard(vehicle, ko, Modifier.weight(1.15f).fillMaxWidth())
                    RouteSnapshotCard(ui, vehicle, ko, Modifier.fillMaxWidth())
                    AnnouncementLog(ui.announceLog, ko, Modifier.weight(0.95f).fillMaxWidth())
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (ui.phrasePrompt) PhrasePrompt(ko, vm, Modifier.fillMaxWidth().heightIn(min = 300.dp))
                else ConversationCard(ui, ko, large = false, modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp))
                VehicleCard(vehicle, ko, Modifier.fillMaxWidth().heightIn(min = 260.dp))
                RouteSnapshotCard(ui, vehicle, ko, Modifier.fillMaxWidth())
                AnnouncementLog(ui.announceLog, ko, Modifier.fillMaxWidth().heightIn(min = 210.dp))
            }
        }
    }
}

@Composable
private fun FocusDashboard(
    ui: UiState,
    vehicle: VehicleState,
    ko: Context,
    mode: DriverUiMode,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth().pearlPanel(TT.DriverCardSoft, TT.Outline, RoundedCornerShape(22.dp))
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("FOCUS DRIVE", color = TT.Champagne, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                Text(
                    if (mode == DriverUiMode.DRIVE) "통역과 안전 정보만 빠르게 확인하세요"
                    else "기어 상태를 확인할 때까지 조작을 제한합니다",
                    color = TT.DriverSub,
                    fontSize = 16.sp,
                )
            }
            Pill("기어 ${vehicle.gear.label()}", TT.DriverBg, TT.DriverText)
        }
        ConversationCard(ui, ko, large = true, modifier = Modifier.weight(1f).fillMaxWidth())
        LatestRouteStrip(ui, ko)
    }
}

@Composable
private fun LatestRouteStrip(ui: UiState, ko: Context) {
    val routeMessage = ui.announceLog.lastOrNull {
        it.type == AnnouncementType.ETA || it.type == AnnouncementType.ROUTE_TRAFFIC ||
            it.type == AnnouncementType.ROUTE_ACCIDENT || it.type == AnnouncementType.ROUTE_ROADWORK ||
            it.type == AnnouncementType.ROUTE_OTHER || it.type == AnnouncementType.APPROACHING
    }
    if (routeMessage != null) {
        NoticeBanner(
            text = ko.getString(routeMessage.type.res, *routeMessage.args.toTypedArray()),
            dark = true,
            prefix = ko.getString(R.string.drv_navi_prefix),
        )
    }
}

@Composable
private fun VoiceControlDeck(
    ui: UiState,
    lang: Lang,
    ko: Context,
    onMic: (Speaker) -> Unit,
    prominent: Boolean,
) {
    SectionLabel(
        if (prominent) "음성 통역" else "양방향 통역",
        if (prominent) "주행 중에는 큰 음성 제어를 우선합니다" else "화면 또는 물리 버튼으로 같은 통역 기능을 사용할 수 있습니다",
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val height = if (prominent) 150.dp else 122.dp
        TalkButton(
            state = ui.driverTurn,
            label = ko.getString(R.string.drv_talk_me),
            activeLabel = ko.getString(ui.driverTurn.labelRes()),
            direction = ko.getString(R.string.drv_direction_fmt, Lang.KO.koreanName(), lang.koreanName()),
            color = TT.Champagne,
            contentColor = TT.MidnightNavy,
            onClick = { onMic(Speaker.DRIVER) },
            level = ui.micLevel,
            modifier = Modifier.weight(1f).height(height),
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
            modifier = Modifier.weight(1f).height(height),
        )
    }
}

@Composable
private fun SectionLabel(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title.uppercase(Locale.KOREA), color = TT.Champagne, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        Text(subtitle, color = TT.DriverSub, fontSize = 14.sp)
    }
}

/** 기사 말을 알아듣지 못했을 때(주차 중): 전할 문구를 눌러 손님에게 음성으로 내보낸다. */
@Composable
private fun PhrasePrompt(ko: Context, vm: TaxiTalkViewModel, modifier: Modifier) {
    Column(
        modifier.pearlPanel(TT.DriverCard, TT.Warning.copy(alpha = 0.55f), RoundedCornerShape(26.dp), 3.dp)
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("FALLBACK PHRASES", color = TT.Warning, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                Text(ko.getString(R.string.drv_phrase_prompt), color = TT.DriverText, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            }
            Pill(ko.getString(R.string.drv_phrase_close), TT.DriverBg, TT.DriverText, onClick = vm::dismissPhrasePrompt)
        }
        PhrasebookTranslator.driverPhrases.chunked(2).forEach { row ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { phrase ->
                    Box(
                        Modifier.weight(1f).fillMaxHeight().pearlPanel(TT.InputSurface, TT.Outline, RoundedCornerShape(18.dp))
                            .clickable { vm.submitText(Speaker.DRIVER, phrase.ko) }.padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(phrase.ko, color = TT.DriverText, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    }
                }
            }
        }
    }
}

/** Last passenger and driver utterance. Original text remains available for place-name confirmation. */
@Composable
private fun ConversationCard(ui: UiState, ko: Context, large: Boolean, modifier: Modifier) {
    val passenger = ui.utterances.lastOrNull { it.speaker == Speaker.PASSENGER }
    val driver = ui.utterances.lastOrNull { it.speaker == Speaker.DRIVER }
    Column(
        modifier.pearlPanel(TT.DriverCard, TT.OutlineSoft, RoundedCornerShape(26.dp), 3.dp)
            .padding(if (large) 30.dp else 24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(if (large) 28.dp else 22.dp, Alignment.CenterVertically),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("LIVE INTERPRET", color = TT.Champagne, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                Text(if (large) "최근 핵심 통역" else "최근 통역 대화", color = TT.DriverText, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Pill(if (large) "FOCUS" else "PARK", TT.DriverCardSoft, TT.DriverSub)
        }
        ui.partialText?.let {
            Text(
                "듣는 중  ·  $it",
                color = TT.VoiceActive,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.background(TT.VoiceActive.copy(alpha = 0.12f), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
        if (passenger == null && driver == null) {
            Text(
                ko.getString(R.string.drv_empty_manual),
                color = TT.DriverSub,
                fontSize = if (large) 28.sp else 23.sp,
                lineHeight = if (large) 38.sp else 32.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        passenger?.let { utterance ->
            UtteranceBlock(
                who = ko.getString(R.string.drv_passenger),
                color = TT.Accent,
                main = utterance.translated ?: utterance.original,
                sub = "${utterance.sourceLang.flag} ${utterance.original}",
                sizeSp = if (large) 48 else 38,
            )
        }
        driver?.let { utterance ->
            UtteranceBlock(
                who = ko.getString(R.string.drv_me),
                color = TT.Champagne,
                main = utterance.original,
                sub = utterance.translated?.let { "${utterance.targetLang.flag} $it" },
                sizeSp = if (large) 30 else 26,
            )
        }
    }
}

@Composable
private fun UtteranceBlock(who: String, color: Color, main: String, sub: String?, sizeSp: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(4.dp).height((sizeSp * 1.8f).dp).background(color, RoundedCornerShape(8.dp)))
        Column {
            Text(who, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
            Spacer(Modifier.height(5.dp))
            Text(main, color = TT.DriverText, fontSize = sizeSp.sp, lineHeight = (sizeSp * 1.22f).sp, fontWeight = FontWeight.Bold)
            sub?.let {
                Spacer(Modifier.height(7.dp))
                Text(it, color = TT.DriverSub, fontSize = 19.sp, lineHeight = 25.sp)
            }
        }
    }
}

/** Vehicle cabin information shown only in the P-gear dashboard. */
@Composable
private fun VehicleCard(v: VehicleState, ko: Context, modifier: Modifier) {
    Column(
        modifier.pearlPanel(TT.DriverCard, TT.OutlineSoft, RoundedCornerShape(24.dp), 2.dp).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("VEHICLE CABIN", color = TT.Champagne, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 0.9.sp)
                Text("승객 안전 상태", color = TT.DriverText, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            }
            Pill("기어 ${v.gear.label()}", TT.DriverCardRaised, TT.DriverText)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            CarTopView(v, Modifier.width(120.dp).height(196.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SeatRow(ko.getString(R.string.drv_seat_left), v.rearLeftOccupied, v.rearLeftBelted, ko)
                SeatRow(ko.getString(R.string.drv_seat_right), v.rearRightOccupied, v.rearRightBelted, ko)
                DoorRow(ko.getString(R.string.drv_door_left), v.rearLeftDoorOpen, ko)
                DoorRow(ko.getString(R.string.drv_door_right), v.rearRightDoorOpen, ko)
            }
        }
        Text(
            "주행 거리  ·  ${v.tripDistanceKm?.let { String.format(Locale.KOREA, "%.1f km", it) } ?: "수신 대기"}",
            color = TT.DriverSub,
            fontSize = 15.sp,
        )
    }
}

@Composable
private fun SeatRow(label: String, occupied: Boolean, belted: Boolean, ko: Context) {
    val (res, color) = when {
        !occupied -> R.string.drv_seat_empty to TT.DriverSub
        belted -> R.string.drv_seat_belted to TT.Ok
        else -> R.string.drv_seat_unbelted to TT.Danger
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.width(7.dp))
        Text("$label  ${ko.getString(res)}", color = color, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DoorRow(label: String, open: Boolean, ko: Context) {
    val color = if (open) TT.Danger else TT.DriverSub
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.width(7.dp))
        Text(
            "$label  ${ko.getString(if (open) R.string.drv_door_open else R.string.drv_door_closed)}",
            color = color,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun CarTopView(v: VehicleState, modifier: Modifier) {
    Canvas(modifier) {
        val w = minOf(size.width * 0.72f, size.height * 0.46f)
        val h = w * 2f
        val left = (size.width - w) / 2
        val top = (size.height - h) / 2
        drawRoundRect(TT.DeepSlate, Offset(left, top), Size(w, h), CornerRadius(w * 0.30f))
        drawRoundRect(TT.MidnightNavy, Offset(left + w * 0.12f, top + h * 0.16f), Size(w * 0.76f, h * 0.68f), CornerRadius(w * 0.12f))
        val seatW = w * 0.28f
        val seatH = h * 0.16f
        fun seat(x: Float, y: Float, color: Color) = drawRoundRect(color, Offset(x, y), Size(seatW, seatH), CornerRadius(seatW * 0.25f))
        fun rear(occupied: Boolean, belted: Boolean) = when {
            occupied && belted -> TT.Ok
            occupied -> TT.Danger
            else -> TT.Outline
        }
        val leftSeat = left + w * 0.17f
        val rightSeat = left + w * 0.55f
        seat(leftSeat, top + h * 0.24f, TT.Champagne)
        seat(rightSeat, top + h * 0.24f, TT.Outline)
        seat(leftSeat, top + h * 0.58f, rear(v.rearLeftOccupied, v.rearLeftBelted))
        seat(rightSeat, top + h * 0.58f, rear(v.rearRightOccupied, v.rearRightBelted))
        val doorY = top + h * 0.54f
        val doorH = h * 0.24f
        val thickness = w * 0.06f
        if (v.rearLeftDoorOpen) drawRoundRect(TT.Danger, Offset(left - thickness, doorY), Size(thickness * 2, doorH), CornerRadius(thickness))
        if (v.rearRightDoorOpen) drawRoundRect(TT.Danger, Offset(left + w - thickness, doorY), Size(thickness * 2, doorH), CornerRadius(thickness))
    }
}

/** Uses only available navigation events; it never invents a destination that the SDK did not provide. */
@Composable
private fun RouteSnapshotCard(ui: UiState, vehicle: VehicleState, ko: Context, modifier: Modifier) {
    val latestEta = ui.announceLog.lastOrNull { it.type == AnnouncementType.ETA }
    val latestRoute = ui.announceLog.lastOrNull {
        it.type == AnnouncementType.ROUTE_TRAFFIC || it.type == AnnouncementType.ROUTE_ACCIDENT ||
            it.type == AnnouncementType.ROUTE_ROADWORK || it.type == AnnouncementType.ROUTE_OTHER ||
            it.type == AnnouncementType.APPROACHING
    }
    Column(
        modifier.pearlPanel(TT.DriverCardSoft, TT.OutlineSoft, RoundedCornerShape(22.dp), 2.dp).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("ROUTE / ETA", color = TT.Champagne, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 0.9.sp)
        Text(
            latestEta?.let { ko.getString(it.type.res, *it.args.toTypedArray()) } ?: "ETA 정보 대기",
            color = TT.DriverText,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            when {
                ui.arrivedAt != null && ui.arrivedAt.isNotBlank() -> "목적지  ·  ${ui.arrivedAt}"
                ui.arrivedAt != null -> ko.getString(R.string.drv_arrived)
                latestRoute != null -> ko.getString(latestRoute.type.res, *latestRoute.args.toTypedArray())
                vehicle.source == VehicleState.Source.PLEOS -> "내비 목적지 정보 수신 대기"
                else -> "시뮬레이션 내비 안내 대기"
            },
            color = TT.DriverSub,
            fontSize = 15.sp,
            lineHeight = 21.sp,
        )
    }
}

/** Vehicle and navigation announcements remain visible in the full P-gear dashboard. */
@Composable
private fun AnnouncementLog(log: List<Announcement>, ko: Context, modifier: Modifier) {
    Column(
        modifier.pearlPanel(TT.DriverCard, TT.OutlineSoft, RoundedCornerShape(24.dp), 2.dp).padding(18.dp),
    ) {
        Text("AUTO GUIDANCE", color = TT.Champagne, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 0.9.sp)
        Text(ko.getString(R.string.drv_auto_log), color = TT.DriverText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            if (log.isEmpty()) Text(ko.getString(R.string.drv_auto_log_empty), color = TT.DriverSub, fontSize = 16.sp)
            log.asReversed().forEach { announcement ->
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(6.dp).background(TT.Champagne, CircleShape))
                    Text(
                        ko.getString(announcement.type.res, *announcement.args.toTypedArray()),
                        color = TT.DriverText,
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                    )
                }
            }
        }
    }
}
