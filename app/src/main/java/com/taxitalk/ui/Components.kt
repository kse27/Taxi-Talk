package com.taxitalk.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taxitalk.model.TurnState
import kotlinx.coroutines.delay

/** Reusable elevated cabin surface for Midnight Pearl panels. */
fun Modifier.pearlPanel(
    color: Color = TT.DriverCard,
    borderColor: Color = TT.OutlineSoft,
    shape: Shape = RoundedCornerShape(26.dp),
    elevation: Dp = 0.dp,
): Modifier = shadow(elevation, shape, clip = false)
    .background(color, shape)
    .border(1.dp, borderColor, shape)

/**
 * Large voice control used by both physical-seat roles.
 * The click contract, turn state and microphone-level data are unchanged; this
 * component only gives each state a restrained visual language.
 */
@Composable
fun TalkButton(
    state: TurnState,
    label: String,
    activeLabel: String,
    direction: String,
    color: Color,
    contentColor: Color,
    onClick: () -> Unit,
    level: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val listening = state == TurnState.LISTENING
    val idle = state == TurnState.IDLE
    val stateColor = when (state) {
        TurnState.IDLE -> color
        TurnState.LISTENING -> TT.VoiceActive
        TurnState.RECOGNIZING, TurnState.TRANSLATING -> TT.Processing
        TurnState.SPEAKING -> TT.Speaking
        TurnState.ERROR -> TT.Danger
    }
    val foreground = when (state) {
        TurnState.IDLE -> contentColor
        TurnState.RECOGNIZING, TurnState.TRANSLATING -> TT.MidnightNavy
        else -> Color.White
    }
    val transition = rememberInfiniteTransition(label = "voiceControl")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (listening) 1.018f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "voicePulse",
    )
    val glowAlpha by transition.animateFloat(
        initialValue = 0.16f,
        targetValue = if (listening) 0.38f else 0.16f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "voiceGlow",
    )
    val shape = RoundedCornerShape(30.dp)
    val brush = Brush.linearGradient(
        listOf(
            stateColor,
            stateColor.copy(alpha = if (idle) 0.80f else 0.88f),
        ),
    )

    Box(
        modifier = modifier
            .graphicsLayer(scaleX = pulse, scaleY = pulse)
            .shadow(if (listening) 14.dp else 6.dp, shape, clip = false)
            .clip(shape)
            .background(brush)
            .border(1.dp, Color.White.copy(alpha = glowAlpha), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(50.dp)
                        .background(Color.White.copy(alpha = if (idle) 0.18f else 0.22f), CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (listening) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = null,
                        tint = foreground,
                        modifier = Modifier.size(29.dp),
                    )
                }
                Column {
                    Text(
                        if (idle) label else activeLabel,
                        color = foreground,
                        fontSize = 27.sp,
                        lineHeight = 32.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(direction, color = foreground.copy(alpha = 0.76f), fontSize = 15.sp)
                }
            }
            if (listening) VoiceWave(level)
        }
    }
}

/** Small, data-driven waveform. It is deliberately subtle while the car is moving. */
@Composable
private fun VoiceWave(level: Float) {
    val normalized = level.coerceIn(0.02f, 1f)
    Row(
        Modifier.fillMaxWidth(0.72f).height(16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(7) { index ->
            val rhythm = if (index in 2..4) 1f else 0.68f
            val barHeight = (4f + normalized * 12f * rhythm).dp
            Box(
                Modifier.weight(1f).height(barHeight)
                    .background(Color.White.copy(alpha = 0.86f), RoundedCornerShape(8.dp)),
            )
        }
    }
}

@Composable
fun PhraseRow(phrases: List<String>, dark: Boolean, onPick: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(phrases) { phrase ->
            val surface = if (dark) TT.DriverCardSoft else TT.RearCard
            val textColor = if (dark) TT.DriverText else TT.RearText
            Text(
                phrase,
                color = textColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .defaultMinSize(minHeight = 42.dp)
                    .background(surface, RoundedCornerShape(18.dp))
                    .border(1.dp, if (dark) TT.Outline else Color(0xFFE5DCD4), RoundedCornerShape(18.dp))
                    .clickable { onPick(phrase) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
fun TextInputRow(hint: String, dark: Boolean, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val foreground = if (dark) TT.DriverText else TT.RearText
    val outline = if (dark) TT.Outline else Color(0xFFD9D0C7)
    val focused = if (dark) TT.Champagne else TT.Accent
    val send = { if (text.isNotBlank()) { onSubmit(text); text = "" } }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            placeholder = { Text(hint, color = if (dark) TT.DriverSub else TT.RearSub) },
            modifier = Modifier.weight(1f).heightIn(min = 58.dp),
            shape = RoundedCornerShape(18.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = foreground,
                unfocusedTextColor = foreground,
                cursorColor = focused,
                focusedBorderColor = focused,
                unfocusedBorderColor = outline,
                focusedContainerColor = if (dark) TT.InputSurface else TT.RearCard,
                unfocusedContainerColor = if (dark) TT.InputSurface else TT.RearCard,
            ),
        )
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = send,
            modifier = Modifier.size(52.dp).background(if (dark) TT.Champagne else TT.Accent, CircleShape),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = if (dark) TT.MidnightNavy else Color.White)
        }
    }
}

/** Vehicle or navigation guidance that is informative, not an emergency override. */
@Composable
fun NoticeBanner(text: String, dark: Boolean, prefix: String? = null) {
    val surface = if (dark) Color(0xFF3B342A) else Color(0xFFFFF1D8)
    val foreground = if (dark) TT.DriverText else TT.RearText
    val accent = if (dark) TT.Champagne else Color(0xFF9B6D2E)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .background(surface, RoundedCornerShape(18.dp))
            .border(1.dp, accent.copy(alpha = 0.42f), RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        Icon(Icons.Default.Campaign, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            if (prefix != null) Text(prefix, color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(text, color = foreground, fontSize = if (dark) 18.sp else 20.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun ErrorBanner(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .background(TT.Danger.copy(alpha = 0.16f), RoundedCornerShape(18.dp))
            .border(1.dp, TT.Danger.copy(alpha = 0.62f), RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = TT.Danger)
        Spacer(Modifier.width(10.dp))
        Text(text, color = TT.DriverText, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

/** Compact touch target shared by status and developer controls. */
@Composable
fun Pill(text: String, bg: Color, fg: Color = Color.White, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(50)
    Text(
        text = text,
        color = fg,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .defaultMinSize(minHeight = 36.dp)
            .background(bg, shape)
            .border(1.dp, Color.White.copy(alpha = if (onClick == null) 0.08f else 0.16f), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** Park-only two-step end action. It preserves [onConfirm] and prevents accidental taps. */
@Composable
fun ConfirmEndRidePill(onConfirm: () -> Unit) {
    var waitingForConfirmation by remember { mutableStateOf(false) }
    LaunchedEffect(waitingForConfirmation) {
        if (waitingForConfirmation) {
            delay(4_000)
            waitingForConfirmation = false
        }
    }
    Pill(
        text = if (waitingForConfirmation) "한 번 더 눌러 종료" else "운행 종료",
        bg = if (waitingForConfirmation) TT.Danger else TT.DriverCardSoft,
        fg = if (waitingForConfirmation) Color.White else TT.DustyRose,
        onClick = {
            if (waitingForConfirmation) onConfirm() else waitingForConfirmation = true
        },
    )
}
