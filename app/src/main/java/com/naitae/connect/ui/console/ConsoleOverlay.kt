package com.naitae.connect.ui.console

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.naitae.connect.vehicle.SimulatorConsole

/**
 * 가상 콘솔 패널 (구현 예정: ui-A 담당). 시뮬레이터일 때만 화면 오른쪽에 붙는다.
 * 날짜 6개, 시동/기어, 목적지 도착, 좌석 착석, SDK 호출 로그(SdkLog.lines).
 */
@Composable
fun ConsoleOverlay(console: SimulatorConsole, modifier: Modifier = Modifier) {
    Box(modifier)
}
