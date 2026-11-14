package com.naitae.connect.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.naitae.connect.core.model.AmbientColor

/* 색·글자 토큰 (계약 파일). 화면은 여기 있는 값만 쓴다. */
object NaitaeColors {
    val Navy = Color(0xFF16324F)
    val Ink2 = Color(0xFF55687B)
    val Slate = Color(0xFF8394A5)
    val Light = Color(0xFFE9EEF3)
    val Bg = Color(0xFFF5F7FA)
    val Screen = Color(0xFFFBFCFD)
    val Amber = Color(0xFFD98E2B)
    val AmberInk = Color(0xFFA9651A)
    val Soft = Color(0xFFFBEBD3)
    val Ring2 = Color(0xFFF5D7A9)
    val Purple = Color(0xFF8E6CC0)
    val White = Color(0xFFFFFFFF)

    /** 다시 보기·편지 같은 어두운 화면 */
    val DarkBg = Color(0xFF16141B)
    val DarkCard = Color(0xFF2A2433)
    val DarkText = Color(0xFFEEE9F5)
    val DarkSub = Color(0xFFA79FB5)
}

fun AmbientColor.toColor(): Color = Color(argb)

private val Scheme = lightColorScheme(
    primary = NaitaeColors.Amber,
    onPrimary = NaitaeColors.White,
    secondary = NaitaeColors.Navy,
    onSecondary = NaitaeColors.White,
    background = NaitaeColors.Screen,
    onBackground = NaitaeColors.Navy,
    surface = NaitaeColors.Screen,
    onSurface = NaitaeColors.Navy,
    surfaceVariant = NaitaeColors.Bg,
    onSurfaceVariant = NaitaeColors.Ink2,
)

@Composable
fun NaitaeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
