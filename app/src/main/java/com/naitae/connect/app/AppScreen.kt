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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naitae.connect.ui.NaitaeActions
import com.naitae.connect.ui.UiState
import com.naitae.connect.ui.console.ConsoleOverlay
import com.naitae.connect.ui.screens.NaitaeRoot
import com.naitae.connect.ui.theme.NaitaeColors
import com.naitae.connect.vehicle.SimulatorConsole

/** 가상 콘솔이 차지하는 너비의 비율 */
private const val CONSOLE_FRACTION = 0.28f

/** 가상 콘솔이 쓸 수 있는 가장 좁은 너비와, 차량 화면을 가리지 않는 한도 */
private val ConsoleMinWidth = 280.dp
private const val CONSOLE_MAX_FRACTION = 0.45f

private val HandleWidth = 26.dp

/**
 * 앱 화면 배치: 왼쪽에 차량 화면(NaitaeRoot), 시뮬레이터일 때만 오른쪽에 가상 콘솔.
 * 가상 콘솔은 사이의 손잡이를 눌러 접고 펼 수 있다(접으면 차량 화면이 전체를 쓴다).
 */
@Composable
fun NaitaeAppScreen(state: UiState, actions: NaitaeActions, console: SimulatorConsole?) {
    var consoleShown by rememberSaveable { mutableStateOf(true) }
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
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            if (console != null) {
                ConsoleHandle(shown = consoleShown, onToggle = { consoleShown = !consoleShown })
                if (consoleShown) {
                    ConsoleOverlay(
                        console = console,
                        modifier = Modifier.width(consoleWidth).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

/** 가상 콘솔을 접고 펴는 작은 손잡이. 차량 화면 위에 겹치지 않도록 두 영역 사이에 둔다. */
@Composable
private fun ConsoleHandle(shown: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(HandleWidth)
            .background(NaitaeColors.Navy)
            .clickable(
                onClickLabel = if (shown) "가상 콘솔 접기" else "가상 콘솔 펴기",
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
