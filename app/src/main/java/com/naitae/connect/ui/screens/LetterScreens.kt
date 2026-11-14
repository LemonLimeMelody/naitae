package com.naitae.connect.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.naitae.connect.ui.NaitaeActions
import com.naitae.connect.ui.Screen
import com.naitae.connect.ui.UiState

/** 편지가 도착했어요 (구현 예정: ui-B 담당. 시그니처는 계약) */
@Composable
fun LetterArriveScreen(state: Screen.LetterArrive, ui: UiState, actions: NaitaeActions, modifier: Modifier = Modifier) {
    Box(modifier)
}

/** 편지 연출·낭독 (구현 예정: ui-B 담당. 시그니처는 계약) */
@Composable
fun LetterOpenScreen(state: Screen.LetterOpen, ui: UiState, actions: NaitaeActions, modifier: Modifier = Modifier) {
    Box(modifier)
}
