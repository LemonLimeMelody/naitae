package com.naitae.connect.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.naitae.connect.ui.NaitaeActions
import com.naitae.connect.ui.UiState

/**
 * 화면 진입점 (계약). state.screen 에 맞는 화면을 그린다.
 * 구현 예정: ui-A 가 이 파일과 공통 틀(상단 바·자막·메모 카드·주행 중 잠금)을, 각 화면은 ui-A / ui-B 가 나눠 만든다.
 */
@Composable
fun NaitaeRoot(state: UiState, actions: NaitaeActions, modifier: Modifier = Modifier) {
    Box(modifier)
}
