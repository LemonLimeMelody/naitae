package com.naitae.connect.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.naitae.connect.ui.NaitaeActions
import com.naitae.connect.ui.Screen
import com.naitae.connect.ui.UiState

/** 오늘을 남길까요? (구현 예정: ui-B 담당. 시그니처는 계약) */
@Composable
fun RecordAskScreen(state: Screen.RecordAsk, ui: UiState, actions: NaitaeActions, modifier: Modifier = Modifier) {
    Box(modifier)
}

/** 남긴 기록 + 한마디 (구현 예정: ui-B 담당. 시그니처는 계약) */
@Composable
fun RecordEditScreen(state: Screen.RecordEdit, ui: UiState, actions: NaitaeActions, modifier: Modifier = Modifier) {
    Box(modifier)
}

/** 함께 꾸미기 (구현 예정: ui-B 담당. 시그니처는 계약) */
@Composable
fun DecorateScreen(state: Screen.Decorate, ui: UiState, actions: NaitaeActions, modifier: Modifier = Modifier) {
    Box(modifier)
}
