package com.naitae.connect.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naitae.connect.ui.NaitaeActions
import com.naitae.connect.ui.UiState
import com.naitae.connect.ui.console.ConsoleOverlay
import com.naitae.connect.ui.screens.NaitaeRoot
import com.naitae.connect.ui.theme.NaitaeColors
import com.naitae.connect.vehicle.CabinState
import com.naitae.connect.vehicle.SimulatorConsole

/** 가상 콘솔이 차지하는 너비의 비율 */
private const val CONSOLE_FRACTION = 0.28f

/** 가상 콘솔이 쓸 수 있는 가장 좁은 너비와, 차량 화면을 가리지 않는 한도 */
private val ConsoleMinWidth = 280.dp
private const val CONSOLE_MAX_FRACTION = 0.45f

private val HandleWidth = 26.dp

/** 시뮬레이터의 실내 조명 표현: 빛이 번지는 폭과, 밝기 100 일 때의 진하기 */
private val GlowWidth = 44.dp
private const val GLOW_MAX_ALPHA = 0.8f
private const val GLOW_STEP_MS = 320

/**
 * 앱 화면 배치: 왼쪽에 차량 화면(NaitaeRoot), 오른쪽에 가상 콘솔(시뮬레이터) 또는 시연 패널(Pleos 모드).
 * 사이의 손잡이를 눌러 접고 펼 수 있다(접으면 차량 화면이 전체를 쓴다). Pleos 모드에서는 접힌 채로 시작한다.
 */
@Composable
fun NaitaeAppScreen(state: UiState, actions: NaitaeActions, console: SimulatorConsole?) {
    // 차의 신호까지 넣는 콘솔 = 시뮬레이터. 실제 차에 연결된 시연 패널은 필요할 때만 편다.
    val simulated = console?.controlsVehicle == true
    var consoleShown by rememberSaveable { mutableStateOf(simulated) }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // 시스템 바나 화면 잘림이 남아 있는 기기에서도 내용이 가려지지 않게 한다.
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val consoleWidth = (maxWidth * CONSOLE_FRACTION)
            .coerceAtLeast(ConsoleMinWidth)
            .coerceAtMost(maxWidth * CONSOLE_MAX_FRACTION)
        Row(Modifier.fillMaxSize()) {
            NaitaeRoot(
                state = state,
                actions = actions,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // 실제 차에서는 실내 조명이 직접 바뀌므로 화면에 흉내 내지 않는다.
                    .then(if (simulated) Modifier.cabinGlow(state.cabin) else Modifier),
            )
            if (console != null) {
                ConsoleHandle(
                    name = if (simulated) "가상 콘솔" else "시연 패널",
                    shown = consoleShown,
                    onToggle = { consoleShown = !consoleShown },
                )
                if (consoleShown) {
                    ConsoleOverlay(
                        console = console,
                        modifier = Modifier.width(consoleWidth).fillMaxHeight(),
                        cabin = state.cabin,
                    )
                }
            }
        }
    }
}

/**
 * 시뮬레이터에는 실내가 없으므로, 차량 화면의 가장자리에 지금 실내 조명의 색과 밝기를 빛으로 비춘다
 * (꾸미기의 색, 다시 보기의 그날 색, 편지에서 서서히 밝아지는 조명이 눈에 보이게).
 */
@Composable
private fun Modifier.cabinGlow(cabin: CabinState): Modifier {
    val target = cabin.ambientColor?.let { Color(it.argb) }
    val level by animateFloatAsState(
        targetValue = if (target == null) 0f else (cabin.ambientBrightness ?: 0).coerceIn(0, 100) / 100f,
        animationSpec = tween(durationMillis = GLOW_STEP_MS),
        label = "cabinGlowLevel",
    )
    val color by animateColorAsState(
        targetValue = target ?: Color.Transparent,
        animationSpec = tween(durationMillis = GLOW_STEP_MS),
        label = "cabinGlowColor",
    )
    return drawWithContent {
        drawContent()
        if (level <= 0f) return@drawWithContent
        val edge = color.copy(alpha = GLOW_MAX_ALPHA * level)
        val clear = color.copy(alpha = 0f)
        val w = GlowWidth.toPx()
        drawRect(Brush.verticalGradient(listOf(edge, clear), 0f, w), size = Size(size.width, w))
        drawRect(
            brush = Brush.verticalGradient(listOf(clear, edge), size.height - w, size.height),
            topLeft = Offset(0f, size.height - w),
            size = Size(size.width, w),
        )
        drawRect(Brush.horizontalGradient(listOf(edge, clear), 0f, w), size = Size(w, size.height))
        drawRect(
            brush = Brush.horizontalGradient(listOf(clear, edge), size.width - w, size.width),
            topLeft = Offset(size.width - w, 0f),
            size = Size(w, size.height),
        )
    }
}

/** 가상 콘솔을 접고 펴는 작은 손잡이. 차량 화면 위에 겹치지 않도록 두 영역 사이에 둔다. */
@Composable
private fun ConsoleHandle(name: String, shown: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(HandleWidth)
            .background(NaitaeColors.Navy)
            .clickable(
                onClickLabel = if (shown) "$name 접기" else "$name 펴기",
                onClick = onToggle,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (shown) "›" else "‹",
            color = NaitaeColors.Ring2,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
