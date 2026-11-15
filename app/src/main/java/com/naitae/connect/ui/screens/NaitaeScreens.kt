package com.naitae.connect.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.naitae.connect.core.model.Memo
import com.naitae.connect.ui.NaitaeActions
import com.naitae.connect.ui.Screen
import com.naitae.connect.ui.UiState
import com.naitae.connect.ui.screens.frame.DrivingLock
import com.naitae.connect.ui.screens.frame.EngineOffBody
import com.naitae.connect.ui.screens.frame.FrameSpec
import com.naitae.connect.ui.screens.frame.FrameTopBar
import com.naitae.connect.ui.screens.frame.MemoToastCard
import com.naitae.connect.ui.screens.frame.VoiceCaptionBar
import com.naitae.connect.ui.screens.frame.designScale
import com.naitae.connect.ui.screens.frame.isDark
import com.naitae.connect.ui.theme.NaitaeColors

/**
 * 화면 진입점 (계약). state.screen 에 맞는 화면을 그린다.
 *
 * 공통 틀: 상단 바(앱 이름 · 차 이름, 시계, 기어) + 화면 본문 + 메모 카드 + 화면 아래 음성 자막.
 * 본문은 상단 바 아래 영역 전체를 받는다(여백은 화면마다 스스로 준다). 바탕색은 틀이 칠한다.
 * 주행 중(state.driving)에는 보이지 않는 덮개가 본문의 터치를 모두 삼킨다.
 *
 * 화면은 960×540dp 를 기준으로 설계하고, 실제 화면이 그보다 크면 밀도를 올려 통째로 키운다.
 * 그래서 안쪽 화면이 보는 크기는 늘 960×540dp 안팎이고, 글자·버튼은 화면이 클수록 함께 커진다.
 */
@Composable
fun NaitaeRoot(state: UiState, actions: NaitaeActions, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier) {
        val device = LocalDensity.current
        val scale = if (constraints.hasBoundedWidth && constraints.hasBoundedHeight) {
            designScale(maxWidth, maxHeight)
        } else {
            1f
        }
        CompositionLocalProvider(
            LocalDensity provides Density(device.density * scale, device.fontScale),
        ) {
            NaitaeFrame(state, actions)
        }
    }
}

/** 화면 전환(서로 스며들기)을 화면 종류가 바뀔 때만 하려고 쓰는 열쇠 */
private enum class FrameKey {
    OFF, HOME, BRIEFING, ROUTE_ASK, RECORD_ASK, RECORD_EDIT, DECORATE, RECALL, LETTER_ARRIVE, LETTER_OPEN, HANDOVER,
}

private fun frameKeyOf(screen: Screen): FrameKey = when (screen) {
    Screen.EngineOff -> FrameKey.OFF
    is Screen.Home -> FrameKey.HOME
    is Screen.Briefing -> FrameKey.BRIEFING
    is Screen.RouteAsk -> FrameKey.ROUTE_ASK
    is Screen.RecordAsk -> FrameKey.RECORD_ASK
    is Screen.RecordEdit -> FrameKey.RECORD_EDIT
    is Screen.Decorate -> FrameKey.DECORATE
    is Screen.RecallView -> FrameKey.RECALL
    is Screen.LetterArrive -> FrameKey.LETTER_ARRIVE
    is Screen.LetterOpen -> FrameKey.LETTER_OPEN
    is Screen.Handover -> FrameKey.HANDOVER
}

private data class ToastKey(val memo: Memo, val author: String?)

@Composable
private fun NaitaeFrame(state: UiState, actions: NaitaeActions) {
    val screen = state.screen
    val dark = screen.isDark()
    val engineOff = screen is Screen.EngineOff
    val key = frameKeyOf(screen)

    // Crossfade 는 처음 받은 content 람다를 붙들고 있을 수 있으므로, 안에서는 늘 최신 상태를 State 로 읽는다
    val latestState = rememberUpdatedState(state)
    val latestActions = rememberUpdatedState(actions)
    // 사라지는 화면이 스며드는 동안 그릴 마지막 모습
    val lastByKey = remember { HashMap<FrameKey, Screen>() }
    lastByKey[key] = screen

    val rootBg = when {
        engineOff -> FrameSpec.OffBg
        dark -> NaitaeColors.DarkBg
        else -> NaitaeColors.Screen
    }
    Column(Modifier.fillMaxSize().background(rootBg)) {
        FrameTopBar(
            carLabel = state.carLabel,
            clockLabel = state.clockLabel,
            gear = state.gear,
            dark = dark,
            engineOff = engineOff,
        )
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Crossfade(
                targetState = key,
                modifier = Modifier.fillMaxSize(),
                animationSpec = tween(durationMillis = 240),
                label = "screen",
            ) { shownKey ->
                val ui = latestState.value
                val shown = if (frameKeyOf(ui.screen) == shownKey) ui.screen else lastByKey[shownKey]
                if (shown != null) ScreenBody(shown, ui, latestActions.value)
            }

            // 주행 중 잠금: 본문 위를 덮어 터치를 받지 않는다(안내 문구는 띄우지 않는다)
            if (state.driving) DrivingLock(Modifier.matchParentSize())

            Crossfade(
                targetState = state.memoToast?.let { ToastKey(it, state.memoToastAuthor) },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(start = 40.dp, end = 40.dp, top = 24.dp),
                animationSpec = tween(durationMillis = 240),
                label = "memoToast",
            ) { toast ->
                if (toast != null) {
                    MemoToastCard(
                        memo = toast.memo,
                        author = toast.author,
                        tapEnabled = !latestState.value.driving,
                        onDismiss = { latestActions.value.dismissMemoToast() },
                    )
                }
            }

            Crossfade(
                targetState = state.voiceLine,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 40.dp, end = 40.dp, bottom = 14.dp),
                animationSpec = tween(durationMillis = 200),
                label = "voiceLine",
            ) { line ->
                if (!line.isNullOrBlank()) VoiceCaptionBar(line)
            }
        }
    }
}

@Composable
private fun ScreenBody(screen: Screen, ui: UiState, actions: NaitaeActions) {
    val bg = when {
        screen is Screen.EngineOff -> FrameSpec.OffBg
        screen.isDark() -> NaitaeColors.DarkBg
        else -> NaitaeColors.Screen
    }
    val fill = Modifier.fillMaxSize()
    Box(Modifier.fillMaxSize().background(bg)) {
        when (screen) {
            Screen.EngineOff -> EngineOffBody(fill)
            is Screen.Home -> HomeScreen(screen, ui, actions, fill)
            is Screen.Briefing -> BriefingScreen(screen, ui, actions, fill)
            is Screen.RouteAsk -> RouteAskScreen(screen, ui, actions, fill)
            is Screen.RecordAsk -> RecordAskScreen(screen, ui, actions, fill)
            is Screen.RecordEdit -> RecordEditScreen(screen, ui, actions, fill)
            is Screen.Decorate -> DecorateScreen(screen, ui, actions, fill)
            is Screen.RecallView -> RecallScreen(screen, ui, actions, fill)
            is Screen.LetterArrive -> LetterArriveScreen(screen, ui, actions, fill)
            is Screen.LetterOpen -> LetterOpenScreen(screen, ui, actions, fill)
            is Screen.Handover -> HandoverScreen(screen, ui, actions, fill)
        }
    }
}
