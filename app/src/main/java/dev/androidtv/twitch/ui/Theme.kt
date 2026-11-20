package dev.androidtv.twitch.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.androidtv.twitch.R

object Palette {
    val Background = Color(0xFF0B0D12)
    val Surface = Color(0xFF161A22)
    val SurfaceHigh = Color(0xFF1F2430)
    val Placeholder = Color(0xFF2A3140)
    val Track = Color(0xFF3A4252)
    val Text = Color(0xFFF3F4F6)
    val TextSecondary = Color(0xFFC9CED6)
    val TextMuted = Color(0xFFA3ABB8)
    val Accent = Color(0xFF35D69B)
    val Live = Color(0xFFC8323A)
    val Scrim = Color(0xE00B0D12)
}

/** Both fonts are variable: the weight axis has to be set explicitly, or every weight renders as Regular. */
@OptIn(ExperimentalTextApi::class)
private fun variable(resId: Int, weight: FontWeight) =
    Font(resId, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

private val Inter = FontFamily(
    variable(R.font.inter, FontWeight.Normal),
    variable(R.font.inter, FontWeight.Medium),
    variable(R.font.inter, FontWeight.SemiBold),
)

private val SpaceGrotesk = FontFamily(variable(R.font.space_grotesk, FontWeight.Bold))

object Type {
    val Display = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 36.sp, color = Palette.Text)
    val Heading = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Palette.Text)
    val Code = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 54.sp, letterSpacing = 7.sp, color = Palette.Accent)
    val Title = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Palette.Text)
    val Body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 18.sp, color = Palette.TextSecondary)
    val Button = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Palette.Text)
    val Label = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Palette.Text)
    val Small = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 11.sp, color = Palette.TextMuted)
    val Badge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 9.sp, letterSpacing = 0.4.sp, color = Color.White)
}
