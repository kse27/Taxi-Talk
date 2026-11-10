package com.taxitalk.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Midnight Pearl design tokens.
 *
 * The driver display remains deliberately dark for a low-glare cabin experience.
 * Champagne, dusty rose and pearl surfaces provide hierarchy without turning
 * operational or safety states into decorative colors.
 */
object TT {
    // Brand foundation
    val MidnightNavy = Color(0xFF111722)
    val DeepSlate = Color(0xFF2B3546)
    val PearlIvory = Color(0xFFF6F1EB)
    val DustyRose = Color(0xFFE9B1AA)
    val Champagne = Color(0xFFC4A989)

    // Cabin surfaces
    val DriverBg = MidnightNavy
    val DriverCard = Color(0xFF1B2433)
    val DriverCardRaised = Color(0xFF253145)
    val DriverCardSoft = Color(0xFF202B3D)
    val DriverText = PearlIvory
    val DriverSub = Color(0xFFABB5C5)
    val Outline = Color(0xFF3D4A60)
    val OutlineSoft = Color(0xFF2F3B50)
    val InputSurface = Color(0xFF161F2D)

    // Semantic accents. These remain distinct from safety colors.
    val Yellow = Champagne
    val Accent = Color(0xFF92A5D6)
    val VoiceActive = DustyRose
    val Processing = Color(0xFFD6C2A4)
    val Speaking = Color(0xFF8FB5D1)
    val Danger = Color(0xFFD95C68)
    val Warning = Color(0xFFE0A35B)
    val Ok = Color(0xFF6EB695)

    // Existing aliases kept for shared components and future rear-display work.
    val RearBg = PearlIvory
    val RearCard = Color(0xFFFFFBF7)
    val RearText = MidnightNavy
    val RearSub = Color(0xFF687082)
    val DemoBg = Color(0xFF182131)
}

@Composable
fun TaxiTalkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = TT.Champagne,
            onPrimary = TT.MidnightNavy,
            primaryContainer = Color(0xFF574938),
            onPrimaryContainer = TT.PearlIvory,
            secondary = TT.DustyRose,
            onSecondary = TT.MidnightNavy,
            secondaryContainer = Color(0xFF5B4143),
            onSecondaryContainer = TT.PearlIvory,
            background = TT.DriverBg,
            onBackground = TT.DriverText,
            surface = TT.DriverCard,
            onSurface = TT.DriverText,
            surfaceVariant = TT.DriverCardRaised,
            onSurfaceVariant = TT.DriverSub,
            error = TT.Danger,
            onError = Color.White,
        ),
        content = content,
    )
}
